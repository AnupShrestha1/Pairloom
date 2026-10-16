package com.anupshrestha.pairloom.media

/**
 * Where outgoing 10 ms frames come from. A [paced] source blocks in [read] until the next frame
 * exists (a microphone), so its clock drives sending; an unpaced one is read on a timer.
 */
interface FrameSource {
    val paced: Boolean
    val frameBytes: Int

    /** The frame for position [index]; may block (paced sources). */
    fun read(index: Long): ByteArray

    fun close() {}
}

/**
 * Where played-out frames go, one per frame period (silence while buffering). A [paced] sink blocks
 * in [write] at the device's rate (a speaker), so its clock drives playout; an unpaced one is
 * written on a timer.
 */
interface FrameSink {
    val paced: Boolean

    fun write(frame: ByteArray)

    fun close() {}
}

/** The deterministic test tone as a source (milestone A1). */
class ToneSource(private val tone: TestTone = TestTone()) : FrameSource {
    override val paced = false
    override val frameBytes: Int get() = tone.frameBytes
    override fun read(index: Long): ByteArray = tone.frame(index)
}

/** Drops what it is given (tests, and tone runs without audio output). */
object DiscardSink : FrameSink {
    override val paced = false
    override fun write(frame: ByteArray) = Unit
}
