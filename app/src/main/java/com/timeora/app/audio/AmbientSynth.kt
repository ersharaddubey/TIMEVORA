package com.timevora.app.audio

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * A sound offered in Focus mode. The four built-in ones are generated on the phone (offline, nothing downloaded).
 * Sounds the admin uploads (MP3 on Cloudinary) have a [url]: they download once into the app cache and then also work offline.
 */
data class AmbientSound(val id: String, val label: String, val emoji: String, val url: String = "") {
    val isCloud: Boolean get() = url.isNotEmpty()

    companion object {
        val RAIN = AmbientSound("rain", "Rain", "🌧️")
        val CAFE = AmbientSound("cafe", "Café", "☕")
        val WHITE = AmbientSound("white", "White noise", "📻")
        val LOFI = AmbientSound("lofi", "Lo-fi", "🎧")

        /** The built-in sounds, in the order they are shown. */
        val entries: List<AmbientSound> = listOf(RAIN, CAFE, WHITE, LOFI)

        fun fromId(id: String?): AmbientSound? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Builds a short seamless loop (12 s, mono, 16-bit) for each sound. No audio files are bundled, which keeps the app small.
 * Noise based sounds are cross-faded at the loop point; the lo-fi beat is exactly 16 beats at 80 BPM so it wraps on the beat.
 */
object AmbientSynth {
    const val RATE = 22050
    const val SECONDS = 12
    private const val N = RATE * SECONDS
    private const val XFADE = RATE * 3 / 2

    private val cache = HashMap<AmbientSound, ShortArray>()

    @Synchronized
    fun get(sound: AmbientSound): ShortArray = cache.getOrPut(sound) { render(sound) }

    private fun render(sound: AmbientSound): ShortArray {
        val rnd = Random(1000L + AmbientSound.entries.indexOf(sound).coerceAtLeast(0))
        val raw = when (sound.id) {
            "rain" -> loopable(rnd) { rain(it, N + XFADE) }
            "cafe" -> loopable(rnd) { cafe(it, N + XFADE) }
            "lofi" -> lofi(rnd)
            else -> loopable(rnd) { white(it, N + XFADE) }
        }
        return finish(raw, if (sound.id == "lofi") 0.16f else 0.14f)
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private fun coef(fc: Float): Float = (1.0 - exp(-2.0 * PI * fc / RATE)).toFloat()

    /** Renders N + XFADE samples and folds the extra tail into the start so the loop has no seam. */
    private inline fun loopable(rnd: Random, gen: (Random) -> FloatArray): FloatArray {
        val buf = gen(rnd)
        val out = FloatArray(N)
        for (i in 0 until N) out[i] = buf[i]
        for (i in 0 until XFADE) {
            val p = (PI / 2.0 * i / XFADE)
            out[i] = buf[i] * sin(p).toFloat() + buf[N + i] * cos(p).toFloat()
        }
        return out
    }

    /** Scales to a steady loudness, soft-limits peaks and converts to 16-bit. */
    private fun finish(x: FloatArray, targetRms: Float): ShortArray {
        var sum = 0.0
        for (v in x) sum += v * v
        val rms = sqrt(sum / x.size).toFloat().coerceAtLeast(1e-6f)
        val g = targetRms / rms
        val out = ShortArray(x.size)
        for (i in x.indices) {
            val v = tanh((x[i] * g).toDouble()).toFloat()
            out[i] = (v * 30000f).toInt().coerceIn(-32767, 32767).toShort()
        }
        return out
    }

    private fun noise(rnd: Random): Float = rnd.nextFloat() * 2f - 1f

    // ---- white noise -------------------------------------------------------------------------------------------

    private fun white(rnd: Random, n: Int): FloatArray {
        val out = FloatArray(n)
        val a = coef(9000f)
        var y = 0f
        for (i in 0 until n) {
            y += a * (noise(rnd) - y)
            out[i] = y
        }
        return out
    }

    // ---- rain --------------------------------------------------------------------------------------------------

    private fun rain(rnd: Random, n: Int): FloatArray {
        val out = FloatArray(n)
        val aHi = coef(7000f)
        val aLo = coef(500f)
        val aDrop = coef(5000f)
        var hi = 0f
        var lo = 0f
        var dropEnv = 0f
        var dropLp = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / RATE
            val x = noise(rnd)
            hi += aHi * (x - hi)
            lo += aLo * (x - lo)
            val bed = (hi - lo) * (0.85f + 0.15f * sin(2.0 * PI * 0.17 * t).toFloat())
            // Individual drops hitting leaves and glass, roughly 60 per second.
            if (rnd.nextFloat() < 60f / RATE) dropEnv += 0.4f + rnd.nextFloat() * 0.9f
            dropEnv *= 0.985f
            dropLp += aDrop * (noise(rnd) - dropLp)
            out[i] = bed * 0.9f + dropLp * dropEnv * 1.6f
        }
        return out
    }

    // ---- café ------------------------------------------------------------------------------------------------

    private class Voice(val aHi: Float, val aLo: Float, val syl: Float, val phase: Float, var lo: Float = 0f, var hi: Float = 0f, var level: Float = 0f, var target: Float = 0f, var timer: Int = 0)

    private fun cafe(rnd: Random, n: Int): FloatArray {
        val out = FloatArray(n)
        val voices = List(6) {
            Voice(
                aHi = coef(1100f + rnd.nextFloat() * 900f),
                aLo = coef(180f + rnd.nextFloat() * 120f),
                syl = 2.4f + rnd.nextFloat() * 2.2f,
                phase = rnd.nextFloat() * 6.28f
            )
        }
        val aRoom = coef(260f)
        var room = 0f
        // Clinks: a decaying pair of high sine partials (cups, spoons). Clunks: lower and softer.
        var clinkEnv = 0f
        var clinkF = 3200f
        var clinkPhase = 0f
        var clunkEnv = 0f
        var clunkPhase = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / RATE
            room += aRoom * (noise(rnd) - room)
            var chatter = 0f
            for (v in voices) {
                if (v.timer <= 0) {
                    v.target = if (rnd.nextFloat() < 0.55f) 0.5f + rnd.nextFloat() * 0.5f else 0f
                    v.timer = (RATE * (0.8f + rnd.nextFloat() * 2.4f)).toInt()
                }
                v.timer--
                v.level += 0.0006f * (v.target - v.level)
                val x = noise(rnd)
                v.hi += v.aHi * (x - v.hi)
                v.lo += v.aLo * (x - v.lo)
                val syllable = max(0f, sin(2.0 * PI * v.syl * t + v.phase).toFloat())
                chatter += (v.hi - v.lo) * v.level * (0.25f + syllable * syllable)
            }
            if (rnd.nextFloat() < 0.45f / RATE) { clinkEnv = 0.25f + rnd.nextFloat() * 0.3f; clinkF = 2600f + rnd.nextFloat() * 2200f }
            if (rnd.nextFloat() < 0.12f / RATE) clunkEnv = 0.35f + rnd.nextFloat() * 0.25f
            clinkEnv *= 0.9992f
            clunkEnv *= 0.9987f
            clinkPhase += (2.0 * PI * clinkF / RATE).toFloat()
            clunkPhase += (2.0 * PI * 640.0 / RATE).toFloat()
            val clink = (sin(clinkPhase.toDouble()) + 0.5 * sin(clinkPhase * 2.31)).toFloat() * clinkEnv * 0.4f
            val clunk = sin(clunkPhase.toDouble()).toFloat() * clunkEnv * 0.5f
            out[i] = room * 1.4f + chatter * 1.1f + clink + clunk
        }
        return out
    }

    // ---- lo-fi ---------------------------------------------------------------------------------------------

    private val CHORDS = arrayOf(
        floatArrayOf(130.81f, 164.81f, 196.00f, 246.94f), // Cmaj7
        floatArrayOf(110.00f, 130.81f, 164.81f, 196.00f), // Am7
        floatArrayOf(146.83f, 174.61f, 220.00f, 261.63f), // Dm7
        floatArrayOf(98.00f, 123.47f, 146.83f, 174.61f)   // G7
    )
    private val BASS = floatArrayOf(65.41f, 55.00f, 73.42f, 49.00f)

    /** Adds [value] at position [idx], wrapping around so tails at the end of the loop continue at its start. */
    private fun add(buf: FloatArray, idx: Int, value: Float) { buf[((idx % N) + N) % N] += value }

    private fun lofi(rnd: Random): FloatArray {
        val buf = FloatArray(N)
        val beat = RATE * 60 / 80 // 80 BPM -> 16 beats = 12 s
        val eighth = beat / 2

        for (bar in 0 until 4) {
            val chord = CHORDS[bar]
            val barStart = bar * 4 * beat
            // Electric-piano chord stabs: beat 1 (long) and the "and" of beat 2 (shorter).
            for ((offset, len, amp) in listOf(Triple(0, (beat * 3.2f).toInt(), 0.20f), Triple(beat + eighth, (beat * 1.8f).toInt(), 0.14f))) {
                for ((k, f) in chord.withIndex()) {
                    val strum = k * (RATE / 90) // small strum between notes
                    for (i in 0 until len) {
                        val t = i.toFloat() / RATE
                        val env = min(1f, i / (RATE * 0.012f)) * exp(-t * 1.5f)
                        val s = sin(2.0 * PI * f * t) + 0.5 * sin(2.0 * PI * f * 1.003 * t) + 0.25 * exp(-t * 6f) * sin(2.0 * PI * f * 2 * t)
                        add(buf, barStart + offset + strum + i, (s * env * amp * 0.5).toFloat())
                    }
                }
            }
            // Bass on beat 1 and a short pickup on beat 3.5.
            for ((offset, len) in listOf(0 to beat * 2, (beat * 3.5f).toInt() to beat)) {
                for (i in 0 until len) {
                    val t = i.toFloat() / RATE
                    val env = min(1f, i / (RATE * 0.01f)) * exp(-t * 2.2f)
                    add(buf, barStart + offset + i, (sin(2.0 * PI * BASS[bar] * t) * env * 0.35).toFloat())
                }
            }
            // Drums: kick on 1 and 3.5, soft snare on 2 and 4, swung hats on every eighth.
            for (kickAt in listOf(0, (beat * 2.5f).toInt())) {
                var phase = 0.0
                for (i in 0 until (RATE * 0.22f).toInt()) {
                    val t = i.toFloat() / RATE
                    phase += 2.0 * PI * (45.0 + 70.0 * exp(-t * 28.0)) / RATE
                    add(buf, barStart + kickAt + i, (sin(phase) * exp(-t * 14.0) * 0.55).toFloat())
                }
            }
            for (snareBeat in listOf(1, 3)) {
                var lp = 0f
                val a = coef(3500f)
                for (i in 0 until (RATE * 0.16f).toInt()) {
                    val t = i.toFloat() / RATE
                    val x = noise(rnd)
                    lp += a * (x - lp)
                    add(buf, barStart + snareBeat * beat + i, ((x - lp) * exp(-t * 22f) * 0.22f))
                }
            }
            for (h in 0 until 8) {
                val swing = if (h % 2 == 1) eighth / 4 else 0
                var lp = 0f
                val a = coef(6500f)
                for (i in 0 until (RATE * 0.05f).toInt()) {
                    val t = i.toFloat() / RATE
                    val x = noise(rnd)
                    lp += a * (x - lp)
                    add(buf, barStart + h * eighth + swing + i, ((x - lp) * exp(-t * 90f) * (if (h % 2 == 0) 0.10f else 0.06f)))
                }
            }
        }

        // Vinyl crackle and a faint tape hiss.
        var hiss = 0f
        val aHiss = coef(2500f)
        var pop = 0f
        for (i in 0 until N) {
            hiss += aHiss * (noise(rnd) - hiss)
            if (rnd.nextFloat() < 14f / RATE) pop += (rnd.nextFloat() - 0.5f) * 0.5f
            pop *= 0.9f
            buf[i] += hiss * 0.05f + pop * 0.4f
        }
        // Warm it up with a gentle low-pass, then run it once more around the loop so the filter state is continuous.
        val aWarm = coef(4200f)
        var y = 0f
        for (pass in 0 until 2) for (i in 0 until N) {
            y += aWarm * (buf[i] - y)
            if (pass == 1) buf[i] = y
        }
        return buf
    }

    @Suppress("unused")
    private fun peak(x: FloatArray): Float { var p = 0f; for (v in x) p = max(p, abs(v)); return p }
}
