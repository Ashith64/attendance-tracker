package app.attendancetracker.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SessionState {
    FIRST_LOGIN,
    SESSION_VALID,
    SESSION_EXPIRED,
    CHALLENGE_REQUIRED,
}

enum class LoginErrorKind {
    NONE,
    WRONG_DETAILS,
    NETWORK_ERROR,
    CHALLENGE_REQUIRED,
    AUTH_REQUIRED,
}

data class AuthSessionSnapshot(
    val state: SessionState = SessionState.FIRST_LOGIN,
    val isBusy: Boolean = false,
    val errorKind: LoginErrorKind = LoginErrorKind.NONE,
    val errorMessage: String? = null,
    val challengeReason: String? = null,
)

sealed class SessionCheckResult {
    data object Valid : SessionCheckResult()
    data object Expired : SessionCheckResult()
    data class ChallengeRequired(val reason: String) : SessionCheckResult()
    data class NetworkError(val reason: String) : SessionCheckResult()
}

sealed class PortalLoginResult {
    data object Success : PortalLoginResult()
    data class WrongDetails(val message: String) : PortalLoginResult()
    data class NetworkError(val message: String) : PortalLoginResult()
    data class ChallengeRequired(val reason: String) : PortalLoginResult()
}

/**
 * Pure Kotlin state machine managing the Architecture B authentication lifecycle:
 * FIRST_LOGIN -> SESSION_VALID <-> SESSION_EXPIRED -> CHALLENGE_REQUIRED.
 *
 * Never logs credentials, cookies, or tokens.
 */
class AuthSessionStateMachine(
    private val credentialStore: CredentialStore,
    private val hasCookies: () -> Boolean,
    private val clearCookies: () -> Unit,
    private val checkSessionValid: suspend () -> SessionCheckResult,
    private val performPortalLogin: suspend (StudentCredentials) -> PortalLoginResult,
) {
    private val _snapshot = MutableStateFlow(AuthSessionSnapshot())
    val snapshot: StateFlow<AuthSessionSnapshot> = _snapshot.asStateFlow()

    /**
     * Restores state from local credential storage on app launch.
     * If credentials already exist, immediately marks the session as SESSION_VALID so
     * the cached dashboard is displayed without flashing the login screen.
     */
    suspend fun restoreSessionState(): SessionState {
        val hasCreds = credentialStore.hasCredentials()
        val next = if (hasCreds) SessionState.SESSION_VALID else SessionState.FIRST_LOGIN
        _snapshot.value = AuthSessionSnapshot(state = next)
        return next
    }

    /**
     * Evaluates whether the user has stored credentials and a valid portal session.
     * Uses the session validity signal from PORTAL_RECON.md when cookies exist.
     */
    suspend fun evaluateSession(): SessionState {
        _snapshot.value = _snapshot.value.copy(isBusy = true, errorKind = LoginErrorKind.NONE, errorMessage = null)

        if (!credentialStore.hasCredentials()) {
            _snapshot.value = AuthSessionSnapshot(state = SessionState.FIRST_LOGIN)
            return SessionState.FIRST_LOGIN
        }

        if (!hasCookies()) {
            _snapshot.value = AuthSessionSnapshot(state = SessionState.SESSION_EXPIRED)
            return SessionState.SESSION_EXPIRED
        }

        return when (val check = checkSessionValid()) {
            SessionCheckResult.Valid -> {
                _snapshot.value = AuthSessionSnapshot(state = SessionState.SESSION_VALID)
                SessionState.SESSION_VALID
            }
            SessionCheckResult.Expired -> {
                _snapshot.value = AuthSessionSnapshot(state = SessionState.SESSION_EXPIRED)
                SessionState.SESSION_EXPIRED
            }
            is SessionCheckResult.ChallengeRequired -> {
                _snapshot.value = AuthSessionSnapshot(
                    state = SessionState.CHALLENGE_REQUIRED,
                    errorKind = LoginErrorKind.CHALLENGE_REQUIRED,
                    errorMessage = check.reason,
                    challengeReason = check.reason,
                )
                SessionState.CHALLENGE_REQUIRED
            }
            is SessionCheckResult.NetworkError -> {
                _snapshot.value = AuthSessionSnapshot(
                    state = SessionState.SESSION_EXPIRED,
                    errorKind = LoginErrorKind.NETWORK_ERROR,
                    errorMessage = check.reason,
                )
                SessionState.SESSION_EXPIRED
            }
        }
    }

    /**
     * Checks session validity and, if expired with no challenge, automatically logs in
     * using encrypted credentials. Stops immediately if a CAPTCHA/OTP challenge appears.
     */
    suspend fun ensureSessionForSync(): SessionState {
        val current = evaluateSession()
        if (current != SessionState.SESSION_EXPIRED) {
            return current
        }
        if (_snapshot.value.errorKind == LoginErrorKind.NETWORK_ERROR) {
            return SessionState.SESSION_EXPIRED
        }

        _snapshot.value = _snapshot.value.copy(isBusy = true, errorKind = LoginErrorKind.NONE, errorMessage = null)

        return when (val readResult = credentialStore.read()) {
            is CredentialReadResult.Success -> {
                when (val loginResult = performPortalLogin(readResult.credentials)) {
                    PortalLoginResult.Success -> {
                        _snapshot.value = AuthSessionSnapshot(state = SessionState.SESSION_VALID)
                        SessionState.SESSION_VALID
                    }
                    is PortalLoginResult.ChallengeRequired -> {
                        _snapshot.value = AuthSessionSnapshot(
                            state = SessionState.CHALLENGE_REQUIRED,
                            errorKind = LoginErrorKind.CHALLENGE_REQUIRED,
                            errorMessage = loginResult.reason,
                            challengeReason = loginResult.reason,
                        )
                        SessionState.CHALLENGE_REQUIRED
                    }
                    is PortalLoginResult.WrongDetails -> {
                        _snapshot.value = AuthSessionSnapshot(
                            state = SessionState.FIRST_LOGIN,
                            errorKind = LoginErrorKind.WRONG_DETAILS,
                            errorMessage = loginResult.message,
                        )
                        SessionState.FIRST_LOGIN
                    }
                    is PortalLoginResult.NetworkError -> {
                        _snapshot.value = AuthSessionSnapshot(
                            state = SessionState.SESSION_EXPIRED,
                            errorKind = LoginErrorKind.NETWORK_ERROR,
                            errorMessage = loginResult.message,
                        )
                        SessionState.SESSION_EXPIRED
                    }
                }
            }
            CredentialReadResult.NotFound -> {
                _snapshot.value = AuthSessionSnapshot(state = SessionState.FIRST_LOGIN)
                SessionState.FIRST_LOGIN
            }
            CredentialReadResult.KeyInvalidated -> {
                _snapshot.value = AuthSessionSnapshot(
                    state = SessionState.FIRST_LOGIN,
                    errorKind = LoginErrorKind.WRONG_DETAILS,
                    errorMessage = "Device biometrics changed. Please re-enter your portal login details.",
                )
                SessionState.FIRST_LOGIN
            }
            CredentialReadResult.AuthRequired -> {
                _snapshot.value = AuthSessionSnapshot(
                    state = SessionState.SESSION_EXPIRED,
                    errorKind = LoginErrorKind.AUTH_REQUIRED,
                    errorMessage = "Authentication required to unlock saved credentials.",
                )
                SessionState.SESSION_EXPIRED
            }
            is CredentialReadResult.Error -> {
                _snapshot.value = AuthSessionSnapshot(
                    state = SessionState.SESSION_EXPIRED,
                    errorKind = LoginErrorKind.AUTH_REQUIRED,
                    errorMessage = readResult.reason,
                )
                SessionState.SESSION_EXPIRED
            }
        }
    }

    /**
     * Handles interactive first-run login submission.
     * Saves encrypted credentials only when portal login succeeds.
     */
    suspend fun submitFirstRunLogin(credentials: StudentCredentials): SessionState {
        _snapshot.value = AuthSessionSnapshot(state = SessionState.FIRST_LOGIN, isBusy = true)

        return when (val result = performPortalLogin(credentials)) {
            PortalLoginResult.Success -> {
                val saved = credentialStore.save(credentials)
                if (!saved) {
                    _snapshot.value = AuthSessionSnapshot(
                        state = SessionState.FIRST_LOGIN,
                        errorKind = LoginErrorKind.AUTH_REQUIRED,
                        errorMessage = "Could not encrypt credentials. Please unlock with biometrics or device PIN and try again.",
                    )
                    SessionState.FIRST_LOGIN
                } else {
                    _snapshot.value = AuthSessionSnapshot(state = SessionState.SESSION_VALID)
                    SessionState.SESSION_VALID
                }
            }
            is PortalLoginResult.ChallengeRequired -> {
                _snapshot.value = AuthSessionSnapshot(
                    state = SessionState.CHALLENGE_REQUIRED,
                    errorKind = LoginErrorKind.CHALLENGE_REQUIRED,
                    errorMessage = result.reason,
                    challengeReason = result.reason,
                )
                SessionState.CHALLENGE_REQUIRED
            }
            is PortalLoginResult.WrongDetails -> {
                _snapshot.value = AuthSessionSnapshot(
                    state = SessionState.FIRST_LOGIN,
                    errorKind = LoginErrorKind.WRONG_DETAILS,
                    errorMessage = result.message,
                )
                SessionState.FIRST_LOGIN
            }
            is PortalLoginResult.NetworkError -> {
                _snapshot.value = AuthSessionSnapshot(
                    state = SessionState.FIRST_LOGIN,
                    errorKind = LoginErrorKind.NETWORK_ERROR,
                    errorMessage = result.message,
                )
                SessionState.FIRST_LOGIN
            }
        }
    }

    /**
     * Called when the student manually finishes a CAPTCHA/OTP/challenge in the visible WebView.
     */
    suspend fun onChallengeCompleted(credentialsToSave: StudentCredentials? = null): SessionState {
        if (credentialsToSave != null) {
            credentialStore.save(credentialsToSave)
        }
        _snapshot.value = AuthSessionSnapshot(state = SessionState.SESSION_VALID)
        return SessionState.SESSION_VALID
    }

    /**
     * Called when a data sync detects the portal returned the login page mid-sync.
     */
    suspend fun onSessionExpiredDuringSync(): SessionState {
        val next = if (credentialStore.hasCredentials()) {
            SessionState.SESSION_EXPIRED
        } else {
            SessionState.FIRST_LOGIN
        }
        _snapshot.value = AuthSessionSnapshot(state = next)
        return next
    }

    /**
     * Clears persisted cookies and encrypted credentials, returning to FIRST_LOGIN.
     */
    suspend fun logout(): SessionState {
        clearCookies()
        credentialStore.clear()
        _snapshot.value = AuthSessionSnapshot(state = SessionState.FIRST_LOGIN)
        return SessionState.FIRST_LOGIN
    }
}
