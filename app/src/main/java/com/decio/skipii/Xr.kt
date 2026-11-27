package com.decio.skipii

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.Window
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.spatial.Orbiter
import androidx.xr.compose.spatial.OrbiterPosition
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialCurvedRow
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.SpatialSpacer
import androidx.xr.compose.subspace.draw.alpha
import androidx.xr.compose.subspace.draw.scale
import androidx.xr.compose.subspace.layout.SpatialAlignment
import androidx.xr.compose.subspace.layout.SpatialRoundedCornerShape
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.offset
import androidx.xr.compose.subspace.layout.resizable
import androidx.xr.compose.subspace.layout.width
import androidx.xr.compose.unit.DpVolumeOffset
import androidx.xr.scenecore.SpatialEnvironment
import androidx.xr.scenecore.scene

// ── Android XR Spatial Dimensions (32 dp panel radius per Android XR visual guidelines) ──
// Sized so panels + outside Orbiters sit comfortably inside the headset's central FOV without edge clipping.

internal val XrBrowsePanelWidth = 1040.dp
internal val XrBrowsePanelHeight = 760.dp
internal val XrPlayerPanelWidth = 640.dp
internal val XrPlayerPanelHeight = 720.dp
internal val XrCompanionPanelWidth = 310.dp
internal val XrCinemaPanelWidth = 1120.dp
internal val XrCinemaPanelHeight = 630.dp
internal val XrShortsPanelWidth = 440.dp
internal val XrShortsPanelHeight = 760.dp
private val XrCurveRadius = 960.dp
private val XrPanelShape = SpatialRoundedCornerShape(CornerSize(32.dp))
private val XrNavOrbiterShape = SpatialRoundedCornerShape(CornerSize(36.dp))
private val XrPillOrbiterShape = SpatialRoundedCornerShape(CornerSize(50))
private val XrCardOrbiterShape = SpatialRoundedCornerShape(CornerSize(24.dp))

/**
 * Header pill shown in Android XR Home Space so the user can enter Full Space with one tap.
 */
@Composable
fun XrEnterFullSpacePill(modifier: Modifier = Modifier) {
    val session = LocalSession.current ?: return
    val c = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .height(44.dp)
            .pressable { runCatching { session.scene.requestFullSpace() } }
            .clip(CircleShape)
            .background(c.primaryContainer)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Rounded.ViewInAr, contentDescription = null, tint = c.onPrimaryContainer, modifier = Modifier.size(18.dp))
        Text(
            text = stringResource(R.string.xr_full_space),
            color = c.onPrimaryContainer,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
        )
    }
}

private enum class XrBottomMode { Watch, Cinema, Mini }
private enum class XrCompanionKind { Watch, ShortsComments }

/**
 * Full Space spatial shell for Android XR:
 * Uses ONE persistent [SpatialCurvedRow] + ONE persistent main [SpatialPanel] across Browse,
 * Watch, Shorts, and Cinema modes so switching between Watch <-> Home/MiniPlayer NEVER tears
 * down the PanelEntity or flashes blank. Panel dimensions, companion panel depth/opacity, and
 * Orbiters animate smoothly in place.
 */
@Composable
fun XrSpatialShell(
    selectedTab: Tab,
    tabs: List<Tab>,
    onSelectTab: (Tab) -> Unit,
    player: PlayerState,
    shorts: ShortsState,
    hasMini: Boolean,
    playerOpen: Boolean,
    playerReveal: Float,
    showFullscreen: Boolean,
    settingsOpen: Boolean,
    signingIn: Boolean,
    openChannel: String?,
    onOpenVideo: (VideoItem) -> Unit,
    onToggleFullscreen: () -> Unit,
    onExitFullscreen: () -> Unit,
    mainContent: @Composable () -> Unit,
) {
    val session = LocalSession.current
    val caps = LocalSpatialCapabilities.current
    val reduced = rememberReducedMotion()
    var theaterDim by rememberSaveable { mutableStateOf(false) }

    // Apply passthrough dimming when requested in Full Space and restore cleanly on exit.
    LaunchedEffect(session, caps.isPassthroughControlEnabled, theaterDim, showFullscreen) {
        val env = session?.scene?.spatialEnvironment ?: return@LaunchedEffect
        if (!caps.isPassthroughControlEnabled) return@LaunchedEffect
        runCatching {
            env.preferredPassthroughOpacity = when {
                theaterDim -> 0.15f
                showFullscreen -> 0.35f
                else -> SpatialEnvironment.NO_PASSTHROUGH_OPACITY_PREFERENCE
            }
        }
    }
    DisposableEffect(session) {
        onDispose {
            runCatching {
                session?.scene?.spatialEnvironment?.preferredPassthroughOpacity =
                    SpatialEnvironment.NO_PASSTHROUGH_OPACITY_PREFERENCE
            }
        }
    }

    val requestHomeSpace = {
        runCatching { session?.scene?.requestHomeSpace() }
        Unit
    }
    val canDim = caps.isPassthroughControlEnabled
    val inShortsSpatial = selectedTab == Tab.Shorts && openChannel == null && !settingsOpen && !signingIn
    val inWatchSpatial = playerOpen && !showFullscreen && !settingsOpen && !signingIn && !inShortsSpatial

    // Smoothly animate the single persistent main SpatialPanel between mode sizes.
    val sizeSpec = if (reduced) snap<Dp>() else spring(dampingRatio = 0.88f, stiffness = 340f)
    val targetWidth = when {
        showFullscreen -> XrCinemaPanelWidth
        inShortsSpatial -> XrShortsPanelWidth
        inWatchSpatial -> XrPlayerPanelWidth
        else -> XrBrowsePanelWidth
    }
    val targetHeight = when {
        showFullscreen -> XrCinemaPanelHeight
        inShortsSpatial -> XrShortsPanelHeight
        inWatchSpatial -> XrPlayerPanelHeight
        else -> XrBrowsePanelHeight
    }
    val panelWidth by animateDpAsState(targetWidth, sizeSpec, label = "xrPanelWidth")
    val panelHeight by animateDpAsState(targetHeight, sizeSpec, label = "xrPanelHeight")

    // Right companion panel (Watch Related/Comments or Shorts Comments): keep last kind while fading out.
    val desiredCompanionKind = when {
        inWatchSpatial -> XrCompanionKind.Watch
        inShortsSpatial && shorts.commentsOpen -> XrCompanionKind.ShortsComments
        else -> null
    }
    var lastCompanionKind by remember { mutableStateOf(XrCompanionKind.Watch) }
    if (desiredCompanionKind != null) lastCompanionKind = desiredCompanionKind

    val companionProgress by animateFloatAsState(
        targetValue = if (desiredCompanionKind != null) 1f else 0f,
        animationSpec = if (reduced) snap() else spring(dampingRatio = 0.9f, stiffness = 360f),
        label = "xrCompanionProgress",
    )

    val bottomMode = when {
        settingsOpen || signingIn || inShortsSpatial || player.current == null -> null
        showFullscreen -> XrBottomMode.Cinema
        playerOpen -> XrBottomMode.Watch
        hasMini -> XrBottomMode.Mini
        else -> null
    }

    Subspace {
        SpatialCurvedRow(
            curveRadius = XrCurveRadius,
            verticalAlignment = SpatialAlignment.CenterVertically,
        ) {
            SpatialPanel(
                modifier = SubspaceModifier
                    .width(panelWidth)
                    .height(panelHeight)
                    .movable()
                    .resizable(),
                shape = XrPanelShape,
            ) {
                mainContent()

                // Persistent Start NavRail Orbiter (never recreated between Browse <-> Watch <-> Shorts).
                if (!showFullscreen && !settingsOpen && !signingIn) {
                    XrStartNavOrbiter(
                        selected = selectedTab,
                        tabs = tabs,
                        onSelect = onSelectTab,
                    )
                }

                // Persistent Top-End Control Orbiter across all modes.
                Orbiter(
                    position = OrbiterPosition.TopEnd(
                        verticalEdgeAlignment = OrbiterPosition.EdgeAlignment.Outside,
                        horizontalEdgeAlignment = OrbiterPosition.EdgeAlignment.Inside,
                        offset = DpVolumeOffset(y = 20.dp),
                    ),
                    shape = XrPillOrbiterShape,
                ) {
                    XrTopControlOrbiterContent(
                        canCinema = showFullscreen || (inWatchSpatial && !AudioOnly.enabled),
                        isCinema = showFullscreen,
                        canDim = canDim,
                        theaterDim = theaterDim,
                        onToggleCinema = if (showFullscreen) onExitFullscreen else onToggleFullscreen,
                        onToggleDim = { theaterDim = !theaterDim },
                        onRequestHomeSpace = requestHomeSpace,
                    )
                }

                // Persistent Bottom Orbiter: smoothly morphs between Playback Controls <-> MiniPlayer!
                if (bottomMode != null) {
                    Orbiter(
                        position = OrbiterPosition.BottomCenter(
                            verticalEdgeAlignment = OrbiterPosition.EdgeAlignment.Outside,
                            offset = DpVolumeOffset(y = (-20).dp),
                        ),
                        shape = if (bottomMode == XrBottomMode.Mini) XrCardOrbiterShape else XrPillOrbiterShape,
                    ) {
                        AnimatedContent(
                            targetState = bottomMode,
                            transitionSpec = {
                                if (reduced) fadeIn(snap()) togetherWith fadeOut(snap())
                                else (fadeIn(tween(220)) + scaleIn(tween(240, easing = Motion.Emphasized), initialScale = 0.94f)) togetherWith
                                    (fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.96f))
                            },
                            label = "xrBottomOrbiter",
                        ) { mode ->
                            when (mode) {
                                XrBottomMode.Cinema -> XrPlaybackOrbiterContent(
                                    player = player,
                                    isCinema = true,
                                    onToggleFullscreen = onExitFullscreen,
                                )
                                XrBottomMode.Watch -> XrPlaybackOrbiterContent(
                                    player = player,
                                    isCinema = false,
                                    onToggleFullscreen = onToggleFullscreen,
                                )
                                XrBottomMode.Mini -> Box(Modifier.width(420.dp)) {
                                    MiniPlayer(
                                        state = player,
                                        onExpand = { player.expanded = true },
                                        // Hand off SharedVideoTexture cleanly once PlayerScreen finishes fading out.
                                        showVideo = !playerOpen && playerReveal < 0.08f,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Smoothly entering/exiting right Companion SpatialPanel.
            if (desiredCompanionKind != null || companionProgress > 0.02f) {
                val p = companionProgress.coerceIn(0f, 1f)
                val compAlpha = ((p - 0.25f) / 0.75f).coerceIn(0f, 1f)
                val compWidth = (XrCompanionPanelWidth * p).coerceAtLeast(64.dp)
                val spacerWidth = (16.dp * p).coerceAtLeast(1.dp)
                val compHeight = if (lastCompanionKind == XrCompanionKind.ShortsComments) XrShortsPanelHeight else XrPlayerPanelHeight
                val compScale = 0.92f + 0.08f * compAlpha
                val compDepth = (-44).dp * (1f - compAlpha)

                SpatialSpacer(SubspaceModifier.width(spacerWidth))
                SpatialPanel(
                    modifier = SubspaceModifier
                        .width(compWidth)
                        .height(compHeight)
                        .offset(z = compDepth)
                        .scale(compScale)
                        .alpha(compAlpha)
                        .movable()
                        .resizable(),
                    shape = XrPanelShape,
                ) {
                    // Keep inner layout at full companion width so text never squishes while fading/collapsing.
                    Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.CenterStart) {
                        Box(Modifier.requiredWidth(XrCompanionPanelWidth).fillMaxHeight()) {
                            when (lastCompanionKind) {
                                XrCompanionKind.Watch -> XrPlayerCompanionPanel(
                                    player = player,
                                    onOpen = onOpenVideo,
                                )
                                XrCompanionKind.ShortsComments -> XrShortsCommentsPanel(
                                    state = shorts.comments,
                                    onClose = { shorts.commentsOpen = false },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun XrStartNavOrbiter(
    selected: Tab,
    tabs: List<Tab>,
    onSelect: (Tab) -> Unit,
) {
    Orbiter(
        position = OrbiterPosition.CenterStart(
            horizontalEdgeAlignment = OrbiterPosition.EdgeAlignment.Outside,
            offset = DpVolumeOffset(x = (-20).dp),
        ),
        shape = XrNavOrbiterShape,
    ) {
        XrNavRailOrbiterContent(
            selected = selected,
            tabs = tabs,
            onSelect = onSelect,
        )
    }
}

@Composable
internal fun XrNavRailOrbiterContent(
    selected: Tab,
    tabs: List<Tab>,
    onSelect: (Tab) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(36.dp),
        color = c.surfaceContainerHigh.copy(alpha = 0.96f),
        tonalElevation = 6.dp,
        modifier = Modifier.border(1.dp, c.outlineVariant.copy(alpha = 0.35f), RoundedCornerShape(36.dp)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tabs.forEach { tab ->
                XrOrbiterNavItem(tab = tab, selected = tab == selected) { onSelect(tab) }
            }
        }
    }
}

@Composable
private fun XrOrbiterNavItem(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val reduced = rememberReducedMotion()
    val on by animateFloatAsState(
        if (selected) 1f else 0f,
        if (reduced) snap() else spring(dampingRatio = 0.72f, stiffness = 650f),
        label = "xrRailTab",
    )
    val ink by animateColorAsState(if (selected) c.onPrimary else c.onSurfaceVariant, label = "xrRailInk")
    Column(
        modifier = Modifier
            .width(82.dp)
            .pressable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 58x40 pill meets Android XR minimum 48-56 dp gaze/pinch target sizing comfortably.
        Box(Modifier.size(width = 58.dp, height = 40.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(width = 58.dp, height = 40.dp)
                    .graphicsLayer {
                        alpha = on
                        scaleX = 0.65f + 0.35f * on
                        scaleY = 0.85f + 0.15f * on
                    }
                    .clip(CircleShape)
                    .background(c.primary),
            )
            Icon(
                imageVector = if (selected) tab.selectedIcon else tab.icon,
                contentDescription = tab.label,
                tint = ink,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = tab.label,
            fontSize = 10.5.sp,
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
            color = if (selected) c.onSurface else c.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun XrTopControlOrbiterContent(
    canCinema: Boolean,
    isCinema: Boolean,
    canDim: Boolean,
    theaterDim: Boolean,
    onToggleCinema: () -> Unit,
    onToggleDim: () -> Unit,
    onRequestHomeSpace: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = c.surfaceContainerHigh.copy(alpha = 0.96f),
        tonalElevation = 6.dp,
        modifier = Modifier.border(1.dp, c.outlineVariant.copy(alpha = 0.35f), CircleShape),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AnimatedVisibility(
                visible = canCinema,
                enter = expandHorizontally(tween(220, easing = Motion.Emphasized)) + fadeIn(tween(180)),
                exit = shrinkHorizontally(tween(180, easing = Motion.Emphasized)) + fadeOut(tween(130)),
            ) {
                XrOrbiterChip(
                    icon = if (isCinema) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                    label = stringResource(if (isCinema) R.string.xr_exit_cinema else R.string.xr_cinema_mode),
                    active = isCinema,
                    onClick = onToggleCinema,
                )
            }
            if (canDim) {
                XrOrbiterChip(
                    icon = if (theaterDim) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                    label = stringResource(R.string.xr_theater_dim),
                    active = theaterDim,
                    onClick = onToggleDim,
                )
            }
            XrOrbiterChip(
                icon = Icons.Rounded.Window,
                label = stringResource(R.string.xr_home_space),
                active = false,
                onClick = onRequestHomeSpace,
            )
        }
    }
}

@Composable
private fun XrOrbiterChip(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (active) c.primary else c.surfaceContainerHighest, label = "xrChipBg")
    val ink by animateColorAsState(if (active) c.onPrimary else c.onSurface, label = "xrChipInk")
    Row(
        modifier = Modifier
            .height(44.dp)
            .pressable(onClick = onClick)
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        Text(
            text = label,
            color = ink,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun XrPlaybackOrbiterContent(
    player: PlayerState,
    isCinema: Boolean,
    onToggleFullscreen: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val item = player.current ?: return
    val hasDuration = player.durationMs >= 1_000L
    val progress = if (hasDuration) (player.positionMs.toFloat() / player.durationMs).coerceIn(0f, 1f) else 0f
    val seekDeltaMs = SeekStep.ms

    fun seekBy(deltaMs: Long) {
        val ctrl = player.controller ?: return
        val maxPos = player.durationMs.coerceAtLeast(0L)
        val target = (ctrl.currentPosition + deltaMs).let { if (maxPos > 0L) it.coerceIn(0L, maxPos) else it.coerceAtLeast(0L) }
        ctrl.seekTo(target)
    }

    Surface(
        shape = CircleShape,
        color = c.surfaceContainerHigh.copy(alpha = 0.96f),
        tonalElevation = 8.dp,
        modifier = Modifier.border(1.dp, c.outlineVariant.copy(alpha = 0.35f), CircleShape),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Audio-only toggle
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .pressable { AudioOnly.set(!AudioOnly.enabled) }
                    .clip(CircleShape)
                    .background(if (AudioOnly.enabled) c.primary else c.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Headphones,
                    contentDescription = null,
                    tint = if (AudioOnly.enabled) c.onPrimary else c.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
            // Seek back
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .pressable { seekBy(-seekDeltaMs) }
                    .clip(CircleShape)
                    .background(c.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(seekIcon(forward = false), contentDescription = seekLabel(-1), tint = c.onSurface, modifier = Modifier.size(24.dp))
            }
            // Play / Pause
            MorphPlayButton(
                playing = player.isPlaying,
                buffering = player.isBuffering,
                size = 54.dp,
                container = c.primary,
                content = c.onPrimary,
                enabled = true,
                onClick = player::togglePlay,
            )
            // Seek forward
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .pressable { seekBy(seekDeltaMs) }
                    .clip(CircleShape)
                    .background(c.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(seekIcon(forward = true), contentDescription = seekLabel(1), tint = c.onSurface, modifier = Modifier.size(24.dp))
            }
            // Title + live progress bar
            Column(
                modifier = Modifier.width(220.dp).padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = c.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(c.primary.copy(alpha = 0.22f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress)
                            .clip(CircleShape)
                            .background(c.primary),
                    )
                }
                val durationText = if (hasDuration) {
                    "${formatDuration(player.positionMs)} / ${formatDuration(player.durationMs)}"
                } else {
                    formatDuration(player.positionMs)
                }
                Text(
                    text = durationText,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            // Cinema / Exit Cinema button
            if (!AudioOnly.enabled) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .pressable(onClick = onToggleFullscreen)
                        .clip(CircleShape)
                        .background(if (isCinema) c.primary else c.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isCinema) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                        contentDescription = stringResource(if (isCinema) R.string.xr_exit_cinema else R.string.xr_cinema_mode),
                        tint = if (isCinema) c.onPrimary else c.onSurface,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

/**
 * Right-hand companion SpatialPanel while watching a video in Android XR Full Space:
 * lets users browse related videos ("関連動画") or read & post comments ("コメント")
 * right next to the playing video without covering it.
 */
@Composable
private fun XrPlayerCompanionPanel(
    player: PlayerState,
    onOpen: (VideoItem) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    // 0 = Related videos, 1 = Comments
    var page by rememberSaveable(player.current?.id) { mutableIntStateOf(0) }
    LaunchedEffect(player.sheet) {
        if (player.sheet == PlayerSheet.Comments) {
            page = 1
            player.sheet = null
        }
    }
    Column(Modifier.fillMaxSize().background(c.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .clip(CircleShape)
                .background(c.surfaceContainerHigh)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            XrCompanionTab(
                label = stringResource(R.string.xr_tab_related),
                selected = page == 0,
                modifier = Modifier.weight(1f),
                onClick = { page = 0 },
            )
            val commentsLabel = if (player.comments.total > 0) {
                "${stringResource(R.string.xr_tab_comments)} (${formatCount(player.comments.total.toLong())})"
            } else {
                stringResource(R.string.xr_tab_comments)
            }
            XrCompanionTab(
                label = commentsLabel,
                selected = page == 1,
                modifier = Modifier.weight(1f),
                onClick = { page = 1 },
            )
        }
        when (page) {
            0 -> MoreVideosColumn(
                state = player,
                onOpen = onOpen,
                contentPadding = PaddingValues(top = 4.dp, bottom = 20.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                showTitle = false,
            )
            else -> XrCommentsPane(
                state = player.comments,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun XrCompanionTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) c.primary else Color.Transparent, label = "xrCompBg")
    val ink by animateColorAsState(if (selected) c.onPrimary else c.onSurfaceVariant, label = "xrCompInk")
    Box(
        modifier = modifier
            .height(44.dp)
            .pressable(onClick = onClick)
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = ink,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun XrShortsCommentsPanel(
    state: CommentsState,
    onClose: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(c.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.comments_title),
                fontFamily = RoundedHeavy,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 21.sp,
            )
            if (state.total > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatCount(state.total.toLong()),
                    color = c.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .pressable(onClick = onClose)
                    .clip(CircleShape)
                    .background(c.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, stringResource(R.string.comments_close), tint = c.onSurface)
            }
        }
        XrCommentsPane(state = state, modifier = Modifier.weight(1f).fillMaxWidth())
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E0A0D)
@Composable
private fun XrTopControlOrbiterPreview() {
    MaterialTheme(colorScheme = seedScheme(SEED_CREAM_ROSE, dark = true), shapes = ExpressiveShapes) {
        Box(Modifier.padding(16.dp)) {
            XrTopControlOrbiterContent(
                canCinema = true,
                isCinema = false,
                canDim = true,
                theaterDim = false,
                onToggleCinema = {},
                onToggleDim = {},
                onRequestHomeSpace = {},
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E0A0D)
@Composable
private fun XrNavRailOrbiterPreview() {
    MaterialTheme(colorScheme = seedScheme(SEED_CREAM_ROSE, dark = true), shapes = ExpressiveShapes) {
        Box(Modifier.padding(16.dp)) {
            XrNavRailOrbiterContent(
                selected = Tab.Home,
                tabs = Tab.entries,
                onSelect = {},
            )
        }
    }
}
