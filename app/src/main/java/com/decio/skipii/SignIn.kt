package com.decio.skipii

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class AuthMainTab { Login, Register }
private enum class LoginSubMode { Password, EmailCode, ResetPassword }
private enum class RegisterSubMode { Email, Passkey, Local }

private tailrec fun Context.findSignInActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findSignInActivity()
    else -> null
}

/**
 * Native Material 3 Expressive sign-in & registration screen for TechniClip Zone (てくぞね),
 * supporting Passkeys (Android CredentialManager), Email verification (OTP & Password),
 * ID/Password login, and SSO WebView flows (Techniclip.com, Discord, Otomad).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SignInScreen(onDone: () -> Unit, onCancel: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var mainTab by remember { mutableStateOf(AuthMainTab.Login) }
    var loginMode by remember { mutableStateOf(LoginSubMode.Password) }
    var regMode by remember { mutableStateOf(RegisterSubMode.Email) }

    // Login states
    var loginIdent by remember { mutableStateOf("") }
    var loginPassword by remember { mutableStateOf("") }
    var showLoginPassword by remember { mutableStateOf(false) }
    var loginEmail by remember { mutableStateOf("") }
    var loginEmailCode by remember { mutableStateOf("") }

    // Password reset states
    var resetEmail by remember { mutableStateOf("") }
    var resetCode by remember { mutableStateOf("") }
    var resetNewPassword by remember { mutableStateOf("") }

    // Register states
    var regEmail by remember { mutableStateOf("") }
    var regEmailCode by remember { mutableStateOf("") }
    var regTekuzoneId by remember { mutableStateOf("") }
    var regDisplayName by remember { mutableStateOf("") }
    var regPassword by remember { mutableStateOf("") }
    var showRegPassword by remember { mutableStateOf(false) }
    var idAvailabilityHint by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    // Shared UI states
    var codeCooldownSec by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var sendingCode by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }

    // Null = Native login/signup UI; non-null = SSO WebView URL
    var webUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(codeCooldownSec) {
        if (codeCooldownSec > 0) {
            delay(1000L)
            codeCooldownSec -= 1
        }
    }

    LaunchedEffect(regTekuzoneId) {
        val id = regTekuzoneId.trim()
        if (id.isEmpty()) {
            idAvailabilityHint = null
            return@LaunchedEffect
        }
        if (!Regex("^[A-Za-z0-9_\\-.]+$").matches(id)) {
            idAvailabilityHint = false to "半角英数字・ハイフン・アンダースコア・ドットのみ使用可能です"
            return@LaunchedEffect
        }
        delay(350L)
        runCatching { Account.checkTekuzoneId(id) }
            .onSuccess { ok ->
                idAvailabilityHint = if (ok) {
                    true to "✓ このてくぞねIDは利用可能です"
                } else {
                    false to "✕ このIDは既に使われています"
                }
            }
    }

    fun clearFeedback() {
        error = null
        infoMessage = null
    }

    fun requestEmailCode(targetEmail: String, purpose: String) {
        val e = targetEmail.trim()
        if (e.isEmpty() || sendingCode || codeCooldownSec > 0) {
            if (e.isEmpty()) error = "メールアドレスを入力してください"
            return
        }
        focusManager.clearFocus()
        sendingCode = true
        clearFeedback()
        scope.launch {
            runCatching { Account.sendEmailCode(e, purpose) }
                .onSuccess {
                    sendingCode = false
                    codeCooldownSec = 30
                    infoMessage = "認証コードを送信しました！メールをご確認ください。"
                }
                .onFailure {
                    sendingCode = false
                    error = it.message ?: "認証コードの送信に失敗しました"
                }
        }
    }

    fun runAuthAction(block: suspend () -> Unit) {
        if (loading) return
        focusManager.clearFocus()
        loading = true
        clearFeedback()
        scope.launch {
            runCatching { block() }
                .onSuccess {
                    loading = false
                    onDone()
                }
                .onFailure {
                    loading = false
                    error = it.message ?: "認証に失敗しました"
                }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.surface)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // ── Top Bar ──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(c.surfaceContainerHigh)
                    .pressable {
                        if (webUrl != null) {
                            webUrl = null
                            clearFeedback()
                        } else {
                            onCancel()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (webUrl != null) Icons.AutoMirrored.Rounded.ArrowBack else Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.ui_cancel),
                    tint = c.onSurface,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (mainTab == AuthMainTab.Login) stringResource(R.string.ui_sign_in_title) else "アカウント新規登録",
                    fontFamily = RoundedHeavy,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp,
                    color = c.onSurface,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, null, tint = c.onSurfaceVariant, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.ui_sign_in_line),
                        color = c.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        if (webUrl != null) {
            // ── Full-screen SSO WebView ──
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val cookies = CookieManager.getInstance()
                        cookies.removeAllCookies(null)
                        cookies.setAcceptCookie(true)
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            cookies.setAcceptThirdPartyCookies(this, true)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.useWideViewPort = false
                            settings.loadWithOverviewMode = false
                            settings.textZoom = 100
                            settings.userAgentString = WebSettings.getDefaultUserAgent(ctx).replace("; wv", "")
                            webViewClient = object : WebViewClient() {
                                private fun checkCookies() {
                                    if (loading) return
                                    val header = cookies.getCookie(Account.ORIGIN)
                                    if (!Account.looksSignedIn(header)) return
                                    loading = true
                                    scope.launch {
                                        runCatching { Account.completeSignIn(header) }
                                            .onSuccess {
                                                cookies.removeAllCookies(null)
                                                cookies.flush()
                                                loading = false
                                                onDone()
                                            }
                                            .onFailure {
                                                loading = false
                                                error = str(R.string.ui_sign_in_error)
                                            }
                                    }
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    view.evaluateJavascript(
                                        """
                                        (function() {
                                            var hero = document.querySelector('.hero');
                                            if (hero) hero.style.display = 'none';
                                            var panel = document.querySelector('.panel');
                                            if (panel) {
                                                panel.style.width = '100%';
                                                panel.style.minWidth = '0';
                                                panel.style.height = '100vh';
                                                panel.style.marginTop = '0';
                                                panel.style.borderRadius = '0';
                                                panel.style.padding = '28px 20px';
                                            }
                                            document.body.style.overflow = 'auto';
                                        })();
                                        """.trimIndent(),
                                        null,
                                    )
                                    checkCookies()
                                }

                                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                    super.doUpdateVisitedHistory(view, url, isReload)
                                    checkCookies()
                                }
                            }
                            loadUrl(webUrl!!)
                        }
                    },
                )
                if (loading) {
                    Column(
                        Modifier.fillMaxSize().background(c.surface).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(color = c.primary, strokeWidth = 4.dp, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.ui_finishing_sign_in), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        } else {
            // ── Native Material 3 Expressive Login & Registration Form ──
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = 460.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(R.drawable.app_icon),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "てくにくりっぷ ぞーん",
                        fontFamily = RoundedHeavy,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 24.sp,
                        letterSpacing = (-0.6).sp,
                        color = c.onSurface,
                    )
                    Spacer(Modifier.height(14.dp))

                    // Main Segmented Switcher: Login | Register
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(c.surfaceContainerHigh)
                            .padding(4.dp),
                    ) {
                        SegmentedPill(
                            text = "ログイン",
                            selected = mainTab == AuthMainTab.Login,
                            modifier = Modifier.weight(1f),
                            onClick = { mainTab = AuthMainTab.Login; clearFeedback() },
                        )
                        SegmentedPill(
                            text = "新規登録",
                            selected = mainTab == AuthMainTab.Register,
                            modifier = Modifier.weight(1f),
                            onClick = { mainTab = AuthMainTab.Register; clearFeedback() },
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    if (infoMessage != null) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(c.primaryContainer.copy(alpha = 0.65f))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(
                                infoMessage.orEmpty(),
                                color = c.onPrimaryContainer,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    if (error != null) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(c.errorContainer.copy(alpha = 0.7f))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(
                                error.orEmpty(),
                                color = c.onErrorContainer,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    if (mainTab == AuthMainTab.Login) {
                        // ── Passkey Login Button ──
                        PasskeyActionCard(
                            text = "パスキーでログイン",
                            enabled = !loading,
                            onClick = {
                                val act = context.findSignInActivity()
                                if (act == null) {
                                    error = "アクティビティを取得できませんでした"
                                } else {
                                    runAuthAction {
                                        Account.loginWithPasskey(act, loginIdent.takeIf { it.isNotBlank() })
                                    }
                                }
                            },
                        )

                        Spacer(Modifier.height(14.dp))

                        // Login Sub-mode Switcher
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(c.surfaceContainer)
                                .padding(3.dp),
                        ) {
                            SmallModeChip(
                                text = "パスワード",
                                selected = loginMode == LoginSubMode.Password || loginMode == LoginSubMode.ResetPassword,
                                modifier = Modifier.weight(1f),
                                onClick = { loginMode = LoginSubMode.Password; clearFeedback() },
                            )
                            SmallModeChip(
                                text = "メール認証コード",
                                selected = loginMode == LoginSubMode.EmailCode,
                                modifier = Modifier.weight(1f),
                                onClick = { loginMode = LoginSubMode.EmailCode; clearFeedback() },
                            )
                        }

                        Spacer(Modifier.height(14.dp))

                        when (loginMode) {
                            LoginSubMode.Password -> {
                                AuthField(
                                    value = loginIdent,
                                    onValueChange = { loginIdent = it; error = null },
                                    placeholder = "てくぞねID または メールアドレス",
                                    icon = Icons.Rounded.Person,
                                    keyboardType = KeyboardType.Email,
                                    imeAction = ImeAction.Next,
                                )
                                Spacer(Modifier.height(10.dp))
                                AuthPasswordField(
                                    value = loginPassword,
                                    onValueChange = { loginPassword = it; error = null },
                                    placeholder = "パスワード",
                                    visible = showLoginPassword,
                                    onToggleVisible = { showLoginPassword = !showLoginPassword },
                                    onDone = {
                                        if (loginIdent.isNotBlank() && loginPassword.isNotEmpty()) {
                                            runAuthAction { Account.loginWithPassword(loginIdent, loginPassword) }
                                        }
                                    },
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .pressable { loginMode = LoginSubMode.ResetPassword; clearFeedback() }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                    ) {
                                        Text(
                                            "パスワードをお忘れですか？（メール再設定）",
                                            color = c.primary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                PrimaryAuthButton(
                                    text = "ログイン",
                                    enabled = loginIdent.isNotBlank() && loginPassword.isNotEmpty() && !loading,
                                    loading = loading,
                                    onClick = { runAuthAction { Account.loginWithPassword(loginIdent, loginPassword) } },
                                )
                            }

                            LoginSubMode.EmailCode -> {
                                EmailWithCodeButtonRow(
                                    email = loginEmail,
                                    onEmailChange = { loginEmail = it; error = null },
                                    placeholder = "登録メールアドレス",
                                    cooldownSec = codeCooldownSec,
                                    sending = sendingCode,
                                    onSendCode = { requestEmailCode(loginEmail, "login") },
                                )
                                Spacer(Modifier.height(10.dp))
                                AuthField(
                                    value = loginEmailCode,
                                    onValueChange = { loginEmailCode = it.filter(Char::isDigit).take(6); error = null },
                                    placeholder = "6桁の認証コード",
                                    icon = Icons.Rounded.Pin,
                                    keyboardType = KeyboardType.NumberPassword,
                                    imeAction = ImeAction.Done,
                                    onDone = {
                                        if (loginEmail.isNotBlank() && loginEmailCode.length == 6) {
                                            runAuthAction { Account.loginWithEmailCode(loginEmail, loginEmailCode) }
                                        }
                                    },
                                )
                                Spacer(Modifier.height(14.dp))
                                PrimaryAuthButton(
                                    text = "認証コードでログイン",
                                    enabled = loginEmail.isNotBlank() && loginEmailCode.length >= 4 && !loading,
                                    loading = loading,
                                    onClick = { runAuthAction { Account.loginWithEmailCode(loginEmail, loginEmailCode) } },
                                )
                            }

                            LoginSubMode.ResetPassword -> {
                                EmailWithCodeButtonRow(
                                    email = resetEmail,
                                    onEmailChange = { resetEmail = it; error = null },
                                    placeholder = "登録メールアドレス",
                                    cooldownSec = codeCooldownSec,
                                    sending = sendingCode,
                                    onSendCode = { requestEmailCode(resetEmail, "reset_password") },
                                )
                                Spacer(Modifier.height(10.dp))
                                AuthField(
                                    value = resetCode,
                                    onValueChange = { resetCode = it.filter(Char::isDigit).take(6); error = null },
                                    placeholder = "6桁の認証コード",
                                    icon = Icons.Rounded.Pin,
                                    keyboardType = KeyboardType.NumberPassword,
                                    imeAction = ImeAction.Next,
                                )
                                Spacer(Modifier.height(10.dp))
                                AuthPasswordField(
                                    value = resetNewPassword,
                                    onValueChange = { resetNewPassword = it; error = null },
                                    placeholder = "新しいパスワード（6文字以上）",
                                    visible = showLoginPassword,
                                    onToggleVisible = { showLoginPassword = !showLoginPassword },
                                )
                                Spacer(Modifier.height(14.dp))
                                PrimaryAuthButton(
                                    text = "パスワードを再設定してログイン",
                                    enabled = resetEmail.isNotBlank() && resetCode.length >= 4 && resetNewPassword.length >= 6 && !loading,
                                    loading = loading,
                                    onClick = {
                                        runAuthAction {
                                            Account.resetPasswordWithEmail(resetEmail, resetCode, resetNewPassword)
                                        }
                                    },
                                )
                                Spacer(Modifier.height(6.dp))
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .pressable { loginMode = LoginSubMode.Password; clearFeedback() }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    Text("← パスワードログインに戻る", color = c.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    } else {
                        // ── REGISTER TAB ──
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(c.surfaceContainer)
                                .padding(3.dp),
                        ) {
                            SmallModeChip(
                                text = "メール認証",
                                selected = regMode == RegisterSubMode.Email,
                                modifier = Modifier.weight(1f),
                                onClick = { regMode = RegisterSubMode.Email; clearFeedback() },
                            )
                            SmallModeChip(
                                text = "パスキー",
                                selected = regMode == RegisterSubMode.Passkey,
                                modifier = Modifier.weight(1f),
                                onClick = { regMode = RegisterSubMode.Passkey; clearFeedback() },
                            )
                            SmallModeChip(
                                text = "IDのみ",
                                selected = regMode == RegisterSubMode.Local,
                                modifier = Modifier.weight(1f),
                                onClick = { regMode = RegisterSubMode.Local; clearFeedback() },
                            )
                        }

                        Spacer(Modifier.height(14.dp))

                        if (regMode == RegisterSubMode.Email) {
                            EmailWithCodeButtonRow(
                                email = regEmail,
                                onEmailChange = { regEmail = it; error = null },
                                placeholder = "メールアドレス",
                                cooldownSec = codeCooldownSec,
                                sending = sendingCode,
                                onSendCode = { requestEmailCode(regEmail, "register") },
                            )
                            Spacer(Modifier.height(10.dp))
                            AuthField(
                                value = regEmailCode,
                                onValueChange = { regEmailCode = it.filter(Char::isDigit).take(6); error = null },
                                placeholder = "メールに届いた6桁の認証コード",
                                icon = Icons.Rounded.Pin,
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Next,
                            )
                            Spacer(Modifier.height(10.dp))
                        }

                        AuthField(
                            value = regTekuzoneId,
                            onValueChange = { regTekuzoneId = it; error = null },
                            placeholder = "てくぞねID（半角英数字・例: my_id）",
                            icon = Icons.Rounded.Person,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Next,
                        )

                        idAvailabilityHint?.let { (available, msg) ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                msg,
                                color = if (available) Color(0xFF2E7D32) else c.error,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                            )
                        }

                        Spacer(Modifier.height(10.dp))

                        AuthField(
                            value = regDisplayName,
                            onValueChange = { regDisplayName = it; error = null },
                            placeholder = "表示名・ニックネーム（任意・日本語OK）",
                            icon = Icons.Rounded.Badge,
                            keyboardType = KeyboardType.Text,
                            imeAction = if (regMode == RegisterSubMode.Passkey) ImeAction.Done else ImeAction.Next,
                        )

                        if (regMode != RegisterSubMode.Passkey) {
                            Spacer(Modifier.height(10.dp))
                            AuthPasswordField(
                                value = regPassword,
                                onValueChange = { regPassword = it; error = null },
                                placeholder = "パスワード（6文字以上）",
                                visible = showRegPassword,
                                onToggleVisible = { showRegPassword = !showRegPassword },
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        when (regMode) {
                            RegisterSubMode.Email -> {
                                PrimaryAuthButton(
                                    text = "メール認証して新規登録",
                                    enabled = regEmail.isNotBlank() && regEmailCode.length >= 4 && regTekuzoneId.isNotBlank() && regPassword.length >= 6 && !loading,
                                    loading = loading,
                                    onClick = {
                                        runAuthAction {
                                            Account.registerWithEmail(
                                                email = regEmail,
                                                code = regEmailCode,
                                                tekuzoneId = regTekuzoneId,
                                                displayName = regDisplayName,
                                                password = regPassword,
                                            )
                                        }
                                    },
                                )
                                Spacer(Modifier.height(10.dp))
                                PasskeyActionCard(
                                    text = "メール認証 ＋ パスキー作成で登録",
                                    enabled = regEmail.isNotBlank() && regEmailCode.length >= 4 && regTekuzoneId.isNotBlank() && !loading,
                                    onClick = {
                                        val act = context.findSignInActivity()
                                        if (act == null) {
                                            error = "アクティビティを取得できませんでした"
                                        } else {
                                            runAuthAction {
                                                Account.registerWithPasskey(
                                                    activity = act,
                                                    tekuzoneId = regTekuzoneId,
                                                    displayName = regDisplayName,
                                                    email = regEmail,
                                                    emailCode = regEmailCode,
                                                )
                                            }
                                        }
                                    },
                                )
                            }

                            RegisterSubMode.Passkey -> {
                                PasskeyActionCard(
                                    text = "パスキーを作成して新規登録",
                                    enabled = regTekuzoneId.isNotBlank() && !loading,
                                    onClick = {
                                        val act = context.findSignInActivity()
                                        if (act == null) {
                                            error = "アクティビティを取得できませんでした"
                                        } else {
                                            runAuthAction {
                                                Account.registerWithPasskey(
                                                    activity = act,
                                                    tekuzoneId = regTekuzoneId,
                                                    displayName = regDisplayName,
                                                )
                                            }
                                        }
                                    },
                                )
                            }

                            RegisterSubMode.Local -> {
                                PrimaryAuthButton(
                                    text = "ID・パスワードで新規登録",
                                    enabled = regTekuzoneId.isNotBlank() && regPassword.length >= 6 && !loading,
                                    loading = loading,
                                    onClick = {
                                        runAuthAction {
                                            Account.registerLocal(
                                                tekuzoneId = regTekuzoneId,
                                                displayName = regDisplayName,
                                                password = regPassword,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(22.dp))

                    // Divider
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HorizontalDivider(Modifier.weight(1f), color = c.outlineVariant)
                        Text(
                            "または外部連携でログイン",
                            color = c.onSurfaceVariant,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 14.dp),
                        )
                        HorizontalDivider(Modifier.weight(1f), color = c.outlineVariant)
                    }

                    Spacer(Modifier.height(16.dp))

                    // SSO Buttons
                    SsoButton(
                        text = "Techniclip.com でログイン",
                        containerColor = c.surfaceContainerHigh,
                        contentColor = c.onSurface,
                        onClick = { webUrl = "${TekuZone.BASE_URL}/login" },
                    )
                    Spacer(Modifier.height(10.dp))
                    SsoButton(
                        text = "Discord でログイン",
                        containerColor = Color(0xFF5865F2),
                        contentColor = Color.White,
                        onClick = { webUrl = "${TekuZone.BASE_URL}/auth/discord/login" },
                    )
                    Spacer(Modifier.height(10.dp))
                    SsoButton(
                        text = "オトマッド・サイトでログイン",
                        containerColor = c.surfaceContainerHighest,
                        contentColor = c.onSurface,
                        onClick = { webUrl = "${TekuZone.BASE_URL}/auth/otomad/login" },
                    )

                    Spacer(Modifier.height(18.dp))
                }
            }
        }
    }
}

@Composable
private fun SegmentedPill(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier
            .height(42.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) c.primary else Color.Transparent)
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) c.onPrimary else c.onSurfaceVariant,
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

@Composable
private fun SmallModeChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier
            .height(36.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (selected) c.secondaryContainer else Color.Transparent)
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) c.onSecondaryContainer else c.onSurfaceVariant,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun PasskeyActionCard(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (enabled) c.secondaryContainer else c.surfaceContainerHighest)
            .border(1.dp, c.primary.copy(alpha = if (enabled) 0.45f else 0.15f), RoundedCornerShape(22.dp))
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Rounded.Fingerprint,
            contentDescription = null,
            tint = if (enabled) c.primary else c.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            color = if (enabled) c.onSecondaryContainer else c.onSurfaceVariant,
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

@Composable
private fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: ImageVector,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(c.surfaceContainerHigh)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    color = c.onSurfaceVariant,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = c.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                cursorBrush = SolidColor(c.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = imeAction,
                ),
                keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun EmailWithCodeButtonRow(
    email: String,
    onEmailChange: (String) -> Unit,
    placeholder: String,
    cooldownSec: Int,
    sending: Boolean,
    onSendCode: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AuthField(
            value = email,
            onValueChange = onEmailChange,
            placeholder = placeholder,
            icon = Icons.Rounded.Mail,
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        val canSend = email.isNotBlank() && !sending && cooldownSec == 0
        Box(
            Modifier
                .height(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (canSend) c.primaryContainer else c.surfaceContainerHighest)
                .pressable(enabled = canSend, onClick = onSendCode)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = when {
                    sending -> "送信中"
                    cooldownSec > 0 -> "${cooldownSec}s"
                    else -> "コード送信"
                },
                color = if (canSend) c.onPrimaryContainer else c.onSurfaceVariant,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }
    }
}

@Composable
private fun AuthPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    visible: Boolean,
    onToggleVisible: () -> Unit,
    onDone: (() -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(c.surfaceContainerHigh)
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Key, null, tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    color = c.onSurfaceVariant,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                textStyle = TextStyle(
                    color = c.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                cursorBrush = SolidColor(c.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .pressable(onClick = onToggleVisible),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                contentDescription = null,
                tint = c.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun PrimaryAuthButton(
    text: String,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(27.dp))
            .background(if (enabled) c.primary else c.surfaceContainerHighest)
            .pressable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = c.onPrimary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(24.dp),
            )
        } else {
            Text(
                text,
                color = if (enabled) c.onPrimary else c.onSurfaceVariant,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }
    }
}

@Composable
private fun SsoButton(
    text: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(containerColor)
            .pressable(onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = contentColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Account button in the headers: the avatar when signed in, a person icon otherwise. */
@Composable
fun AccountAction(onClick: () -> Unit) {
    val profile = Account.profile
    if (profile == null) {
        CircleAction(Icons.Rounded.AccountCircle, stringResource(R.string.ui_sign_in), onClick)
    } else {
        Box(Modifier.size(44.dp).pressable(onClick = onClick), contentAlignment = Alignment.Center) {
            ChannelAvatar(profile.avatar, profile.name, size = 40)
        }
    }
}
