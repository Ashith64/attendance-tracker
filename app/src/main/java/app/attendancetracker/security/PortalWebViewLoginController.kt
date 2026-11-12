package app.attendancetracker.security

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import app.attendancetracker.network.PersistentCookieJar
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

/**
 * Architecture B WebView two-stage login controller for https://parents.nie.ac.in/.
 *
 * - Stage 1: fills #username, #dd, #mm, #yyyy and lets the page's submitLogin() run.
 * - Stage 2: fills #id-type-select, .digit-input, waits for #recaptcha-token, and submits.
 * - If a visible CAPTCHA, OTP, or challenge appears (or token times out), returns
 *   [PortalLoginResult.ChallengeRequired] so the UI can display the WebView to the student.
 * - Never logs USN, DOB, digits, cookies, or tokens.
 */
class PortalWebViewLoginController(
    private val context: Context,
    private val cookieJar: PersistentCookieJar,
) {
    companion object {
        const val PORTAL_URL = "https://parents.nie.ac.in/"
        private const val LOGIN_TIMEOUT_MS = 35_000L
        private const val RECAPTCHA_POLL_MS = 200L
        private const val RECAPTCHA_MAX_POLLS = 50
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    fun configureWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = WebSettings.getDefaultUserAgent(context)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
    }

    /**
     * Runs automated two-stage WebView login in an off-screen WebView.
     * On [PortalLoginResult.ChallengeRequired], callers should display a visible WebView
     * using [attachInteractiveChallengeClient] so the student can complete the check.
     */
    suspend fun login(credentials: StudentCredentials): PortalLoginResult =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val webView = WebView(context)
                configureWebView(webView)
                webView.settings.blockNetworkImage = true

                var stage1Submitted = false
                var stage2Submitted = false
                var recaptchaPolls = 0

                val timeoutRunnable = Runnable {
                    if (continuation.isActive) {
                        webView.stopLoading()
                        webView.destroy()
                        continuation.resume(
                            PortalLoginResult.ChallengeRequired(
                                "Portal login timed out or requires manual verification.",
                            ),
                        )
                    }
                }
                mainHandler.postDelayed(timeoutRunnable, LOGIN_TIMEOUT_MS)

                fun finishWith(result: PortalLoginResult) {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    if (continuation.isActive) {
                        webView.stopLoading()
                        webView.destroy()
                        continuation.resume(result)
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: return null
                        if (reqUrl.contains("ionicframework.com", ignoreCase = true) ||
                            reqUrl.contains("fonts.googleapis.com", ignoreCase = true) ||
                            reqUrl.contains("fonts.gstatic.com", ignoreCase = true)
                        ) {
                            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            finishWith(
                                PortalLoginResult.NetworkError(
                                    "Could not reach parents.nie.ac.in. Check your connection and try again.",
                                ),
                            )
                        }
                    }

                    override fun onReceivedSslError(
                        view: WebView?,
                        handler: SslErrorHandler?,
                        error: SslError?,
                    ) {
                        handler?.cancel()
                        finishWith(PortalLoginResult.NetworkError("SSL error connecting to portal."))
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (view == null || url == null || !continuation.isActive) return
                        if (!url.startsWith(PORTAL_URL)) return

                        inspectAndAct(
                            webView = view,
                            credentials = credentials,
                            stage1Submitted = stage1Submitted,
                            stage2Submitted = stage2Submitted,
                            onMarkStage1 = { stage1Submitted = true },
                            onMarkStage2 = { stage2Submitted = true },
                            recaptchaPolls = { recaptchaPolls++ },
                            onSuccess = {
                                exportCookiesToJar(view.url)
                                finishWith(PortalLoginResult.Success)
                            },
                            onWrongDetails = { msg -> finishWith(PortalLoginResult.WrongDetails(msg)) },
                            onChallenge = { reason -> finishWith(PortalLoginResult.ChallengeRequired(reason)) },
                            continuation = continuation,
                        )
                    }
                }

                continuation.invokeOnCancellation {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    webView.stopLoading()
                    webView.destroy()
                }

                CookieManager.getInstance().removeAllCookies {
                    webView.loadUrl(PORTAL_URL)
                }
            }
        }

    /**
     * Attaches a WebViewClient to a visible WebView so the student can manually complete
     * a CAPTCHA, OTP, or portal challenge. Calls [onLoginSuccess] once the dashboard loads.
     * Optionally takes [credentials] to auto-populate USN/DOB if the page is on Stage 1.
     */
    fun attachInteractiveChallengeClient(
        webView: WebView,
        credentials: StudentCredentials? = null,
        onLoginSuccess: () -> Unit,
        onNetworkError: (String) -> Unit,
    ) {
        configureWebView(webView)
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    onNetworkError("Could not reach parents.nie.ac.in.")
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (view == null || url == null) return
                if (!url.startsWith(PORTAL_URL)) return
                view.evaluateJavascript(DETECT_PAGE_STATE_JS) { raw ->
                    val state = unquoteJsResult(raw)
                    if (state == "DASHBOARD") {
                        exportCookiesToJar(view.url)
                        onLoginSuccess()
                    } else if (state == "STAGE1" && credentials != null) {
                        view.evaluateJavascript(buildStage1FillJs(credentials), null)
                    }
                }
            }
        }
        webView.loadUrl(PORTAL_URL)
    }

    fun exportCookiesToJar(specificUrl: String? = null) {
        val manager = CookieManager.getInstance()
        manager.flush()
        val candidateUrls = listOfNotNull(
            PORTAL_URL,
            "https://parents.nie.ac.in/index.php",
            "https://parents.nie.ac.in/index.php?option=com_studentdashboard&controller=studentdashboard&task=dashboard",
            specificUrl,
        ).distinct()
        var count = 0
        for (url in candidateUrls) {
            val header = manager.getCookie(url)
            if (!header.isNullOrBlank()) {
                cookieJar.importFromWebViewCookieHeader(url, header)
                count++
            }
        }
        Log.d("PortalLogin", "exportCookiesToJar: checked ${candidateUrls.size} candidate urls, found cookies on $count")
    }

    fun clearWebViewCookies() {
        val manager = CookieManager.getInstance()
        manager.removeAllCookies(null)
        manager.flush()
    }

    private fun inspectAndAct(
        webView: WebView,
        credentials: StudentCredentials,
        stage1Submitted: Boolean,
        stage2Submitted: Boolean,
        onMarkStage1: () -> Unit,
        onMarkStage2: () -> Unit,
        recaptchaPolls: () -> Int,
        onSuccess: () -> Unit,
        onWrongDetails: (String) -> Unit,
        onChallenge: (String) -> Unit,
        continuation: CancellableContinuation<PortalLoginResult>,
    ) {
        webView.evaluateJavascript(DETECT_PAGE_STATE_JS) { raw ->
            if (!continuation.isActive) return@evaluateJavascript
            val detected = unquoteJsResult(raw)
            Log.d(
                "PortalLogin",
                "inspectAndAct state=$detected stage1Submitted=$stage1Submitted stage2Submitted=$stage2Submitted",
            )
            when (detected) {
                "DASHBOARD" -> onSuccess()
                "LOGIN_ERROR" -> {
                    val msg = if (!stage1Submitted) {
                        "Portal showed login error before submit. Please try again."
                    } else if (!stage2Submitted) {
                        "Invalid USN or date of birth (Stage 1). Please check your USN and DOB and try again."
                    } else {
                        "Invalid parent relation or last 4 mobile digits (Stage 2). Please check your details and try again."
                    }
                    onWrongDetails(msg)
                }
                "CHALLENGE" -> onChallenge(
                    "Portal requires verification (CAPTCHA or OTP). Please complete it in the portal window.",
                )
                "STAGE1" -> {
                    if (stage1Submitted) {
                        // Stage 1 reloaded without modal error -> check again or treat as challenge
                        onChallenge("Portal did not advance past Stage 1 login. Please complete login in the portal window.")
                    } else {
                        onMarkStage1()
                        webView.evaluateJavascript(buildStage1FillJs(credentials), null)
                    }
                }
                "STAGE2" -> {
                    if (stage2Submitted) {
                        onWrongDetails(
                            "Verification failed. Please check your relation and last 4 mobile digits.",
                        )
                    } else {
                        pollRecaptchaAndSubmitStage2(
                            webView = webView,
                            credentials = credentials,
                            recaptchaPolls = recaptchaPolls,
                            onMarkStage2 = onMarkStage2,
                            onChallenge = onChallenge,
                            continuation = continuation,
                        )
                    }
                }
            }
        }
    }

    private fun pollRecaptchaAndSubmitStage2(
        webView: WebView,
        credentials: StudentCredentials,
        recaptchaPolls: () -> Int,
        onMarkStage2: () -> Unit,
        onChallenge: (String) -> Unit,
        continuation: CancellableContinuation<PortalLoginResult>,
    ) {
        webView.evaluateJavascript(
            "(function(){ var t = document.getElementById('recaptcha-token'); return t ? t.value : ''; })()",
        ) { raw ->
            if (!continuation.isActive) return@evaluateJavascript
            val token = unquoteJsResult(raw)
            if (token.isNotEmpty()) {
                onMarkStage2()
                webView.evaluateJavascript(buildStage2FillJs(credentials), null)
            } else if (recaptchaPolls() < RECAPTCHA_MAX_POLLS) {
                mainHandler.postDelayed(
                    {
                        if (continuation.isActive) {
                            pollRecaptchaAndSubmitStage2(
                                webView, credentials, recaptchaPolls, onMarkStage2, onChallenge, continuation,
                            )
                        }
                    },
                    RECAPTCHA_POLL_MS,
                )
            } else {
                onChallenge(
                    "Portal verification token did not load automatically. Please complete verification in the portal window.",
                )
            }
        }
    }

    private fun buildStage1FillJs(credentials: StudentCredentials): String {
        val usn = JSONObject.quote(credentials.usn.trim())
        val dd = JSONObject.quote(credentials.dobDay.trim().padStart(2, '0'))
        val mm = JSONObject.quote(credentials.dobMonth.trim().padStart(2, '0'))
        val yyyy = JSONObject.quote(credentials.dobYear.trim())
        return """
            (function() {
                var u = document.getElementById('username');
                var d = document.getElementById('dd');
                var m = document.getElementById('mm');
                var y = document.getElementById('yyyy');
                var form = document.getElementById('login-form');
                if (u) u.value = $usn;
                if (d) d.value = $dd;
                if (m) m.value = $mm;
                if (y) y.value = $yyyy;
                if (typeof submitLogin === 'function') {
                    var ok = submitLogin();
                    if (ok !== false && form) form.submit();
                } else if (form) {
                    form.submit();
                }
            })();
        """.trimIndent()
    }

    private fun buildStage2FillJs(credentials: StudentCredentials): String {
        val relation = JSONObject.quote(credentials.relation.toString())
        val digits = credentials.digits.trim()
        val d0 = JSONObject.quote(digits.getOrElse(0) { '0' }.toString())
        val d1 = JSONObject.quote(digits.getOrElse(1) { '0' }.toString())
        val d2 = JSONObject.quote(digits.getOrElse(2) { '0' }.toString())
        val d3 = JSONObject.quote(digits.getOrElse(3) { '0' }.toString())
        val combined = JSONObject.quote(digits)
        return """
            (function() {
                var sel = document.getElementById('id-type-select');
                if (sel) sel.value = $relation;
                var inputs = document.querySelectorAll('.digit-input');
                var vals = [$d0, $d1, $d2, $d3];
                for (var i = 0; i < inputs.length && i < 4; i++) {
                    inputs[i].value = vals[i];
                }
                var hidden = document.getElementById('enteredid');
                if (hidden) hidden.value = $combined;
                var form = document.getElementById('login-form');
                if (typeof combineDigits === 'function') {
                    var ok = combineDigits();
                    if (ok !== false && form) form.submit();
                } else if (form) {
                    form.submit();
                }
            })();
        """.trimIndent()
    }

    private fun unquoteJsResult(raw: String?): String {
        if (raw == null || raw == "null") return ""
        return raw.trim().removeSurrounding("\"")
    }
}

private val DETECT_PAGE_STATE_JS = """
    (function() {
        var modal = document.getElementById('myloginModal');
        if (modal) {
            var style = window.getComputedStyle(modal);
            var isVisible = style && style.display !== 'none' && style.visibility !== 'hidden';
            if (isVisible && modal.innerText && modal.innerText.toLowerCase().indexOf('invalid') !== -1) {
                return 'LOGIN_ERROR';
            }
        }
        var bodyText = (document.body && document.body.innerText) ? document.body.innerText.toLowerCase() : '';
        if (bodyText.indexOf('invalid username or password') !== -1 ||
            bodyText.indexOf('login failed') !== -1) {
            return 'LOGIN_ERROR';
        }
        if (document.querySelector('table.cn-pay-table') ||
            window.location.href.indexOf('task=dashboard') !== -1) {
            return 'DASHBOARD';
        }
        if (document.querySelector('iframe[src*="recaptcha/api2/bframe"]') ||
            document.querySelector('.g-recaptcha') ||
            document.querySelector('input[name*="otp" i]:not([type="hidden"])')) {
            return 'CHALLENGE';
        }
        if (document.getElementById('id-type-select') ||
            document.querySelector('input[name="task"][value="login"]')) {
            return 'STAGE2';
        }
        if (document.getElementById('username') && document.getElementById('dd')) {
            return 'STAGE1';
        }
        return 'UNKNOWN';
    })();
""".trimIndent()
