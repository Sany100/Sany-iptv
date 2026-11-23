package com.raghav.donation

/**
 * SANY host policy: third-party plugin donation prompts are disabled.
 *
 * Keep this compatibility object so plugins that still reference the upstream
 * DonationManager can load without crashing, but never fetch donation metadata,
 * display dialogs, or show promotional pop-ups.
 */
object DonationManager {
    data class Goal(
        val raised: Double,
        val target: Double,
        val currency: String,
        val donor: String,
        val donation: String
    ) {
        val achieved: Boolean get() = target > 0.0 && raised >= target
        val percent: Int
            get() = if (target > 0.0) (raised / target * 100.0).toInt().coerceIn(0, 100) else 0
    }

    @JvmStatic
    fun checkAndShow() {
        // Intentionally disabled by the SANY host.
    }

    @JvmStatic
    fun onDialogDismissed() {
        // Compatibility no-op.
    }
}
