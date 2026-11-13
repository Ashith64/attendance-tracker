package app.attendancetracker

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.attendancetracker.data.AttendanceDatabase
import app.attendancetracker.data.AttendanceRepository
import app.attendancetracker.data.SnapshotCategory
import app.attendancetracker.debug.DebugBridge
import app.attendancetracker.network.DataStoreCookiePersistence
import app.attendancetracker.network.FetchResult
import app.attendancetracker.network.PersistentCookieJar
import app.attendancetracker.network.PortalFetcher
import app.attendancetracker.security.AndroidCredentialStore
import app.attendancetracker.security.AuthSessionStateMachine
import app.attendancetracker.security.BiometricAppLock
import app.attendancetracker.security.CredentialReadResult
import app.attendancetracker.security.CredentialStore
import app.attendancetracker.security.DataStoreSettingsStore
import app.attendancetracker.security.PortalWebViewLoginController
import app.attendancetracker.security.SessionCheckResult
import app.attendancetracker.security.SessionState
import app.attendancetracker.security.SettingsStore
import app.attendancetracker.security.StudentCredentials
import app.attendancetracker.sync.AttendanceSyncWorker
import app.attendancetracker.sync.SyncManager
import app.attendancetracker.sync.SyncState
import app.attendancetracker.ui.ChallengeWebViewScreen
import app.attendancetracker.ui.CourseCardUiModel
import app.attendancetracker.ui.CourseDetailScreen
import app.attendancetracker.ui.CourseDetailUiModel
import app.attendancetracker.ui.CourseHealth
import app.attendancetracker.ui.DashboardScreenState
import app.attendancetracker.ui.HomeScreen
import app.attendancetracker.ui.HomeUiState
import app.attendancetracker.ui.LoginScreen
import app.attendancetracker.ui.SettingsScreen
import app.attendancetracker.ui.UiStateMapper
import app.attendancetracker.ui.theme.AppColors
import app.attendancetracker.ui.theme.AttendanceTrackerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {

    private lateinit var syncManager: SyncManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appContext = applicationContext
        AttendanceSyncWorker.schedulePeriodicSync(appContext)

        val database = AttendanceDatabase.getInstance(appContext)
        val repository = AttendanceRepository(database.portalDao())
        val credentialStore = AndroidCredentialStore(appContext)
        val settingsStore = DataStoreSettingsStore(appContext)
        val cookieJar = PersistentCookieJar(DataStoreCookiePersistence(appContext))
        val defaultUserAgent = try {
            android.webkit.WebSettings.getDefaultUserAgent(appContext)
        } catch (_: Throwable) {
            PortalFetcher.DEFAULT_BROWSER_USER_AGENT
        }
        val fetcher = PortalFetcher(cookieJar = cookieJar, userAgent = defaultUserAgent)
        val webViewController = PortalWebViewLoginController(appContext, cookieJar)

        val stateMachine = AuthSessionStateMachine(
            credentialStore = credentialStore,
            hasCookies = { cookieJar.hasCookiesFor(PortalFetcher.DEFAULT_BASE_URL) },
            clearCookies = {
                cookieJar.clear()
                webViewController.clearWebViewCookies()
            },
            checkSessionValid = {
                withContext(Dispatchers.IO) {
                    when (val res = fetcher.fetchDashboard()) {
                        is FetchResult.Success -> SessionCheckResult.Valid
                        is FetchResult.SessionExpired -> SessionCheckResult.Expired
                        is FetchResult.Error -> SessionCheckResult.NetworkError(res.reason)
                    }
                }
            },
            performPortalLogin = { creds -> webViewController.login(creds) },
        )

        syncManager = SyncManager(
            syncClient = fetcher,
            repository = repository,
            stateMachine = stateMachine,
        )

        setContent {
            AttendanceTrackerTheme {
                Surface(
                    color = AppColors.Background,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    AttendanceAppRoot(
                        activity = this@MainActivity,
                        stateMachine = stateMachine,
                        credentialStore = credentialStore,
                        repository = repository,
                        syncManager = syncManager,
                        settingsStore = settingsStore,
                        webViewController = webViewController,
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::syncManager.isInitialized) {
            syncManager.cancel()
        }
    }
}

@Composable
private fun AttendanceAppRoot(
    activity: FragmentActivity,
    stateMachine: AuthSessionStateMachine,
    credentialStore: CredentialStore,
    repository: AttendanceRepository,
    syncManager: SyncManager,
    settingsStore: SettingsStore,
    webViewController: PortalWebViewLoginController,
) {
    val scope = rememberCoroutineScope()
    var isUnlocked by remember { mutableStateOf(false) }
    var lockMessage by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showDebugScreen by remember { mutableStateOf(false) }
    var selectedCourseCode by remember { mutableStateOf<String?>(null) }
    var pendingCredentials by remember { mutableStateOf<StudentCredentials?>(null) }
    var storedCredentials by remember { mutableStateOf<StudentCredentials?>(null) }

    LaunchedEffect(Unit) {
        when (val res = credentialStore.read()) {
            is CredentialReadResult.Success -> storedCredentials = res.credentials
            else -> {}
        }
    }

    val authSnapshot by stateMachine.snapshot.collectAsState()
    val syncState by syncManager.state.collectAsState()
    val isSyncing = syncState is SyncState.Running

    val thresholdPercent by settingsStore.thresholdPercent.collectAsState(
        initial = SettingsStore.DEFAULT_THRESHOLD,
    )
    val courses by repository.observeCoursesForLatestTerm(thresholdPercent).collectAsState(
        initial = emptyList(),
    )
    val latestTerm by repository.observeLatestTerm().collectAsState(initial = null)
    val layoutChangedWarning by repository.observeLayoutChangedWarningForLatestTerm().collectAsState(
        initial = false,
    )

    fun triggerBiometricUnlock() {
        lockMessage = null
        BiometricAppLock.authenticate(
            activity = activity,
            onSuccess = { isUnlocked = true },
            onError = { err -> lockMessage = err },
            onUnavailable = {
                lockMessage = "Set a device PIN, pattern, password, or fingerprint in Android Settings to protect your credentials."
            },
        )
    }

    fun triggerSync(forceRefresh: Boolean) {
        Log.d("MainActivity", "triggerSync forceRefresh=$forceRefresh")
        scope.launch {
            withContext(Dispatchers.IO) {
                val state = syncManager.sync(forceRefresh = forceRefresh)
                Log.d("MainActivity", "triggerSync finished with state=$state")
            }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            stateMachine.restoreSessionState()
        }
        triggerBiometricUnlock()
    }

    // Once unlocked, show cached dashboard immediately and start sync in background (5-min throttle).
    LaunchedEffect(isUnlocked) {
        if (isUnlocked) {
            withContext(Dispatchers.IO) {
                syncManager.sync(forceRefresh = false)
            }
        }
    }

    if (!isUnlocked) {
        LockScreen(
            message = lockMessage,
            onUnlockClick = { triggerBiometricUnlock() },
        )
        return
    }

    if (DebugBridge.isDebugAvailable && showDebugScreen) {
        DebugBridge.DebugScreenContent(
            repository = repository,
            onBack = { showDebugScreen = false },
        )
        return
    }

    if (showSettings) {
        SettingsScreen(
            thresholdPercent = thresholdPercent,
            onSelectThreshold = { selected ->
                scope.launch { settingsStore.setThresholdPercent(selected) }
            },
            onLogout = {
                scope.launch {
                    pendingCredentials = null
                    selectedCourseCode = null
                    stateMachine.logout()
                    showSettings = false
                }
            },
            onBack = { showSettings = false },
            onOpenDebug = if (DebugBridge.isDebugAvailable) {
                { showDebugScreen = true }
            } else {
                null
            },
        )
        return
    }

    val showDashboard = authSnapshot.state == SessionState.SESSION_VALID ||
        authSnapshot.state == SessionState.SESSION_EXPIRED ||
        courses.isNotEmpty()

    if (authSnapshot.state == SessionState.CHALLENGE_REQUIRED) {
        val activeCreds = pendingCredentials ?: storedCredentials
        ChallengeWebViewScreen(
            reason = authSnapshot.challengeReason,
            webViewController = webViewController,
            credentials = activeCreds,
            onCompleted = {
                scope.launch {
                    stateMachine.onChallengeCompleted(activeCreds)
                    pendingCredentials = null
                    triggerSync(forceRefresh = true)
                }
            },
            onCancelToLogin = {
                scope.launch {
                    stateMachine.logout()
                }
            },
        )
    } else if (showDashboard) {
        if (DebugBridge.isDebugAvailable && DebugBridge.useFakeData) {
                val activeCourseCode = selectedCourseCode
                val fakeDetail = activeCourseCode?.let {
                    DebugBridge.getFakeCourseDetail(it, thresholdPercent)
                }
                if (fakeDetail != null) {
                    CourseDetailScreen(
                        detail = fakeDetail,
                        defaultThresholdPercent = thresholdPercent,
                        onBack = { selectedCourseCode = null },
                    )
                } else {
                    HomeScreen(
                        state = DebugBridge.getFakeHomeUiState(
                            thresholdPercent = thresholdPercent,
                            isSyncing = isSyncing,
                        ),
                        onRefresh = { triggerSync(forceRefresh = true) },
                        onOpenSettings = { showSettings = true },
                        onCourseClick = { code -> selectedCourseCode = code },
                        onOpenChallenge = { triggerSync(forceRefresh = true) },
                    )
                }
                return
            }

            val nowMs = remember(courses, syncState) { System.currentTimeMillis() }
            val courseCards = remember(courses, thresholdPercent, nowMs) {
                courses.map {
                    UiStateMapper.mapCourseCard(
                        model = it,
                        thresholdPercent = thresholdPercent,
                        nowMs = nowMs,
                    )
                }.sortedWith(
                    compareBy<CourseCardUiModel> { card ->
                        // Only courses with NO DATA (conducted == null or conducted == 0) go to the bottom section.
                        // Genuine 0% courses (conducted > 0, attended == 0) stay in active courses and appear FIRST (critical!).
                        val isNoData = card.health == CourseHealth.NO_DATA ||
                            card.conducted == null || card.conducted == 0
                        if (isNoData) 1 else 0
                    }.thenBy { card ->
                        // Lowest attendance percentage appears first
                        val pct = if (card.attended != null && card.conducted != null && card.conducted > 0) {
                            (card.attended.toDouble() * 100.0) / card.conducted.toDouble()
                        } else {
                            Double.MAX_VALUE
                        }
                        pct
                    },
                )
            }

            AnimatedContent(
                targetState = selectedCourseCode,
                transitionSpec = {
                    if (targetState != null) {
                        // Navigating into detail: snappy slide from right + fade
                        (slideInHorizontally(tween(180, easing = FastOutSlowInEasing)) { it / 4 } + fadeIn(tween(160))) togetherWith
                            (slideOutHorizontally(tween(150, easing = FastOutLinearInEasing)) { -it / 4 } + fadeOut(tween(140)))
                    } else {
                        // Back to home: snappy slide from left + fade
                        (slideInHorizontally(tween(180, easing = FastOutSlowInEasing)) { -it / 4 } + fadeIn(tween(160))) togetherWith
                            (slideOutHorizontally(tween(150, easing = FastOutLinearInEasing)) { it / 4 } + fadeOut(tween(140)))
                    }
                },
                label = "CourseScreenTransition",
            ) { targetCode ->
                val activeCard = courseCards.find { it.courseCode == targetCode }
                if (targetCode != null && activeCard != null) {
                    val termId = activeCard.term
                    val attendanceHistory by repository
                        .observeHistory(targetCode, SnapshotCategory.ATTENDANCE, termId)
                        .collectAsState(initial = emptyList())
                    val sessionRecords by repository
                        .observeAttendanceRecords(targetCode, termId)
                        .collectAsState(initial = emptyList())

                    val mappedSessions = remember(sessionRecords) {
                        UiStateMapper.mapAttendanceRecords(sessionRecords)
                    }

                    val detailModel = CourseDetailUiModel(
                        card = activeCard,
                        attendanceHistory = UiStateMapper.mapAttendanceHistory(attendanceHistory, nowMs),
                        sessionRecords = mappedSessions,
                    )

                    CourseDetailScreen(
                        detail = detailModel,
                        defaultThresholdPercent = thresholdPercent,
                        onBack = { selectedCourseCode = null },
                    )
                } else {
                    val screenState = when (val s = syncState) {
                        is SyncState.Running -> if (courseCards.isEmpty()) DashboardScreenState.Loading else DashboardScreenState.Content
                        is SyncState.Failed -> if (courseCards.isEmpty()) DashboardScreenState.Error(s.reason) else DashboardScreenState.Content
                        is SyncState.NeedsChallenge -> DashboardScreenState.NeedsChallenge(s.reason)
                        else -> if (courseCards.isEmpty()) DashboardScreenState.Empty else DashboardScreenState.Content
                    }

                    val latestSyncMs = courseCards.mapNotNull { it.lastSyncedAt }.maxOrNull()
                    val lastSyncText = when (val s = syncState) {
                        is SyncState.Running -> "Syncing ${s.progressText}..."
                        is SyncState.Failed -> if (latestSyncMs != null) {
                            "Synced ${UiStateMapper.formatRelativeTime(latestSyncMs, nowMs)}"
                        } else {
                            "Not synced yet"
                        }
                        else -> if (latestSyncMs != null) {
                            "Synced ${UiStateMapper.formatRelativeTime(latestSyncMs, nowMs)}"
                        } else {
                            "Not synced yet"
                        }
                    }

                    val syncProgress = (syncState as? SyncState.Running)?.let { running ->
                        if (running.totalCourses > 0) {
                            running.currentCourse.toFloat() / running.totalCourses.toFloat()
                        } else {
                            null
                        }
                    }

                    HomeScreen(
                        state = HomeUiState(
                            studentName = null,
                            termLabel = latestTerm?.label,
                            lastSyncText = lastSyncText,
                            isSyncing = isSyncing,
                            syncProgress = syncProgress,
                            courses = courseCards,
                            screenState = screenState,
                            thresholdPercent = thresholdPercent,
                            layoutChangedWarning = layoutChangedWarning,
                        ),
                        onRefresh = { triggerSync(forceRefresh = true) },
                        onOpenSettings = { showSettings = true },
                        onCourseClick = { code -> selectedCourseCode = code },
                        onOpenChallenge = { triggerSync(forceRefresh = true) },
                    )
                }
            }
        } else {
        LoginScreen(
            snapshot = authSnapshot,
            onSubmit = { credentials ->
                pendingCredentials = credentials
                scope.launch {
                    val resultState = stateMachine.submitFirstRunLogin(credentials)
                    if (resultState == SessionState.SESSION_VALID) {
                        pendingCredentials = null
                        triggerSync(forceRefresh = true)
                    }
                }
            },
        )
    }
}

@Composable
private fun LockScreen(
    message: String?,
    onUnlockClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Attendance Tracker Locked",
                style = MaterialTheme.typography.headlineSmall,
                color = AppColors.TextPrimary,
            )
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.DangerRed,
                )
            }
            Button(
                onClick = onUnlockClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.TextPrimary,
                    contentColor = AppColors.Background,
                ),
            ) {
                Text("Unlock")
            }
        }
    }
}
