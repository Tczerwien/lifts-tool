package com.tczerwien.lifts.ui

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** The rest-over beep: two short 1760 Hz tones (A6), high enough to cut through gym noise. */
const val BEEP_FREQ_HZ = 1760.0
const val BEEP_TONE_MS = 150
const val BEEP_GAP_MS = 100
const val BEEP_COUNT = 2

/** Peak level, a little under full scale so the tone never clips. */
const val BEEP_AMPLITUDE = 0.8

private const val ENVELOPE_MS = 5

/**
 * The rest-over beep as mono 16-bit PCM at [sampleRate]: [BEEP_COUNT] tones of [BEEP_TONE_MS] with
 * [BEEP_GAP_MS] of silence between them. Each tone fades in and out over 5 ms so it doesn't click,
 * as in Light's audio-demo `synthesizeSine`. The builder packages no audio files, so the sound is
 * generated here.
 */
fun beepPcm(sampleRate: Int): ShortArray {
    require(BEEP_FREQ_HZ < sampleRate / 2.0) { "Frequency must be below Nyquist" }
    val tone = sineTone(sampleRate, BEEP_FREQ_HZ, BEEP_TONE_MS, BEEP_AMPLITUDE)
    val gap = frames(sampleRate, BEEP_GAP_MS)
    // The gaps are the array's zeros.
    val pcm = ShortArray(BEEP_COUNT * tone.size + (BEEP_COUNT - 1) * gap)
    repeat(BEEP_COUNT) { index -> tone.copyInto(pcm, index * (tone.size + gap)) }
    return pcm
}

private fun frames(sampleRate: Int, durationMs: Int): Int = (sampleRate.toLong() * durationMs / 1_000L).toInt()

private fun sineTone(sampleRate: Int, freqHz: Double, durationMs: Int, amplitude: Double): ShortArray {
    val frames = frames(sampleRate, durationMs)
    val envelopeFrames = min(frames / 2, sampleRate * ENVELOPE_MS / 1_000)
    return ShortArray(frames) { frame ->
        val attack = if (envelopeFrames == 0) 1.0 else min(1.0, frame.toDouble() / envelopeFrames)
        val release = if (envelopeFrames == 0) 1.0 else min(1.0, (frames - 1 - frame).toDouble() / envelopeFrames)
        val envelope = min(attack, release)
        (sin(2.0 * PI * freqHz * frame / sampleRate) * envelope * amplitude * Short.MAX_VALUE).toInt().toShort()
    }
}
