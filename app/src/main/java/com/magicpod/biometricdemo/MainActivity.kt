package com.magicpod.biometricdemo

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.magicpod.biometricdemo.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var options: LaunchOptions

    private var outcome = AuthOutcome.IDLE
    private var detail = "-"
    private var terminalCount = 0
    private var nonMatchingScanCount = 0

    private var gateUnlocked = false

    private val preferences by lazy { getSharedPreferences("biometric-demo", Context.MODE_PRIVATE) }
    private var gateEnabledPreference: Boolean
        get() = preferences.getBoolean("gateEnabled", false)
        set(value) = preferences.edit().putBoolean("gateEnabled", value).apply()

    private val gateActive: Boolean
        get() = options.forcedGate ?: gateEnabledPreference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        options = LaunchOptions(intent)

        binding.resetButton.setOnClickListener { reset() }
        binding.authStrongButton.setOnClickListener { authenticate(AuthMode.STRONG) }
        binding.authStrongCryptoButton.setOnClickListener { authenticate(AuthMode.STRONG_CRYPTO) }
        binding.authWeakButton.setOnClickListener { authenticate(AuthMode.WEAK) }
        binding.authStrongOrCredentialButton.setOnClickListener {
            authenticate(AuthMode.STRONG_OR_CREDENTIAL)
        }

        binding.gateToggle.isChecked = gateEnabledPreference
        binding.gateToggle.setOnCheckedChangeListener { _, checked -> gateEnabledPreference = checked }
        binding.gateUnlockButton.setOnClickListener { authenticateForGate() }
        binding.gateDisableButton.setOnClickListener {
            gateEnabledPreference = false
            gateUnlocked = true
            renderGate()
        }

        applyLaunchOptions()
        render()
        renderGate()
        if (gateActive) authenticateForGate()
    }

    private fun applyLaunchOptions() {
        if (gateActive) return
        options.autoAuth?.let { authenticate(it) }
    }

    // ---------------------------------------------------------------- authentication

    private fun authenticate(mode: AuthMode) {
        outcome = AuthOutcome.RUNNING
        detail = "mode=$mode"
        render()

        val crypto = if (mode == AuthMode.STRONG_CRYPTO) {
            try {
                if (!KeystoreStore.hasKey()) KeystoreStore.createKey()
                BiometricPrompt.CryptoObject(KeystoreStore.encryptCipher())
            } catch (e: KeystoreStore.KeyInvalidatedException) {
                // Enrolling a biometric invalidates the key, so the next attempt would be stuck on
                // this forever. Report it, but drop the key so a retry starts from a usable one.
                KeystoreStore.deleteKey()
                finishWith(AuthOutcome.KEY_INVALIDATED, "mode=$mode ${e.cause}")
                return
            } catch (e: Exception) {
                finishWith(AuthOutcome.ERROR, "mode=$mode keystore failed: $e")
                return
            }
        } else {
            null
        }

        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), promptCallback(mode))
        val info = promptInfo(mode)
        if (crypto != null) prompt.authenticate(info, crypto) else prompt.authenticate(info)
    }

    private fun promptInfo(mode: AuthMode): BiometricPrompt.PromptInfo {
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Biometric Demo")
            .setSubtitle("mode=$mode")
            .setConfirmationRequired(options.confirmationRequired)

        when (mode) {
            AuthMode.WEAK -> builder
                .setAllowedAuthenticators(BIOMETRIC_WEAK)
                .setNegativeButtonText("Cancel")

            AuthMode.STRONG, AuthMode.STRONG_CRYPTO -> builder
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .setNegativeButtonText("Cancel")

            AuthMode.STRONG_OR_CREDENTIAL -> {
                // BIOMETRIC_STRONG or DEVICE_CREDENTIAL is rejected on API 28-29, so those levels
                // have to settle for the weak class. A negative button is not allowed (and not
                // needed) once DEVICE_CREDENTIAL is in the set.
                val biometricClass =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG else BIOMETRIC_WEAK
                builder.setAllowedAuthenticators(biometricClass or DEVICE_CREDENTIAL)
            }
        }
        return builder.build()
    }

    private fun promptCallback(mode: AuthMode) = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            val via = when (result.authenticationType) {
                BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC -> "BIOMETRIC"
                BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL -> "DEVICE_CREDENTIAL"
                else -> "UNKNOWN"
            }
            val cipher = result.cryptoObject?.cipher
            if (cipher == null) {
                finishWith(AuthOutcome.SUCCESS, "mode=$mode via=$via")
                return
            }
            // Run the crypto operation the authentication was supposed to unlock. If it throws,
            // the authentication only looked successful, so do not report SUCCESS.
            runCatching { cipher.doFinal(KeystoreStore.PLAINTEXT.toByteArray()).size }
                .fold(
                    { finishWith(AuthOutcome.SUCCESS, "mode=$mode via=$via cryptoBytes=$it") },
                    { finishWith(AuthOutcome.CRYPTO_FAILED, "mode=$mode via=$via cryptoFailed=$it") },
                )
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            finishWith(
                AuthOutcome.fromErrorCode(errorCode),
                "mode=$mode ${AuthOutcome.errorCodeName(errorCode)}($errorCode): $errString",
            )
        }

        override fun onAuthenticationFailed() {
            // A non-matching scan. The prompt stays up and the authentication is still running,
            // so this is deliberately not a terminal result -- same as iOS.
            nonMatchingScanCount++
            detail = "mode=$mode non-matching scan #$nonMatchingScanCount (prompt still open)"
            render()
        }
    }

    private fun authenticateForGate() {
        binding.gateStatus.text = AuthOutcome.RUNNING.code
        binding.gateDetail.text = "-"
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                gateUnlocked = true
                binding.gateStatus.text = AuthOutcome.SUCCESS.code
                renderGate()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                binding.gateStatus.text = AuthOutcome.fromErrorCode(errorCode).code
                binding.gateDetail.text =
                    "${AuthOutcome.errorCodeName(errorCode)}($errorCode): $errString"
            }
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Biometric Demo")
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setConfirmationRequired(options.confirmationRequired)
            .setNegativeButtonText("Cancel")
            .build()
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), callback).authenticate(info)
    }


    // ---------------------------------------------------------------- rendering

    private fun finishWith(newOutcome: AuthOutcome, newDetail: String) {
        terminalCount++
        setStatus(newOutcome, newDetail)
    }

    private fun setStatus(newOutcome: AuthOutcome, newDetail: String) {
        outcome = newOutcome
        detail = newDetail
        render()
    }

    private fun reset() {
        outcome = AuthOutcome.IDLE
        detail = "-"
        terminalCount = 0
        nonMatchingScanCount = 0
        render()
    }

    private fun render() {
        binding.resultStatus.text = outcome.code
        binding.resultDetail.text = detail
        binding.attemptCountValue.text = terminalCount.toString()
        binding.failedScanCountValue.text = nonMatchingScanCount.toString()
    }

    private fun renderGate() {
        binding.gateOverlay.visibility =
            if (gateActive && !gateUnlocked) View.VISIBLE else View.GONE
    }
}
