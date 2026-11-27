package com.decio.skipii

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import java.lang.ref.WeakReference

/** The app's single shared video surface (see SkipiiApp). */
val LocalVideoSurface = androidx.compose.runtime.staticCompositionLocalOf<@Composable (Modifier) -> Unit> { { m -> VideoSurface(null, m) } }

/** Draws nothing: where the video must not show (picture-in-picture shows it elsewhere). */
val NoVideoSurface: @Composable (Modifier) -> Unit = {}

/**
 * The one surface the decoder draws into, kept alive for the whole process.
 *
 * Why: a TextureView destroys its SurfaceTexture whenever it leaves the window (moving it
 * from the full player to the mini-player does exactly that). The decoder then loses its
 * output, is rebuilt, and waits for the next keyframe — seconds of frozen picture, the
 * "flash" when minimizing. Here the SurfaceTexture is never released (the destroy callback
 * returns false) and is handed back to whichever TextureView shows the video next, so the
 * player's surface never changes and playback never notices a move.
 */
object SharedVideoTexture {
    var texture: SurfaceTexture? = null
        private set
    var surface: Surface? = null
        private set
    /** Bumped when the surface first exists, so players can be pointed at it. */
    var version by mutableIntStateOf(0)
        private set

    /**
     * Live downsampled video frame for the 90° Tabletop glass reflection (Tabletop.kt).
     * Sampled only while [reflectionConsumers] > 0 (zero overhead outside tabletop mode) using
     * two alternating reusable bitmaps so there are no per-frame allocations.
     */
    var reflectionFrame by mutableStateOf<ImageBitmap?>(null)
        private set
    private var reflectionConsumers = 0
    private var activeView: WeakReference<TextureView>? = null
    private val reflectionBuffers = arrayOfNulls<Bitmap>(2)
    private var reflectionPixels = IntArray(0)
    private var reflectionSpare = IntArray(0)
    private var reflectionFlip = 0
    private var lastSampledMs = 0L

    fun acquireReflection() {
        reflectionConsumers++
        activeView?.get()?.let { sampleReflection(it, force = true) }
    }

    fun releaseReflection() {
        reflectionConsumers = (reflectionConsumers - 1).coerceAtLeast(0)
        if (reflectionConsumers == 0) {
            reflectionFrame = null
            reflectionPixels = IntArray(0)
            reflectionSpare = IntArray(0)
        }
    }

    internal fun onViewActive(view: TextureView) {
        activeView = WeakReference(view)
        if (reflectionConsumers > 0) sampleReflection(view, force = true)
    }

    internal fun onViewResized(view: TextureView) {
        activeView = WeakReference(view)
        if (reflectionConsumers > 0) sampleReflection(view, force = true)
    }

    internal fun onFrame(view: TextureView) {
        activeView = WeakReference(view)
        AvSync.onFrameDrawn()
        if (reflectionConsumers > 0) sampleReflection(view, force = false)
    }

    private fun sampleReflection(view: TextureView, force: Boolean) {
        if (!view.isAvailable || view.width <= 0 || view.height <= 0) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastSampledMs < 33L) return
        lastSampledMs = now
        val w = 96
        val h = (w.toLong() * view.height / view.width).toInt().coerceIn(24, 144)
        val slot = 1 - reflectionFlip
        reflectionFlip = slot
        val bmp = reflectionBuffers[slot]?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { reflectionBuffers[slot] = it }
        runCatching { view.getBitmap(bmp) }.getOrNull() ?: return
        val size = w * h
        if (reflectionPixels.size != size) {
            reflectionPixels = IntArray(size)
            reflectionSpare = IntArray(size)
        }
        softBlurBitmap(bmp, reflectionPixels, reflectionSpare, radius = 4, passes = 3)
        reflectionFrame = bmp.asImageBitmap()
    }

    fun adopt(view: TextureView, fresh: SurfaceTexture) {
        activeView = WeakReference(view)
        val kept = texture
        if (kept == null) {
            texture = fresh
            surface = Surface(fresh)
            version++
        } else if (kept !== fresh) {
            // The view made a new one on re-attach: swap ours back in (releases the new one).
            runCatching { view.setSurfaceTexture(kept) }
                .onFailure { Log.w("Skipii", "couldn't re-attach the video texture", it) }
        }
    }
}

/**
 * The video, in a TextureView this app owns. All TextureViews show [SharedVideoTexture];
 * the player's output surface is set once and never cleared while the app runs.
 */
@Composable
fun VideoSurface(player: Player?, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextureView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                // A view created after the texture exists shows it straight away.
                SharedVideoTexture.texture?.let { runCatching { setSurfaceTexture(it) } }
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                        setTransform(null)
                        SharedVideoTexture.adopt(this@apply, st)
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                        setTransform(null)
                        invalidate()
                        post { setTransform(null); invalidate() }
                        SharedVideoTexture.onViewResized(this@apply)
                    }
                    // Never let the view release it: the next view (or this one, re-attached) reuses it.
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = st !== SharedVideoTexture.texture
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = SharedVideoTexture.onFrame(this@apply)
                }
                addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                    if ((right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)) {
                        setTransform(null)
                        postInvalidateOnAnimation()
                    }
                }
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        // Before the hardware layer is built, so it adopts the kept texture.
                        setTransform(null)
                        SharedVideoTexture.onViewActive(this@apply)
                        val kept = SharedVideoTexture.texture ?: return
                        if (surfaceTexture !== kept) runCatching { setSurfaceTexture(kept) }
                        requestLayout()
                        invalidate()
                    }
                    override fun onViewDetachedFromWindow(v: View) {}
                })
            }
        },
        update = { view ->
            val kept = SharedVideoTexture.texture
            if (kept != null && view.isAvailable && view.surfaceTexture !== kept) {
                runCatching { view.setSurfaceTexture(kept) }
            }
            view.setTransform(null)
            view.requestLayout()
            view.invalidate()
        },
    )
    val version = SharedVideoTexture.version
    LaunchedEffect(player, version) {
        val surface = SharedVideoTexture.surface ?: return@LaunchedEffect
        player?.setVideoSurface(surface)
    }
}

/**
 * O(N) sliding-window separable box blur (3 passes ≈ smooth Gaussian) on a small sampled frame,
 * ensuring ultra-soft ambient reflections on all Android versions with zero per-frame allocations.
 */
internal fun softBlurBitmap(bitmap: Bitmap, pixels: IntArray, spare: IntArray, radius: Int = 4, passes: Int = 3) {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 1 || h <= 1 || pixels.size < w * h || spare.size < w * h) return
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val n = 2 * radius + 1
    repeat(passes) {
        // Horizontal pass
        for (y in 0 until h) {
            val row = y * w
            var r = 0; var g = 0; var b = 0
            for (k in -radius..radius) {
                val c = pixels[row + k.coerceIn(0, w - 1)]
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            for (x in 0 until w) {
                spare[row + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                val outC = pixels[row + (x - radius).coerceIn(0, w - 1)]
                val inC = pixels[row + (x + radius + 1).coerceIn(0, w - 1)]
                r += ((inC shr 16) and 0xFF) - ((outC shr 16) and 0xFF)
                g += ((inC shr 8) and 0xFF) - ((outC shr 8) and 0xFF)
                b += (inC and 0xFF) - (outC and 0xFF)
            }
        }
        // Vertical pass
        for (x in 0 until w) {
            var r = 0; var g = 0; var b = 0
            for (k in -radius..radius) {
                val c = spare[k.coerceIn(0, h - 1) * w + x]
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            for (y in 0 until h) {
                pixels[y * w + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                val outC = spare[(y - radius).coerceIn(0, h - 1) * w + x]
                val inC = spare[(y + radius + 1).coerceIn(0, h - 1) * w + x]
                r += ((inC shr 16) and 0xFF) - ((outC shr 16) and 0xFF)
                g += ((inC shr 8) and 0xFF) - ((outC shr 8) and 0xFF)
                b += (inC and 0xFF) - (outC and 0xFF)
            }
        }
    }
    bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
}
