package app.attendancetracker.security

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthSessionStateMachineTest {

    private class FakeCredentialStore(
        var stored: StudentCredentials? = null,
        var readOverride: CredentialReadResult? = null,
        var saveShouldSucceed: Boolean = true,
    ) : CredentialStore {
        var clearCount = 0

        override suspend fun hasCredentials(): Boolean = stored != null

        override suspend fun save(credentials: StudentCredentials): Boolean {
            if (!saveShouldSucceed) return false
            stored = credentials
            return true
        }

        override suspend fun read(): CredentialReadResult {
            readOverride?.let { return it }
            return stored?.let { CredentialReadResult.Success(it) } ?: CredentialReadResult.NotFound
        }

        override suspend fun clear() {
            clearCount++
            stored = null
        }
    }

    private fun sampleCredentials(): StudentCredentials = StudentCredentials(
        usn = "4NI23IS000",
        dobDay = "01",
        dobMonth = "01",
        dobYear = "2005",
        relation = StudentCredentials.RELATION_FATHER,
        digits = "1234",
    )

    @Test
    fun `evaluateSession returns FIRST_LOGIN when no credentials are saved`() = runTest {
        val credsStore = FakeCredentialStore(stored = null)
        var checkCalled = false
        val sm = AuthSessionStateMachine(
            credentialStore = credsStore,
            hasCookies = { true },
            clearCookies = {},
            checkSessionValid = {
                checkCalled = true
                SessionCheckResult.Valid
            },
            performPortalLogin = { PortalLoginResult.Success },
        )

        val state = sm.evaluateSession()

        assertEquals(SessionState.FIRST_LOGIN, state)
        assertFalse(checkCalled)
        assertEquals(LoginErrorKind.NONE, sm.snapshot.value.errorKind)
    }

    @Test
    fun `evaluateSession returns SESSION_VALID and ensureSessionForSync reuses session`() = runTest {
        val credsStore = FakeCredentialStore(stored = sampleCredentials())
        var loginCalled = false
        val sm = AuthSessionStateMachine(
            credentialStore = credsStore,
            hasCookies = { true },
            clearCookies = {},
            checkSessionValid = { SessionCheckResult.Valid },
            performPortalLogin = {
                loginCalled = true
                PortalLoginResult.Success
            },
        )

        val state = sm.ensureSessionForSync()

        assertEquals(SessionState.SESSION_VALID, state)
        assertFalse(loginCalled)
    }

    @Test
    fun `evaluateSession returns SESSION_EXPIRED when cookies are missing`() = runTest {
        val credsStore = FakeCredentialStore(stored = sampleCredentials())
        var checkCalled = false
        val sm = AuthSessionStateMachine(
            credentialStore = credsStore,
            hasCookies = { false },
            clearCookies = {},
            checkSessionValid = {
                checkCalled = true
                SessionCheckResult.Valid
            },
            performPortalLogin = { PortalLoginResult.Success },
        )

        val state = sm.evaluateSession()

        assertEquals(SessionState.SESSION_EXPIRED, state)
        assertFalse(checkCalled)
    }

    @Test
    fun `ensureSessionForSync auto-logs in when session expired and no challenge`() = runTest {
        val creds = sampleCredentials()
        val credsStore = FakeCredentialStore(stored = creds)
        var loginCalls = 0
        val sm = AuthSessionStateMachine(
            credentialStore = credsStore,
            hasCookies = { true },
            clearCookies = {},
            checkSessionValid = { SessionCheckResult.Expired },
            performPortalLogin = {
                loginCalls++
                PortalLoginResult.Success
            },
        )

        val state = sm.ensureSessionForSync()

        assertEquals(SessionState.SESSION_VALID, state)
        assertEquals(1, loginCalls)
    }

    @Test
    fun `ensureSessionForSync stops on CHALLENGE_REQUIRED during session check`() = runTest {
        val credsStore = FakeCredentialStore(stored = sampleCredentials())
        var loginCalled = false
        val sm = AuthSessionStateMachine(
            credentialStore = credsStore,
            hasCookies = { true },
            clearCookies = {},
            checkSessionValid = { SessionCheckResult.ChallengeRequired("CAPTCHA shown") },
            performPortalLogin = {
                loginCalled = true
                PortalLoginResult.Success
            },
        )

        val state = sm.ensureSessionForSync()

        assertEquals(SessionState.CHALLENGE_REQUIRED, state)
        assertFalse(loginCalled)
        assertEquals(LoginErrorKind.CHALLENGE_REQUIRED, sm.snapshot.value.errorKind)
        assertEquals("CAPTCHA shown", sm.snapshot.value.challengeReason)
    }

    @Test
    fun `ensureSessionForSync transitions to CHALLENGE_REQUIRED when auto-login hits challenge`() = runTest {
        val credsStore = FakeCredentialStore(stored = sampleCredentials())
        val sm = AuthSessionStateMachine(
            credentialStore = credsStore,
            hasCookies = { false },
            clearCookies = {},
            checkSessionValid = { SessionCheckResult.Expired },
            performPortalLogin = { PortalLoginResult.ChallengeRequired("OTP requested") },
        )

        val state = sm.ensureSessionForSync()

        assertEquals(SessionState.CHALLENGE_REQUIRED, state)
        assertEquals(LoginErrorKind.CHALLENGE_REQUIRED, sm.snapshot.value.errorKind)
        assertEquals("OTP requested", sm.snapshot.value.challengeReason)
    }

    @Test
    fun `submitFirstRunLogin distinguishes wrong details vs network error vs challenge vs success`() = runTest {
        val creds = sampleCredentials()
        val credsStore = FakeCredentialStore(stored = null)
        var nextResult: PortalLoginResult = PortalLoginResult.WrongDetails("Bad USN or DOB")

        val sm = AuthSessionStateMachine(
            credentialStore = credsStore,
            hasCookies = { false },
            clearCookies = {},
            checkSessionValid = { SessionCheckResult.Expired },
            performPortalLogin = { nextResult },
        )

        // Wrong details
        assertEquals(SessionState.FIRST_LOGIN, sm.submitFirstRunLogin(creds))
        assertEquals(LoginErrorKind.WRONG_DETAILS, sm.snapshot.value.errorKind)
        assertNull(credsStore.stored)

        // Network error
        nextResult = PortalLoginResult.NetworkError("Timeout")
        assertEquals(SessionState.FIRST_LOGIN, sm.submitFirstRunLogin(creds))
        assertEquals(LoginErrorKind.NETWORK_ERROR, sm.snapshot.value.errorKind)
        assertNull(credsStore.stored)

        // Challenge required -> completed
        nextResult = PortalLoginResult.ChallengeRequired("Solve CAPTCHA")
        assertEquals(SessionState.CHALLENGE_REQUIRED, sm.submitFirstRunLogin(creds))
        assertEquals(LoginErrorKind.CHALLENGE_REQUIRED, sm.snapshot.value.errorKind)
        assertEquals(SessionState.SESSION_VALID, sm.onChallengeCompleted(creds))
        assertEquals(creds, credsStore.stored)

        // Logout clears credentials and cookies
        var cookiesCleared = false
        val sm2 = AuthSessionStateMachine(
            credentialStore = credsStore,
            hasCookies = { true },
            clearCookies = { cookiesCleared = true },
            checkSessionValid = { SessionCheckResult.Valid },
            performPortalLogin = { PortalLoginResult.Success },
        )
        assertEquals(SessionState.FIRST_LOGIN, sm2.logout())
        assertTrue(cookiesCleared)
        assertNull(credsStore.stored)
        assertEquals(1, credsStore.clearCount)
    }

    @Test
    fun `ensureSessionForSync handles KeyInvalidated and AuthRequired cleanly`() = runTest {
        val credsStore = FakeCredentialStore(
            stored = sampleCredentials(),
            readOverride = CredentialReadResult.KeyInvalidated,
        )
        val sm = AuthSessionStateMachine(
            credentialStore = credsStore,
            hasCookies = { false },
            clearCookies = {},
            checkSessionValid = { SessionCheckResult.Expired },
            performPortalLogin = { PortalLoginResult.Success },
        )

        assertEquals(SessionState.FIRST_LOGIN, sm.ensureSessionForSync())
        assertEquals(LoginErrorKind.WRONG_DETAILS, sm.snapshot.value.errorKind)

        credsStore.readOverride = CredentialReadResult.AuthRequired
        assertEquals(SessionState.SESSION_EXPIRED, sm.ensureSessionForSync())
        assertEquals(LoginErrorKind.AUTH_REQUIRED, sm.snapshot.value.errorKind)
    }

    @Test
    fun `onSessionExpiredDuringSync transitions to SESSION_EXPIRED when credentials exist`() = runTest {
        val credsStore = FakeCredentialStore(stored = sampleCredentials())
        val sm = AuthSessionStateMachine(
            credentialStore = credsStore,
            hasCookies = { true },
            clearCookies = {},
            checkSessionValid = { SessionCheckResult.Valid },
            performPortalLogin = { PortalLoginResult.Success },
        )

        assertEquals(SessionState.SESSION_VALID, sm.evaluateSession())
        assertEquals(SessionState.SESSION_EXPIRED, sm.onSessionExpiredDuringSync())
    }
}
