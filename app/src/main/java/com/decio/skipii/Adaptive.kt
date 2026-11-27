package com.decio.skipii

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.platform.LocalSpatialConfiguration
import androidx.xr.compose.platform.SpatialConfiguration

/**
 * Big screens (Derek picked "Rail 3", with the Fold's pages swapped — option B — on 2026-11-02;
 * docs/design/large-screens-rail.html and large-screens-rail3-fold.html). A window [wide] enough
 * gets the tab rail on the left, grids, and the two-pane player. On an open book-style foldable
 * the player splits at the fold ([foldX]); half open on a table ([tabletopY]) the video takes the
 * top half and the controls the bottom half. On Android XR ([xr]), Full Space ([spatialUi]) lifts
 * navigation and playback controls into spatial Orbiters and splits watch/comments into curved SpatialPanels.
 */
@Immutable
data class WindowLayout(
    /** At least [WideWindow] across: the rail, grids, the two-pane player. */
    val wide: Boolean = false,
    /** A fold running top to bottom through a wide window, px from its left edge. */
    val foldX: Float? = null,
    /** Exact left..right px bounds of a vertical fold hinge, for hinge-aware split panes. */
    val foldBoundsX: ClosedFloatingPointRange<Float>? = null,
    /** True when a vertical fold is physically HALF_OPENED (book posture ~90°). */
    val foldHalfOpen: Boolean = false,
    /** Half open with the fold running across the window (tabletop): the fold's top and bottom, px. */
    val tabletopY: ClosedFloatingPointRange<Float>? = null,
    /** An Android TV / Google TV: driven by a remote's D-pad, never touched ([TvPlayer], Tv.kt). */
    val tv: Boolean = false,
    /** Running on an Android XR headset or wired XR glasses (Xr.kt). */
    val xr: Boolean = false,
    /** Android XR Full Space spatial UI is currently enabled (Subspace, SpatialPanel, Orbiters). */
    val spatialUi: Boolean = false,
)

/** Medium width and up (Material's window size classes): the phone layout stops here. */
val WideWindow: Dp = 600.dp

val LocalWindowLayout = compositionLocalOf { WindowLayout() }

@Composable
fun rememberWindowLayout(activity: Activity): WindowLayout {
    val config = LocalConfiguration.current
    val spatialUi = LocalSpatialCapabilities.current.isSpatialUiEnabled
    val xrFeature = LocalSpatialConfiguration.current.hasXrSpatialFeature
    val xr = remember(activity) { activity.isXrDevice() } || xrFeature
    val wide = spatialUi || config.screenWidthDp >= WideWindow.value
    val flow = remember(activity) { WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity) }
    val info by flow.collectAsState(initial = null)
    val fold = info?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull()
    val tv = remember(activity) { activity.isTv() }
    return remember(wide, fold, tv, xr, spatialUi) {
        val b = fold?.bounds
        val verticalFold = wide && !spatialUi && fold?.orientation == FoldingFeature.Orientation.VERTICAL && b != null
        WindowLayout(
            wide = wide,
            foldX = if (verticalFold) b!!.exactCenterX() else null,
            foldBoundsX = if (verticalFold) b!!.left.toFloat()..b.right.toFloat() else null,
            foldHalfOpen = verticalFold && fold?.state == FoldingFeature.State.HALF_OPENED,
            tabletopY = if (!spatialUi && fold?.state == FoldingFeature.State.HALF_OPENED && fold.orientation == FoldingFeature.Orientation.HORIZONTAL && b != null) {
                b.top.toFloat()..b.bottom.toFloat()
            } else null,
            tv = tv,
            xr = xr,
            spatialUi = spatialUi,
        )
    }
}

/** An Android XR device (headset or wired XR glasses): never portrait-locked, supports spatial UI in Full Space. */
fun Context.isXrDevice(): Boolean =
    runCatching { SpatialConfiguration.hasXrSpatialFeature(this) }.getOrDefault(false) ||
        packageManager.hasSystemFeature("android.software.xr.api.spatial") ||
        packageManager.hasSystemFeature("android.software.xr.api.openxr")

/** A television (leanback): Android TV and Google TV both say so in their UI mode. */
fun Context.isTv(): Boolean =
    (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

/** The whole screen the window is on, (short side, long side) in px: the same either way round. */
fun Activity.screenSides(): Pair<Int, Int> {
    val (w, h) = if (Build.VERSION.SDK_INT >= 30) {
        windowManager.maximumWindowMetrics.bounds.let { it.width() to it.height() }
    } else {
        android.graphics.Point().also { @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealSize(it) }.let { it.x to it.y }
    }
    return minOf(w, h) to maxOf(w, h)
}

/** The screen is turned a quarter from its natural way up (the phone held sideways). */
fun Activity.turnedSideways(): Boolean {
    val rotation = if (Build.VERSION.SDK_INT >= 30) display?.rotation else @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
    return rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
}

/**
 * A tablet or an open foldable (the screen's short side is wide-window size), a TV (540 dp tall, but it
 * never turns), or an Android XR device (spatial resizable windows): nothing is held portrait.
 */
fun Activity.onLargeScreen(): Boolean = isTv() || isXrDevice() || screenSides().first / resources.displayMetrics.density >= WideWindow.value

/** No more than 4:3 either way round (the Titan 2 Elite is 1080 × 1200, the Ikko Mind One about 1.15): fullscreen keeps the phone upright. */
val SquareScreenRatio = 4f / 3f

/**
 * A near-square phone (Unihertz Titan, keyboard phones; GitHub #38): turning it sideways gains the
 * video almost nothing, so fullscreen stays upright. The whole screen, so the bars don't count.
 */
fun Activity.onSquareScreen(): Boolean = screenSides().let { (short, long) -> short > 0 && long <= short * SquareScreenRatio + 0.5f }
