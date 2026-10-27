package com.anupshrestha.pairloom.debugtools

import com.anupshrestha.pairloom.media.AudioCodec
import com.anupshrestha.pairloom.media.CodecPair
import com.anupshrestha.pairloom.media.DiscardSink
import com.anupshrestha.pairloom.media.GroupHost
import com.anupshrestha.pairloom.media.GroupMemberLink
import com.anupshrestha.pairloom.media.InProcessLink
import com.anupshrestha.pairloom.media.MediaSession
import com.anupshrestha.pairloom.media.OpusSupport
import com.anupshrestha.pairloom.media.TestTone
import com.anupshrestha.pairloom.media.ToneSource
import java.io.Closeable
import java.net.InetAddress

/**
 * Debug builds only (Phase 7, G3): a simulated third phone on the hosting phone, for group-talk
 * tests with two real phones. It joins the hub like any member ([InProcessLink], its own keys,
 * its own UDP socket), so its audio goes through the same relay as a real phone's. Every
 * 8 s it talks for 2 s with a test tone (only while nobody else has the floor, like a
 * polite person), and it reports what it heard every 10 s.
 */
object VirtualPhone {
    const val AVAILABLE = true
    const val NAME = "Virtual phone"

    fun start(host: GroupHost, local: InetAddress, opus: Boolean, log: (String) -> Unit): Closeable? {
        val say: (String) -> Unit = { log("Virtual phone: $it") }
        val link = InProcessLink(host, NAME, local) { send ->
            GroupMemberLink(
                local, local, send,
                newSession = { codec, socket, hub, out, input ->
                    MediaSession(
                        socket, hub, out, input,
                        // A lower tone than the A1 diagnostic one, so it is easy to tell apart.
                        source = ToneSource(TestTone(frequencyHz = 330.0, amplitude = 0.2)),
                        sink = DiscardSink,
                        verify = null,
                        pushToTalk = true,
                        codecs = if (codec == AudioCodec.OPUS) OpusSupport.pair(say) else CodecPair.PCM,
                    )
                },
                localCodecs = { if (opus) AudioCodec.OPUS.bit else 0 },
                log = say,
                onEnded = { say("left the talk ($it)") },
            )
        }
        val member = link.member
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val talker = Thread({
            // An exception escaping a thread would crash the app.
            try {
                runCatching { member.join() }.onFailure { say("could not join: ${it.message}") }
                var t = 0L
                var spurts = 0
                var skipped = 0
                while (running.get()) {
                    try {
                        Thread.sleep(TICK_MS)
                    } catch (e: InterruptedException) {
                        break
                    }
                    t += TICK_MS
                    val s = member.session ?: continue
                    val phase = t % PERIOD_MS
                    if (phase == 0L) {
                        if (member.floorHolder == null) {
                            member.talking = true
                            spurts++
                            say("talks")
                        } else {
                            skipped++
                            say("waits: someone has the floor")
                        }
                    } else if (phase == TALK_MS && member.talking) {
                        member.talking = false
                        say("quiet")
                    }
                    if (t % REPORT_MS == 0L) {
                        val st = s.stats()
                        say("${member.codec}, talked $spurts times (waited $skipped), heard ${st.played} frames, lost ${st.lost}, concealed ${st.concealed}, rtt to the hub ${st.rttAvgMs?.let { "%.1f".format(it) } ?: "-"} ms")
                    }
                }
            } catch (e: Exception) {
                say("stopped: ${e.javaClass.simpleName}: ${e.message}")
            }
        }, "pl-virtual-phone").apply { isDaemon = true }
        talker.start()
        say("added as member ${link.memberId}")
        return Closeable {
            running.set(false)
            talker.interrupt()
            runCatching { talker.join(500) }
            link.close()
            say("removed")
        }
    }

    private const val TICK_MS = 100L
    private const val TALK_MS = 2_000L
    private const val PERIOD_MS = 8_000L
    private const val REPORT_MS = 10_000L
}
