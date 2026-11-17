package com.decio.skipii

import android.annotation.SuppressLint
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import kotlinx.coroutines.launch

/**
 * Native Material 3 Expressive sign-in screen for TechniClip Zone (てくぞね),
 * with native ID/Password login plus full-screen SSO WebView support (Techniclip.com, Discord, Otomad).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SignInScreen(onDone: () -> Unit, onCancel: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Null = Native login screen; non-null = SSO / Registration URL in WebView
    var webUrl by remember { mutableStateOf<String?>(null) }

    fun submitNativeLogin() {
        val u = username.trim()
        val p = password
        if (u.isEmpty() || p.isEmpty() || loading) return
        focusManager.clearFocus()
        loading = true
        error = null
        scope.launch {
            runCatching { Account.loginWithPassword(u, p) }
                .onSuccess {
                    loading = false
                    onDone()
                }
                .onFailure {
                    loading = false
                    error = it.message ?: "IDまたはパスワードが正しくありません"
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
                            error = null
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
                    stringResource(R.string.ui_sign_in_title),
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
            // ── Full-screen SSO / Registration WebView ──
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        val cookies = CookieManager.getInstance()
                        cookies.removeAllCookies(null)
                        cookies.setAcceptCookie(true)
                        WebView(context).apply {
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
                            settings.userAgentString = WebSettings.getDefaultUserAgent(context).replace("; wv", "")
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
                                    // Ensure mobile layout fits 100% width if on /loginpage
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
            // ── Native Material 3 Expressive Login Form ──
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = 460.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(R.drawable.app_icon),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "てくにくりっぷ ぞーん",
                        fontFamily = RoundedHeavy,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 28.sp,
                        letterSpacing = (-0.8).sp,
                        color = c.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "てくぞねIDとパスワード、または連携アカウントでログイン",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = c.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(28.dp))

                    // Username / TekuZone ID Input
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(c.surfaceContainerHigh)
                            .padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Person, null, tint = c.onSurfaceVariant, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Box(Modifier.weight(1f)) {
                            if (username.isEmpty()) {
                                Text(
                                    "てくぞねID / ユーザー名",
                                    color = c.onSurfaceVariant,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            BasicTextField(
                                value = username,
                                onValueChange = { username = it; error = null },
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = c.onSurface,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                cursorBrush = SolidColor(c.primary),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Next,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Password Input
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(c.surfaceContainerHigh)
                            .padding(start = 18.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Key, null, tint = c.onSurfaceVariant, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Box(Modifier.weight(1f)) {
                            if (password.isEmpty()) {
                                Text(
                                    "パスワード",
                                    color = c.onSurfaceVariant,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            BasicTextField(
                                value = password,
                                onValueChange = { password = it; error = null },
                                singleLine = true,
                                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                textStyle = TextStyle(
                                    color = c.onSurface,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                cursorBrush = SolidColor(c.primary),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done,
                                ),
                                keyboardActions = KeyboardActions(onDone = { submitNativeLogin() }),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .pressable { showPassword = !showPassword },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = null,
                                tint = c.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    if (error != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            error.orEmpty(),
                            color = c.error,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(Modifier.height(18.dp))

                    // Primary Login Button
                    val canSubmit = username.isNotBlank() && password.isNotEmpty() && !loading
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(if (canSubmit) c.primary else c.surfaceContainerHighest)
                            .pressable(enabled = canSubmit) { submitNativeLogin() },
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
                                "ログイン",
                                color = if (canSubmit) c.onPrimary else c.onSurfaceVariant,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))

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

                    Spacer(Modifier.height(20.dp))

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

                    Spacer(Modifier.height(20.dp))

                    // Registration link
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .pressable { webUrl = "${TekuZone.BASE_URL}/loginpage" }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(
                            "アカウントをお持ちでない方（新規登録）",
                            color = c.primary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }
            }
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
            .height(54.dp)
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
