package me.phie.tawc

import kotlin.math.roundToInt

/** Display refresh-rate selection, in mHz (the unit of `wl_output.mode`). */
internal object RefreshRate {
    /** Rates outside this window are display bugs, not modes to use. */
    private const val MIN_PLAUSIBLE_MHZ = 10_000
    private const val MAX_PLAUSIBLE_MHZ = 240_000

    /** [hz] in mHz, or null if implausible. */
    fun usableMhz(hz: Float): Int? =
        (hz * 1000f).roundToInt().takeIf { it in MIN_PLAUSIBLE_MHZ..MAX_PLAUSIBLE_MHZ }

    /** Fastest plausible rate among [ratesHz], or null if none is. */
    fun maxUsableMhz(ratesHz: List<Float>): Int? = ratesHz.mapNotNull(::usableMhz).maxOrNull()
}
