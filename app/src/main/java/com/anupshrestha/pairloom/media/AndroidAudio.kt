package com.anupshrestha.pairloom.media

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import java.io.IOException

private const val SAMPLE_RATE = 48_000
private const val FRAME_SAMPLES = 480
private const val FRAME_BYTES = FRAME_SAMPLES * 2

/**
 * The microphone through the voice-communication path (the platform's echo cancellation, noise
 * suppression and gain control where available), 48 kHz mono 16-bit, read in 10 ms frames.
 * When [muted], frames are still read (to keep the clock) but silence is sent.
 */
class MicrophoneSource(private val log: (String) -> Unit) : FrameSource {
    override val paced = true
    override val frameBytes = FRAME_BYTES

    @Volatile var muted = false

    private val record: AudioRecord
    private val effects = mutableListOf<android.media.audiofx.AudioEffect>()

    init {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) throw IOException("the microphone does not support 48 kHz mono 16-bit")
        @SuppressLint("MissingPermission") // the caller only creates this after RECORD_AUDIO was granted
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4 * FRAME_BYTES),
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw IOException("the microphone could not be opened")
        }
        record = r
        attach("echo canceller", AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(r.audioSessionId) }
        attach("noise suppressor", NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(r.audioSessionId) }
        attach("gain control", AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(r.audioSessionId) }
        r.startRecording()
        log("Microphone open: buffer ${maxOf(min, 4 * FRAME_BYTES)} bytes (minimum $min)")
    }

    private fun attach(name: String, available: Boolean, create: () -> android.media.audiofx.AudioEffect?) {
        if (!available) return log("Microphone: no platform $name")
        val effect = runCatching { create() }.getOrNull()
        if (effect == null) return log("Microphone: platform $name could not be created")
        runCatching { effect.enabled = true }
        effects += effect
        log("Microphone: platform $name ${if (effect.enabled) "on" else "off"}")
    }

    override fun read(index: Long): ByteArray {
        val frame = ByteArray(FRAME_BYTES)
        var done = 0
        while (done < FRAME_BYTES) {
            val n = record.read(frame, done, FRAME_BYTES - done)
            if (n <= 0) throw IOException("microphone read failed ($n)")
            done += n
        }
        return if (muted) ByteArray(FRAME_BYTES) else frame
    }

    override fun close() {
        runCatching { record.stop() }
        effects.forEach { runCatching { it.release() } }
        record.release()
    }
}

/**
 * The voice-communication output stream, 48 kHz mono 16-bit, low-latency mode, written per frame.
 *
 * The stream's buffer is allocated at the platform minimum, but only a small part of it is used:
 * the effective size starts at two device bursts ([framesPerBurst], from AudioManager) and grows
 * by one burst whenever the device reports an underrun, up to the allocated capacity. The delay
 * actually added is measured with AudioTrack timestamps ([latencyMs]).
 */
class SpeakerSink(private val log: (String) -> Unit, private val framesPerBurst: Int = FRAME_SAMPLES) : FrameSink {
    override val paced = true
    private val track: AudioTrack
    @Volatile private var framesWritten = 0L
    private var writes = 0
    private var underruns = 0
    private val timestamp = android.media.AudioTimestamp()

    init {
        val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) throw IOException("the speaker does not support 48 kHz mono 16-bit")
        val size = maxOf(min, 2 * FRAME_BYTES)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IOException("the speaker could not be opened")
        }
        val capacity = track.bufferCapacityInFrames
        val wanted = (2 * maxOf(framesPerBurst, 1)).coerceAtMost(capacity)
        val applied = track.setBufferSizeInFrames(wanted)
        track.play()
        log("Speaker open: capacity $capacity frames (minimum $min bytes), using $applied frames (${applied / 48} ms), burst $framesPerBurst")
    }

    override fun write(frame: ByteArray) {
        var done = 0
        while (done < frame.size) {
            val n = track.write(frame, done, frame.size - done)
            if (n < 0) throw IOException("speaker write failed ($n)")
            done += n
        }
        framesWritten += frame.size / 2
        if (++writes % 50 == 0) {
            val now = track.underrunCount
            if (now > underruns) {
                underruns = now
                val bigger = (track.bufferSizeInFrames + maxOf(framesPerBurst, 1)).coerceAtMost(track.bufferCapacityInFrames)
                if (bigger > track.bufferSizeInFrames) {
                    val applied = track.setBufferSizeInFrames(bigger)
                    log("Speaker underran ($now so far); buffer now $applied frames (${applied / 48} ms)")
                }
            }
        }
    }

    /**
     * Frames written but not yet heard, from the latest presentation timestamp, extrapolated to
     * now. Null until the device reports a timestamp.
     */
    override val latencyMs: Double?
        get() = synchronized(timestamp) {
            if (!runCatching { track.getTimestamp(timestamp) }.getOrDefault(false)) return null
            val elapsedFrames = (System.nanoTime() - timestamp.nanoTime) * SAMPLE_RATE / 1_000_000_000L
            val pending = framesWritten - (timestamp.framePosition + elapsedFrames)
            (pending.coerceAtLeast(0) * 1000.0 / SAMPLE_RATE)
        }

    override fun close() {
        runCatching { track.pause() }
        runCatching { track.flush() }
        track.release()
    }
}

/**
 * Phone-call style audio for the duration of a call: audio focus, communication mode, and the
 * earpiece or the loudspeaker. [onFocusLost] is called if another app (or a phone call) takes over.
 */
class CallAudio(context: Context, private val log: (String) -> Unit, private val onFocusLost: () -> Unit) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focus: AudioFocusRequest? = null
    private var previousMode = AudioManager.MODE_NORMAL

    fun begin(speaker: Boolean) {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    log("Audio focus lost ($change)")
                    onFocusLost()
                }
            }
            .build()
        val granted = am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focus = request
        log("Audio focus ${if (granted) "granted" else "not granted"}")
        previousMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeaker(speaker)
    }

    @Suppress("DEPRECATION")
    fun setSpeaker(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) {
            val wanted = if (on) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            val device = am.availableCommunicationDevices.firstOrNull { it.type == wanted }
            val ok = device != null && am.setCommunicationDevice(device)
            log("Audio route: ${if (on) "speaker" else "earpiece"}${if (ok) "" else " (not available)"}")
        } else {
            am.isSpeakerphoneOn = on
            log("Audio route: ${if (on) "speaker" else "earpiece"}")
        }
    }

    @Suppress("DEPRECATION")
    fun end() {
        if (Build.VERSION.SDK_INT >= 31) runCatching { am.clearCommunicationDevice() } else runCatching { am.isSpeakerphoneOn = false }
        runCatching { am.mode = previousMode }
        focus?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        focus = null
    }
}
