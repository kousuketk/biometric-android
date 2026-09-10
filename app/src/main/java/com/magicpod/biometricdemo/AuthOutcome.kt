package com.magicpod.biometricdemo

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt

/**
 * Machine-readable outcome of one authentication attempt.
 *
 * These strings are what an automated test asserts on, so they are intentionally uppercase ASCII
 * and are never localized. They match the iOS demo app so a cross-platform test can share them.
 */
enum class AuthOutcome(val code: String) {
    IDLE("IDLE"),
    RUNNING("RUNNING"),
    SUCCESS("SUCCESS"),
    CANCELED("CANCELED"),

    /** The user pressed the prompt's negative button ("Cancel" / "Use password"). */
    NEGATIVE_BUTTON("NEGATIVE_BUTTON"),
    NOT_ENROLLED("NOT_ENROLLED"),
    NOT_AVAILABLE("NOT_AVAILABLE"),
    LOCKED_OUT("LOCKED_OUT"),
    LOCKED_OUT_PERMANENT("LOCKED_OUT_PERMANENT"),
    PASSCODE_NOT_SET("PASSCODE_NOT_SET"),

    /** The Keystore key was invalidated because the enrolled biometrics changed. */
    KEY_INVALIDATED("KEY_INVALIDATED"),

    /**
     * BiometricPrompt reported success, but the Keystore-backed cipher then refused to run. That
     * means no valid Hardware Auth Token reached Keystore -- the case a fake HAL, or an enrollment
     * written with a stale gatekeeper SID, produces. Reporting it as SUCCESS would hide exactly
     * what this app exists to detect.
     */
    CRYPTO_FAILED("CRYPTO_FAILED"),
    ERROR("ERROR");

    companion object {
        /**
         * Note that there is no FAILED here on purpose. A non-matching scan arrives through
         * `onAuthenticationFailed()`, which does *not* end the authentication: the prompt stays up
         * and the app is only told the outcome once one of these terminal errors arrives. iOS
         * behaves the same way.
         */
        fun fromErrorCode(errorCode: Int): AuthOutcome = when (errorCode) {
            BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED -> CANCELED
            BiometricPrompt.ERROR_NEGATIVE_BUTTON -> NEGATIVE_BUTTON
            BiometricPrompt.ERROR_NO_BIOMETRICS -> NOT_ENROLLED
            BiometricPrompt.ERROR_HW_NOT_PRESENT, BiometricPrompt.ERROR_HW_UNAVAILABLE -> NOT_AVAILABLE
            BiometricPrompt.ERROR_LOCKOUT -> LOCKED_OUT
            BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> LOCKED_OUT_PERMANENT
            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> PASSCODE_NOT_SET
            else -> ERROR
        }

        fun errorCodeName(errorCode: Int): String = when (errorCode) {
            BiometricPrompt.ERROR_HW_UNAVAILABLE -> "ERROR_HW_UNAVAILABLE"
            BiometricPrompt.ERROR_UNABLE_TO_PROCESS -> "ERROR_UNABLE_TO_PROCESS"
            BiometricPrompt.ERROR_TIMEOUT -> "ERROR_TIMEOUT"
            BiometricPrompt.ERROR_NO_SPACE -> "ERROR_NO_SPACE"
            BiometricPrompt.ERROR_CANCELED -> "ERROR_CANCELED"
            BiometricPrompt.ERROR_LOCKOUT -> "ERROR_LOCKOUT"
            BiometricPrompt.ERROR_VENDOR -> "ERROR_VENDOR"
            BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> "ERROR_LOCKOUT_PERMANENT"
            BiometricPrompt.ERROR_USER_CANCELED -> "ERROR_USER_CANCELED"
            BiometricPrompt.ERROR_NO_BIOMETRICS -> "ERROR_NO_BIOMETRICS"
            BiometricPrompt.ERROR_HW_NOT_PRESENT -> "ERROR_HW_NOT_PRESENT"
            BiometricPrompt.ERROR_NEGATIVE_BUTTON -> "ERROR_NEGATIVE_BUTTON"
            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> "ERROR_NO_DEVICE_CREDENTIAL"
            BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED -> "ERROR_SECURITY_UPDATE_REQUIRED"
            else -> "UNKNOWN"
        }

        /** Renders the result of [BiometricManager.canAuthenticate] for the device-state panel. */
        fun canAuthenticateName(status: Int): String = when (status) {
            BiometricManager.BIOMETRIC_SUCCESS -> "OK"
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "NG BIOMETRIC_ERROR_NO_HARDWARE"
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "NG BIOMETRIC_ERROR_HW_UNAVAILABLE"
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "NG BIOMETRIC_ERROR_NONE_ENROLLED"
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                "NG BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED"
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> "NG BIOMETRIC_ERROR_UNSUPPORTED"
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> "NG BIOMETRIC_STATUS_UNKNOWN"
            else -> "NG ($status)"
        }
    }
}
