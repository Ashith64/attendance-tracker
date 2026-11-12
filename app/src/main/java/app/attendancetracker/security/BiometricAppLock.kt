package app.attendancetracker.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Handles app-launch authentication via BiometricPrompt with fingerprint and
 * device PIN/pattern/password fallback.
 *
 * Also unlocks the Android Keystore 5-minute auth window for CredentialStore.
 */
object BiometricAppLock {

    private const val ALLOWED_AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

    fun canAuthenticate(activity: FragmentActivity): Int {
        val manager = BiometricManager.from(activity)
        return manager.canAuthenticate(ALLOWED_AUTHENTICATORS)
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String = "Unlock Attendance Tracker",
        subtitle: String = "Use fingerprint or device PIN to continue",
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
        onUnavailable: () -> Unit,
    ) {
        val status = canAuthenticate(activity)
        if (status != BiometricManager.BIOMETRIC_SUCCESS) {
            // Device does not have biometric or PIN lock enrolled; allow direct access.
            onSuccess()
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onError(errString.toString())
                }
            },
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
            .build()

        prompt.authenticate(promptInfo)
    }
}
