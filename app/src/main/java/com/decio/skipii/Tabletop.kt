package com.decio.skipii

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Pure OLED black base behind the 90° ambient reflection and transparent Material 3 deck. */
private val DeckBlack = Color.Black
/** Storyboard frames along the scrub surface. */
private const val ScrubFrames = 10
/** Narrower than this (a Flip's 411 dp), the five tool tiles move to a second row beneath transport. */
private val OneRowMin = 620.dp

/** Subtle saturation boost (×1.18) so heavily diffused reflected light retains vivid natural hues on OLED black. */
private val ReflectionColorFilter: ColorFilter = ColorFilter.colorMatrix(
    ColorMatrix().apply { setToSaturation(1.18f) },
)

/**
 * Tabletop (90° half-folded Flex / Tabletop posture): the standing top half plays the video while
 * the resting bottom half displays a deeply blurred, exponentially light-attenuating mirror
 * reflection of the video over OLED black, topped by an airy transparent Material 3 control deck.
 * [foldTop] / [foldBottom]: the fold's edges from the window's top.
 */
@Composable
fun TabletopPlayer(player: PlayerState, foldTop: Dp, foldBottom: Dp) {
    val item = player.current ?: return
    val context = LocalContext.current
    val deckScheme = remember(ThemeSettings.seed) { schemeFor(context, ThemeSettings.seed, dark = true) }
    DisposableEffect(Unit) {
        SharedVideoTexture.acquireReflection()
        onDispose { SharedVideoTexture.releaseReflection() }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(DeckBlack)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
        // Top half: standing video screen.
        Box(Modifier.fillMaxWidth().height(foldTop), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().aspectFit(player.videoAspect)) {
                if (AudioOnly.enabled || player.hidesStaleVideo) Thumbnail(item.thumbnail, Modifier.fillMaxSize(), radius = 0)
                if (!AudioOnly.enabled) {
                    val surface = LocalVideoSurface.current
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (player.hidesStaleVideo) 0f else 1f }) {
                        surface(Modifier.fillMaxSize())
                    }
                }
                CaptionLayer(player, bottom = 10.dp)
            }
            if (player.isBuffering) {
                CircularProgressIndicator(
                    color = deckScheme.primary,
                    strokeWidth = 3.5.dp,
                    modifier = Modifier.size(40.dp),
                )
            }
        }

        // Bottom half: heavily blurred ambient reflection with progressive downward light falloff,
        // overlaid with a Material 3 transparent glass control deck.
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = foldBottom)
                .clipToBounds(),
        ) {
            TabletopReflectionSurface(
                frame = if (AudioOnly.enabled || player.hidesStaleVideo) null else SharedVideoTexture.reflectionFrame,
                fallbackThumbnail = item.thumbnail,
                aspect = player.videoAspect,
                mirrorHeight = foldTop,
                modifier = Modifier.fillMaxSize(),
            )
            MaterialTheme(colorScheme = deckScheme, shapes = ExpressiveShapes) {
                val c = MaterialTheme.colorScheme
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Ergonomic downward bias: leaves the luminous reflection zone near the 90° fold
                    // unobstructed while placing the transparent M3 deck naturally under your hands.
                    Spacer(Modifier.weight(1.35f))
                    Surface(
                        modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth(),
                        shape = RoundedCornerShape(32.dp),
                        color = c.surfaceContainerHigh.copy(alpha = 0.18f),
                        contentColor = c.onSurface,
                        border = BorderStroke(1.dp, c.outlineVariant.copy(alpha = 0.34f)),
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        0.00f to Color.White.copy(alpha = 0.08f),
                                        0.35f to Color.Transparent,
                                        1.00f to Color.Black.copy(alpha = 0.10f),
                                    ),
                                )
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                        ) {
                            var scrubMs by remember(item.id) { mutableStateOf<Long?>(null) }
                            TimeRow(player, scrubMs)
                            Spacer(Modifier.height(10.dp))
                            ScrubSurface(player, onScrub = { scrubMs = it })
                            Spacer(Modifier.height(14.dp))
                            DeckButtons(player)
                        }
                    }
                    Spacer(Modifier.weight(0.55f))
                }
            }
        }
    }
}

/**
 * Heavily blurred, natural 90° table-surface ambient reflection for the bottom half of a half-folded
 * OLED display:
 * - Vertically mirrors the standing top screen across the 90° fold crease.
 * - Combines software Gaussian pre-blur with unbounded GPU blur (`48.dp`) so light diffuses softly
 *   with zero harsh borders or distracting sharp details.
 * - Applies a true exponential downward light-attenuation mask (`BlendMode.DstIn`) so reflected
 *   light diminishes smoothly with distance from the hinge into pure OLED black at the front edge.
 */
@Composable
internal fun TabletopReflectionSurface(
    frame: ImageBitmap?,
    fallbackThumbnail: String?,
    aspect: Float,
    mirrorHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier.clipToBounds()) {
        // Offscreen compositing layer so the exponential vertical DstIn gradient directly attenuates
        // the reflected light intensity down to zero (pure OLED black) with zero gray wash.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.00f to Color.White.copy(alpha = 0.88f),
                            0.10f to Color.White.copy(alpha = 0.64f),
                            0.22f to Color.White.copy(alpha = 0.42f),
                            0.38f to Color.White.copy(alpha = 0.23f),
                            0.56f to Color.White.copy(alpha = 0.10f),
                            0.74f to Color.White.copy(alpha = 0.03f),
                            0.88f to Color.White.copy(alpha = 0.006f),
                            1.00f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            // Run the unbounded GPU blur on the full-width container (outside the aspectFit/graphicsLayer
            // box) so pillarboxed 16:9 edges bloom softly into the OLED margins with zero sharp vertical
            // seams. Over-extending 24 dp above y = 0 (clipped by the outer container) keeps the blur
            // kernel filled right at the 90° fold crease so light transitions seamlessly with no dark gap.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(mirrorHeight + 24.dp)
                    .offset(y = (-24).dp)
                    .align(Alignment.TopCenter)
                    .blur(52.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .aspectFit(aspect)
                        .graphicsLayer {
                            scaleY = -1f
                            alpha = 0.86f
                        },
                ) {
                    if (frame != null) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawImage(
                                image = frame,
                                dstSize = IntSize(
                                    size.width.roundToInt().coerceAtLeast(1),
                                    size.height.roundToInt().coerceAtLeast(1),
                                ),
                                colorFilter = ReflectionColorFilter,
                                filterQuality = FilterQuality.Medium,
                            )
                        }
                    } else {
                        Thumbnail(fallbackThumbnail, Modifier.fillMaxSize(), radius = 0)
                    }
                }
            }
        }
        // Whisper-subtle specular sheen catching the physical 90° fold crease.
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.horizontalGradient(
                        0.00f to Color.Transparent,
                        0.15f to Color.White.copy(alpha = 0.10f),
                        0.50f to Color.White.copy(alpha = 0.18f),
                        0.85f to Color.White.copy(alpha = 0.10f),
                        1.00f to Color.Transparent,
                    ),
                ),
        )
    }
}

@Composable
private fun TimeRow(player: PlayerState, scrubMs: Long?) {
    val c = MaterialTheme.colorScheme
    val typo = MaterialTheme.typography
    val position = scrubMs ?: player.positionMs
    val duration = player.durationMs
    val label = player.chapterAt(position) ?: player.current?.title.orEmpty()
    val shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 1.5f), 6f)
    val timeStyle = typo.labelLarge.copy(
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.SemiBold,
        shadow = shadow,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = formatDuration(position),
            color = if (scrubMs != null) c.primary else c.onSurface,
            style = timeStyle,
        )
        Text(
            text = " / " + formatDuration(duration),
            color = c.onSurfaceVariant,
            style = timeStyle.copy(fontWeight = FontWeight.Medium),
        )
        Text(
            text = label,
            color = c.onSurface.copy(alpha = 0.86f),
            style = typo.titleSmall.copy(fontWeight = FontWeight.Medium, shadow = shadow),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
        )
        if (duration > 0) {
            Text(
                text = "−" + formatDuration((duration - position).coerceAtLeast(0L)),
                color = c.onSurfaceVariant,
                style = timeStyle.copy(fontWeight = FontWeight.Medium),
            )
        }
    }
}

/**
 * Material 3 transparent scrub surface:
 * - Without storyboards: sleek M3 Expressive translucent track slider with active pill fill and
 *   vertical capsule handle that expands while dragging.
 * - With storyboards: translucent rounded filmstrip with chapter notches and M3 vertical handle.
 */
@Composable
private fun ScrubSurface(player: PlayerState, onScrub: (Long?) -> Unit) {
    val c = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val duration = player.durationMs.coerceAtLeast(1L)
    val boards = player.details?.storyboards.orEmpty()
    val chapters = player.details?.chapters.orEmpty()
    val sheets = rememberStoryboardSheets(player)
    var width by remember { mutableStateOf(1) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    val shown = scrub ?: (player.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val lift by animateFloatAsState(if (scrub != null) 1f else 0f, label = "scrubLift")
    val hasBoards = boards.isNotEmpty()
    val trackHeight = if (hasBoards) 52.dp else 40.dp
    val boardShape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .onSizeChanged { width = it.width.coerceAtLeast(1) }
                .pointerInput(duration) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun at(x: Float) = (x / width).coerceIn(0f, 1f)
                        scrub = at(down.position.x)
                        onScrub((scrub!! * duration).toLong())
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (change.positionChange() != Offset.Zero) {
                                change.consume()
                                scrub = at(change.position.x)
                                onScrub((scrub!! * duration).toLong())
                            }
                        }
                        scrub?.let { player.seekTo((it * duration).toLong()) }
                        scrub = null
                        onScrub(null)
                    }
                }
                .then(
                    if (hasBoards) {
                        Modifier
                            .clip(boardShape)
                            .background(c.surfaceContainerHighest.copy(alpha = 0.20f))
                            .border(1.dp, c.outlineVariant.copy(alpha = 0.34f), boardShape)
                    } else {
                        Modifier
                    },
                )
                .drawWithContent {
                    if (hasBoards) {
                        drawContent()
                        val x = shown * size.width
                        drawRect(
                            Color.Black.copy(alpha = 0.56f),
                            topLeft = Offset(x, 0f),
                            size = Size(size.width - x, size.height),
                        )
                        chapters.drop(1).forEach { ch ->
                            val cx = ch.startMs.toFloat() / duration * size.width
                            drawRect(
                                DeckBlack.copy(alpha = 0.8f),
                                topLeft = Offset(cx - 1.dp.toPx(), 0f),
                                size = Size(2.dp.toPx(), size.height),
                            )
                        }
                        val handleW = 4.dp.toPx() + lift * 2.dp.toPx()
                        val handlePad = 4.dp.toPx()
                        drawRoundRect(
                            color = c.primary,
                            topLeft = Offset((x - handleW / 2f).coerceIn(0f, size.width - handleW), handlePad),
                            size = Size(handleW, size.height - handlePad * 2f),
                            cornerRadius = CornerRadius(handleW / 2f),
                        )
                    } else {
                        // Material 3 Expressive transparent slider track + vertical pill thumb handle.
                        val barH = 10.dp.toPx() + lift * 2.dp.toPx()
                        val barTop = (size.height - barH) / 2f
                        val barRadius = CornerRadius(barH / 2f)
                        val handleW = 4.dp.toPx() + lift * 2.dp.toPx()
                        val handleH = 26.dp.toPx() + lift * 6.dp.toPx()
                        val gap = 4.dp.toPx()
                        val x = (shown * size.width).coerceIn(handleW / 2f, size.width - handleW / 2f)

                        // Active translucent primary track (left of handle gap).
                        val activeEnd = (x - handleW / 2f - gap).coerceAtLeast(0f)
                        if (activeEnd > 0f) {
                            drawRoundRect(
                                color = c.primary.copy(alpha = 0.84f),
                                topLeft = Offset(0f, barTop),
                                size = Size(activeEnd, barH),
                                cornerRadius = barRadius,
                            )
                        }
                        // Inactive translucent glass track (right of handle gap).
                        val inactiveStart = (x + handleW / 2f + gap).coerceAtMost(size.width)
                        val inactiveWidth = (size.width - inactiveStart).coerceAtLeast(0f)
                        if (inactiveWidth > 0f) {
                            drawRoundRect(
                                color = c.surfaceContainerHighest.copy(alpha = 0.24f),
                                topLeft = Offset(inactiveStart, barTop),
                                size = Size(inactiveWidth, barH),
                                cornerRadius = barRadius,
                            )
                            drawRoundRect(
                                color = c.outlineVariant.copy(alpha = 0.30f),
                                topLeft = Offset(inactiveStart, barTop),
                                size = Size(inactiveWidth, barH),
                                cornerRadius = barRadius,
                                style = Stroke(width = 1.dp.toPx()),
                            )
                        }
                        // Chapter tick marks along the track.
                        chapters.drop(1).forEach { ch ->
                            val cx = ch.startMs.toFloat() / duration * size.width
                            if (kotlin.math.abs(cx - x) > handleW + gap) {
                                drawCircle(
                                    color = if (cx < x) c.onPrimary.copy(alpha = 0.5f) else c.onSurfaceVariant.copy(alpha = 0.45f),
                                    radius = 1.75.dp.toPx(),
                                    center = Offset(cx, size.height / 2f),
                                )
                            }
                        }
                        // M3 vertical pill handle.
                        drawRoundRect(
                            color = c.primary,
                            topLeft = Offset(x - handleW / 2f, (size.height - handleH) / 2f),
                            size = Size(handleW, handleH),
                            cornerRadius = CornerRadius(handleW / 2f),
                        )
                    }
                },
        ) {
            if (hasBoards) {
                Row(Modifier.fillMaxSize()) {
                    repeat(ScrubFrames) { i ->
                        StoryboardThumb(
                            sheets,
                            boards,
                            ((i + 0.5f) / ScrubFrames * duration).toLong(),
                            duration,
                            Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
        // Floating Material 3 translucent scrub-time pill above the finger.
        scrub?.let { f ->
            val ms = (f * duration).toLong()
            val label = listOfNotNull("→ " + formatDuration(ms), player.chapterAt(ms)).joinDots()
            val half = with(density) { 70.dp.toPx() }
            Surface(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (f * width - half).coerceIn(0f, (width - 2 * half).coerceAtLeast(0f)).roundToInt(),
                            with(density) { (-34).dp.roundToPx() },
                        )
                    }
                    .widthIn(min = 128.dp),
                shape = CircleShape,
                color = c.primaryContainer.copy(alpha = 0.88f),
                contentColor = c.onPrimaryContainer,
                border = BorderStroke(1.dp, c.primary.copy(alpha = 0.45f)),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFeatureSettings = "tnum",
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun DeckButtons(player: PlayerState) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= OneRowMin) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SpeedKey(player)
                QualityKey(player)
                Spacer(Modifier.width(4.dp))
                Transport(player)
                Spacer(Modifier.width(4.dp))
                CaptionsKey(player)
                AudioOnlyKey(player)
                NextKey(player)
            }
        } else {
            // Narrow foldable (Flip): centered transport row above 5 compact transparent M3 tiles.
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Transport(player)
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SpeedKey(player, tall = true)
                    QualityKey(player, tall = true)
                    CaptionsKey(player, tall = true)
                    AudioOnlyKey(player, tall = true)
                    NextKey(player, tall = true)
                }
            }
        }
    }
}

/**
 * Material 3 Expressive transparent transport cluster (−10 · Play/Pause · +10):
 * Play/Pause morphs smoothly between a rounded squircle while playing and a full pill while paused,
 * tinted with translucent `primaryContainer` glass so the ambient reflection softly shines through.
 */
@Composable
private fun Transport(player: PlayerState) {
    val c = MaterialTheme.colorScheme
    val corner by animateDpAsState(
        targetValue = if (player.isPlaying) 20.dp else 32.dp,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
        label = "playMorph",
    )
    DeckIconButton(seekIcon(forward = false), seekLabel(-1)) { player.seekBy(-SeekStep.ms) }
    Surface(
        modifier = Modifier
            .size(width = 76.dp, height = 60.dp)
            .pressable { player.togglePlay() },
        shape = RoundedCornerShape(corner),
        color = c.primaryContainer.copy(alpha = 0.48f),
        contentColor = c.onPrimaryContainer,
        border = BorderStroke(1.dp, c.primary.copy(alpha = 0.56f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(if (player.isPlaying) R.string.bar_pause else R.string.bar_play),
                modifier = Modifier.size(34.dp),
            )
        }
    }
    DeckIconButton(seekIcon(forward = true), seekLabel(1)) { player.seekBy(SeekStep.ms) }
}

@Composable
private fun RowScope.SpeedKey(player: PlayerState, tall: Boolean = false) =
    DeckKeyButton(
        label = stringResource(R.string.bar_tool_speed),
        on = player.speed != 1f,
        tall = tall,
        onClick = { player.sheet = PlayerSheet.Speed },
    ) {
        Text(
            text = PlaybackSpeed.label(player.speed) + "×",
            color = it,
            style = MaterialTheme.typography.labelLarge.copy(
                fontFeatureSettings = "tnum",
                fontWeight = FontWeight.Bold,
            ),
        )
    }

@Composable
private fun RowScope.QualityKey(player: PlayerState, tall: Boolean = false) =
    DeckKeyButton(
        label = stringResource(R.string.bar_tool_quality),
        tall = tall,
        onClick = { player.sheet = PlayerSheet.Quality },
    ) {
        Text(
            text = QualityPreference.height?.let { h -> "${h}p" } ?: stringResource(R.string.bar_quality_auto),
            color = it,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        )
    }

@Composable
private fun RowScope.CaptionsKey(player: PlayerState, tall: Boolean = false) {
    val available = player.captions.tracks.isNotEmpty() || player.captions.on
    DeckKeyButton(
        label = stringResource(R.string.bar_tool_captions),
        on = player.captions.on,
        enabled = available,
        tall = tall,
        onClick = {
            player.captions.turn(true)
            player.sheet = PlayerSheet.Captions
        },
    ) { Icon(Icons.Rounded.ClosedCaption, null, tint = it, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun RowScope.AudioOnlyKey(player: PlayerState, tall: Boolean = false) =
    DeckKeyButton(
        label = stringResource(R.string.bar_audio_only),
        on = AudioOnly.enabled,
        tall = tall,
        onClick = { player.setAudioOnly(!AudioOnly.enabled) },
    ) { Icon(Icons.Rounded.Headphones, null, tint = it, modifier = Modifier.size(22.dp)) }

@Composable
private fun RowScope.NextKey(player: PlayerState, tall: Boolean = false) {
    val hasNext = player.queue.isNotEmpty() || !player.details?.related.isNullOrEmpty()
    DeckKeyButton(
        label = stringResource(R.string.bar_tool_next),
        enabled = hasNext,
        tall = tall,
        onClick = { player.playNextUp() },
    ) { Icon(Icons.Rounded.SkipNext, null, tint = it, modifier = Modifier.size(24.dp)) }
}

/**
 * Material 3 transparent tonal/outlined action tile: uses translucent `surfaceContainerHighest`
 * when off and translucent `secondaryContainer` with primary outline when toggled on.
 */
@Composable
private fun RowScope.DeckKeyButton(
    label: String,
    on: Boolean = false,
    enabled: Boolean = true,
    tall: Boolean = false,
    onClick: () -> Unit,
    symbol: @Composable (Color) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val container = if (on) c.secondaryContainer.copy(alpha = 0.44f) else c.surfaceContainerHighest.copy(alpha = 0.16f)
    val border = if (on) c.primary.copy(alpha = 0.48f) else c.outlineVariant.copy(alpha = 0.30f)
    val contentColor = if (on) c.onSecondaryContainer else c.onSurface
    val labelColor = if (on) c.onSecondaryContainer else c.onSurfaceVariant
    Surface(
        modifier = Modifier
            .weight(1f)
            .height(if (tall) 60.dp else 56.dp)
            .pressable(enabled = enabled, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.36f },
        shape = RoundedCornerShape(18.dp),
        color = container,
        contentColor = contentColor,
        border = BorderStroke(1.dp, border),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) { symbol(contentColor) }
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                color = labelColor,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Material 3 transparent circular tonal icon button for −10 / +10 seek controls. */
@Composable
private fun DeckIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .size(54.dp)
            .pressable(onClick = onClick),
        shape = CircleShape,
        color = c.surfaceContainerHighest.copy(alpha = 0.18f),
        contentColor = c.onSurface,
        border = BorderStroke(1.dp, c.outlineVariant.copy(alpha = 0.34f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, description, modifier = Modifier.size(26.dp))
        }
    }
}
