package com.tczerwien.lifts.ui

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.keepScreenOn
import com.tczerwien.lifts.session.RestAlarm
import com.tczerwien.lifts.session.RestStore
import com.tczerwien.lifts.session.keepAwake
import com.tczerwien.lifts.session.msToNextPulsePhase
import com.tczerwien.lifts.session.nextRestCheckMillis
import com.tczerwien.lifts.session.pulseInverted
import com.tczerwien.lifts.settings.SettingsStore
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioUsage
import com.thelightphone.sdk.ui.LightColors
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "LiftsFrame"

/** Used when the phone doesn't report its output rate. */
private const val FALLBACK_SAMPLE_RATE = 48_000

/** Extra time the voice is kept after the beep's last sample, so the end isn't cut off. */
private const val BEEP_TAIL_MS = 150L

/**
 * Where the beep plays: the process, not the screen that fired it, so a tap that leaves the screen
 * right after 0:00 doesn't cut it off. The voice is still released within half a second, in finally.
 */
private val beepScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * Every Lifts screen's outer wrapper, in place of LightTheme: the theme, plus the rest-over alert.
 * - While a rest runs, and for 30 s after it ends, the screen is kept on ([keepAwake]).
 * - When the rest is seen ending ([RestAlarm.check]), the screen invert-pulses, and the beep plays
 *   unless Settings turned it off.
 *
 * Only the screen on top is composed, so whichever screen is showing alerts, and a rest alerts once.
 * The beep's voice lives only as long as the sound and is released in `finally`, never in onCleared,
 * which the SDK skips when Android rebuilds the window.
 */
@Composable
fun LiftsFrame(
    sealedActivity: SealedLightActivity,
    lightContext: SealedLightContext,
    content: @Composable () -> Unit,
) {
    val themeColors by LightThemeController.colors.collectAsState()
    val restStore = remember(lightContext) { RestStore(lightContext.dataStore) }
    val restEnd by restStore.endMillis.collectAsState(initial = null)
    val settingsStore = remember(lightContext) { SettingsStore(lightContext.dataStore) }
    val beepOn by settingsStore.restBeep.collectAsState(initial = SettingsStore.DEFAULT_REST_BEEP)
    val latestBeepOn by rememberUpdatedState(beepOn)
    val alarm = RestAlarm.process

    var awake by remember { mutableStateOf(false) }
    LaunchedEffect(restEnd) {
        val end = restEnd
        if (end == null) {
            awake = false
            return@LaunchedEffect
        }
        while (true) {
            // A frame, not a timer: frames stop while the app is in the background, so a rest that
            // ends there stays quiet when the app comes back after the grace window.
            val now = withFrameMillis { System.currentTimeMillis() }
            awake = keepAwake(end, now)
            if (alarm.check(end, now) && latestBeepOn) {
                beepScope.launch { playRestBeep(DefaultLightAudio(sealedActivity)) }
            }
            delay(nextRestCheckMillis(end, now) ?: break)
        }
        awake = false
    }

    val pulseStartedAt by alarm.pulseStartedAt.collectAsState()
    var inverted by remember { mutableStateOf(false) }
    LaunchedEffect(pulseStartedAt) {
        val start = pulseStartedAt ?: return@LaunchedEffect
        try {
            while (true) {
                val now = System.currentTimeMillis()
                inverted = pulseInverted(start, now) ?: break
                delay(msToNextPulsePhase(start, now))
            }
        } finally {
            inverted = false
        }
    }

    LightTheme(colors = if (inverted) invertedColors(themeColors) else themeColors) {
        Box(modifier = Modifier.fillMaxSize().then(if (awake) Modifier.keepScreenOn() else Modifier)) {
            content()
        }
    }
}

/** Swap background and content for the full-screen alert pulse. */
private fun invertedColors(base: LightColors): LightColors = LightColors(
    background = base.content,
    content = base.background,
    contentSecondary = base.background,
)

/**
 * Plays [beepPcm] once on the alarm stream (alarm volume; music pauses for about half a second). The
 * voice lives only as long as the sound. A failure is logged, never thrown: a missing beep must not
 * take the workout down with it.
 */
private suspend fun playRestBeep(audio: LightAudio) = withContext(Dispatchers.Default) {
    try {
        val sampleRate = audio.capabilities.sampleRate.takeIf { it > 0 } ?: FALLBACK_SAMPLE_RATE
        val pcm = beepPcm(sampleRate)
        val voice = audio.newVoice(LightAudioUsage.Alarm, sampleRate)
        try {
            voice.play(pcm)
            delay(pcm.size * 1_000L / sampleRate + BEEP_TAIL_MS)
        } finally {
            voice.release()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: RuntimeException) {
        Log.w(TAG, "Rest-over beep failed", failure)
    }
}
