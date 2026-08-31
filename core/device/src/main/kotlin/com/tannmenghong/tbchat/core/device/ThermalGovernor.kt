package com.tannmenghong.tbchat.core.device

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.tannmenghong.tbchat.domain.model.PerformanceMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-minute inference will heat a phone into throttling. Rather than letting
 * the SoC degrade unpredictably mid-answer, the app manages the budget itself
 * and -- crucially -- tells the user what it is doing. A visible "cooling down"
 * state is far better than an unexplained slowdown.
 */
@Singleton
class ThermalGovernor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    enum class Policy { FULL, REDUCED, PAUSED }

    private val powerManager by lazy {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    private val _policy = MutableStateFlow(Policy.FULL)
    val policy: StateFlow<Policy> = _policy.asStateFlow()

    private val _thermalStatus = MutableStateFlow(PowerManager.THERMAL_STATUS_NONE)
    val thermalStatus: StateFlow<Int> = _thermalStatus.asStateFlow()

    private val listener = PowerManager.OnThermalStatusChangedListener { status ->
        _thermalStatus.value = status
        _policy.value = policyFor(status)
    }

    private var started = false

    @Synchronized
    fun start() {
        if (started || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        powerManager.addThermalStatusListener(listener)
        val current = powerManager.currentThermalStatus
        _thermalStatus.value = current
        _policy.value = policyFor(current)
        started = true
    }

    @Synchronized
    fun stop() {
        if (!started) return
        runCatching { powerManager.removeThermalStatusListener(listener) }
        started = false
    }

    private fun policyFor(status: Int): Policy = when {
        status >= PowerManager.THERMAL_STATUS_SEVERE -> Policy.PAUSED
        status >= PowerManager.THERMAL_STATUS_MODERATE -> Policy.REDUCED
        else -> Policy.FULL
    }

    /**
     * How many threads to use right now, given the thermal policy, the user's
     * performance mode, and whether the system battery saver is on.
     */
    fun effectiveThreads(maxThreads: Int, mode: PerformanceMode): Int {
        val byMode = when (mode) {
            PerformanceMode.PERFORMANCE -> maxThreads
            PerformanceMode.BALANCED -> maxThreads
            PerformanceMode.BATTERY_SAVER -> (maxThreads / 2).coerceAtLeast(1)
        }
        val byThermal = when (_policy.value) {
            Policy.FULL -> byMode
            // Performance mode opts out of thermal backoff but not of the pause:
            // the pause is a safety limit, not a preference.
            Policy.REDUCED -> if (mode == PerformanceMode.PERFORMANCE) byMode
            else (byMode / 2).coerceAtLeast(1)

            Policy.PAUSED -> 0
        }
        return if (powerManager.isPowerSaveMode) (byThermal / 2).coerceAtLeast(1) else byThermal
    }

    /**
     * Called at the safe yield points -- between tokens, and between diffusion
     * steps. Suspends rather than busy-waiting, so a cooling phone costs
     * nothing while it cools.
     */
    suspend fun awaitCoolIfNeeded() {
        if (_policy.value == Policy.PAUSED) {
            policy.first { it != Policy.PAUSED }
        }
    }

    fun isTooHotToStart(): Boolean = _policy.value == Policy.PAUSED

    fun statusLabel(): String = when (_thermalStatus.value) {
        PowerManager.THERMAL_STATUS_NONE -> "Cool"
        PowerManager.THERMAL_STATUS_LIGHT -> "Warm"
        PowerManager.THERMAL_STATUS_MODERATE -> "Hot, running slower"
        PowerManager.THERMAL_STATUS_SEVERE -> "Too hot, paused"
        PowerManager.THERMAL_STATUS_CRITICAL,
        PowerManager.THERMAL_STATUS_EMERGENCY,
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "Overheating"

        else -> "Unknown"
    }
}
