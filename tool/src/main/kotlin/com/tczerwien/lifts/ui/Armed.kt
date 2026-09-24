package com.tczerwien.lifts.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Two-tap confirmation for actions that can't be undone. The first tap arms. A second tap confirms
 * only if it comes at least [minConfirmMillis] and at most [timeoutMillis] after the first. Sooner
 * than that it is a double-tap or a bounce and is ignored, so one nervous double-tap can't finish
 * or delete anything. Later than that the guard has lapsed, and the tap arms it again.
 */
class ArmGuard(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    val timeoutMillis: Long = 3_000,
    val minConfirmMillis: Long = 400,
) {
    enum class Tap { ARMED, IGNORED, CONFIRMED }

    private var armedAt: Long? = null

    @Synchronized
    fun tap(): Tap {
        val now = clock()
        val since = armedAt?.let { now - it }
        return when {
            since == null || since < 0 || since > timeoutMillis -> {
                armedAt = now
                Tap.ARMED
            }
            since < minConfirmMillis -> Tap.IGNORED
            else -> {
                armedAt = null
                Tap.CONFIRMED
            }
        }
    }

    @Synchronized
    fun isArmed(): Boolean {
        val since = clock() - (armedAt ?: return false)
        return since in 0..timeoutMillis
    }

    @Synchronized
    fun disarm() {
        armedAt = null
    }
}

/**
 * An [ArmGuard] whose armed state is a flow the screen can show: [armed] turns on at the first tap
 * and turns itself off when the confirm window closes. Timers run on [scope] (a viewModelScope).
 */
class ArmedAction(
    private val scope: CoroutineScope,
    private val guard: ArmGuard = ArmGuard(),
) {
    private val _armed = MutableStateFlow(false)
    val armed: StateFlow<Boolean> = _armed.asStateFlow()

    private var expiry: Job? = null

    /** Returns true when this tap confirms; the caller then runs the action. */
    fun tap(): Boolean = when (guard.tap()) {
        ArmGuard.Tap.ARMED -> {
            _armed.value = true
            expiry?.cancel()
            expiry = scope.launch {
                delay(guard.timeoutMillis)
                guard.disarm()
                _armed.value = false
            }
            false
        }
        ArmGuard.Tap.IGNORED -> false
        ArmGuard.Tap.CONFIRMED -> {
            disarm()
            true
        }
    }

    fun disarm() {
        expiry?.cancel()
        guard.disarm()
        _armed.value = false
    }
}

/** Superfine confirm hint shown above the bottom bar while a two-tap action is armed. */
@Composable
fun ArmedHint(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Superfine,
        lighten = true,
        align = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 0.5f.gridUnitsAsDp()),
    )
}
