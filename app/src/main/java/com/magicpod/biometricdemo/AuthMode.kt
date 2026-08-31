package com.magicpod.biometricdemo

/** Which authenticator class an attempt asks for. */
enum class AuthMode {
    /** Class 3 only. */
    STRONG,

    /** Class 3 plus a Keystore-backed CryptoObject -- the banking-app shape. */
    STRONG_CRYPTO,

    /** Class 2. Cannot unlock Keystore keys. */
    WEAK,

    /** Biometrics with the device credential (PIN / pattern / password) as a fallback. */
    STRONG_OR_CREDENTIAL,
    ;

    companion object {
        fun fromName(name: String?): AuthMode? = values().firstOrNull { it.name == name }
    }
}
