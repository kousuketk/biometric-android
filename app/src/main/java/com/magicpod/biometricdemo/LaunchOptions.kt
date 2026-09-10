package com.magicpod.biometricdemo

import android.content.Intent

/**
 * Intent-extra overrides so a test can put the app into a known state without tapping anything.
 *
 *   adb shell am start -n com.magicpod.biometricdemo/.MainActivity \
 *     --es auto_auth STRONG_CRYPTO --ez gate false
 *
 *   auto_auth  STRONG | STRONG_CRYPTO | WEAK | STRONG_OR_CREDENTIAL
 *   gate       true / false -- force the launch gate for this run
 *   confirmation false    -- drop the extra "Confirm" tap that passive modalities require
 */
class LaunchOptions(private val intent: Intent) {
    val autoAuth: AuthMode?
        get() = intent.getStringExtra("auto_auth")?.let { name ->
            AuthMode.entries.firstOrNull { it.name == name }
        }

    /** null means "leave whatever the user toggled in the UI". */
    val forcedGate: Boolean?
        get() = if (intent.hasExtra("gate")) intent.getBooleanExtra("gate", false) else null



    /**
     * Face is a passive modality, so BiometricPrompt asks the user to tap "Confirm" by default.
     * Fingerprint does not. Tests can turn it off to keep the two paths comparable.
     */
    val confirmationRequired: Boolean get() = intent.getBooleanExtra("confirmation", true)
}
