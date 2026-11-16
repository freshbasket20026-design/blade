package com.example.ui.components

import android.net.Uri
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.BladeApp
import com.example.ui.theme.BladeCrimson
import com.example.ui.theme.TextWhite
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerBox(
    videoUri: String,
    isActive: Boolean,
    filterName: String = "Normal",
    trimStartMs: Long = 0L,
    trimEndMs: Long = 0L,
    durationMs: Long = 0L,
    onDoubleTapLike: () -> Unit = {},
    onStartedPlayback: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val currentIsActive by rememberUpdatedState(isActive)
    var userPaused by remember(videoUri) { mutableStateOf(false) }
    val currentUserPaused by rememberUpdatedState(userPaused)
    var isBuffering by remember(videoUri) { mutableStateOf(true) }
    var hasError by remember(videoUri) { mutableStateOf(false) }
    var audioRecoveryAttempts by remember(videoUri) { mutableIntStateOf(0) }
    var tapHeartOffset by remember { mutableStateOf<Offset?>(null) }
    val heartScale = remember { Animatable(0f) }
    val heartAlpha = remember { Animatable(0f) }

    val exoPlayer = remember(videoUri, trimStartMs, trimEndMs, durationMs) {
        val app = context.applicationContext as? BladeApp
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)

        val upstreamFactory = DefaultDataSource.Factory(context, httpFactory)
        val extractorsFactory = DefaultExtractorsFactory()
            .setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)

        val mediaSourceFactory = if (app != null && videoUri.startsWith("http")) {
            val cacheFactory = CacheDataSource.Factory()
                .setCache(app.mediaCache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            DefaultMediaSourceFactory(cacheFactory, extractorsFactory)
        } else {
            DefaultMediaSourceFactory(upstreamFactory, extractorsFactory)
        }

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        // Optimized load control for low-end Android phones & slower mobile networks
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2000, 15000, 800, 1500)
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, false)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                val hasCustomTrim = trimStartMs > 0L ||
                    (trimEndMs > trimStartMs && durationMs > 0L && trimEndMs < durationMs - 250L)
                val itemBuilder = MediaItem.Builder().setUri(Uri.parse(videoUri))
                if (hasCustomTrim && trimEndMs > trimStartMs) {
                    itemBuilder.setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(trimStartMs)
                            .setEndPositionMs(trimEndMs)
                            .build()
                    )
                }
                setMediaItem(itemBuilder.build())
                prepare()
            }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    hasError = false
                    if (currentIsActive) onStartedPlayback()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.isAudioSinkDiscontinuityError() && audioRecoveryAttempts < 2) {
                    val attempt = audioRecoveryAttempts
                    audioRecoveryAttempts = attempt + 1
                    hasError = false
                    isBuffering = true
                    if (attempt == 1) {
                        // Disable broken audio track on second failure so video plays cleanly
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                            .build()
                    }
                    val resumePosition = exoPlayer.currentPosition.coerceAtLeast(0L)
                    exoPlayer.seekTo(resumePosition)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = currentIsActive && !currentUserPaused
                } else {
                    isBuffering = false
                    hasError = true
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    LaunchedEffect(isActive, userPaused) {
        exoPlayer.volume = if (isActive) 1f else 0f
        if (isActive && !userPaused) {
            exoPlayer.playWhenReady = true
        } else {
            exoPlayer.playWhenReady = false
        }
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> exoPlayer.playWhenReady = false
                Lifecycle.Event.ON_RESUME -> if (currentIsActive && !currentUserPaused) exoPlayer.playWhenReady = true
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(videoUri) {
                detectTapGestures(
                    onTap = {
                        userPaused = !userPaused
                    },
                    onDoubleTap = { offset ->
                        tapHeartOffset = offset
                        onDoubleTapLike()
                        scope.launch {
                            heartScale.snapTo(0.4f)
                            heartAlpha.snapTo(1f)
                            heartScale.animateTo(1.45f, tween(220))
                            heartScale.animateTo(1.1f, tween(120))
                            heartAlpha.animateTo(0f, tween(350))
                            tapHeartOffset = null
                        }
                    }
                )
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { view ->
                if (view.player !== exoPlayer) {
                    view.player = exoPlayer
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Filter tint overlay
        val tint = filterOverlayColor(filterName)
        if (tint != Color.Transparent) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(tint)
            )
        }

        if (isBuffering && !hasError) {
            CircularProgressIndicator(
                color = TextWhite.copy(alpha = 0.75f),
                strokeWidth = 2.5.dp,
                modifier = Modifier
                    .size(34.dp)
                    .align(Alignment.Center)
            )
        }

        if (hasError) {
            IconButton(
                onClick = {
                    hasError = false
                    isBuffering = true
                    audioRecoveryAttempts = 0
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = currentIsActive && !currentUserPaused
                },
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .align(Alignment.Center)
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Retry video",
                    tint = TextWhite,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = userPaused,
            enter = fadeIn(tween(120)) + scaleIn(initialScale = 0.8f),
            exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.8f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Paused",
                    tint = TextWhite.copy(alpha = 0.9f),
                    modifier = Modifier.size(44.dp)
                )
            }
        }

        // Double-tap heart burst
        tapHeartOffset?.let { pos ->
            Icon(
                imageVector = Icons.Rounded.ThumbUp,
                contentDescription = null,
                tint = BladeCrimson,
                modifier = Modifier
                    .offset {
                        IntOffset((pos.x - 90).roundToInt(), (pos.y - 90).roundToInt())
                    }
                    .size(96.dp)
                    .scale(heartScale.value)
                    .alpha(heartAlpha.value)
            )
        }
    }
}

private fun PlaybackException.isAudioSinkDiscontinuityError(): Boolean {
    if (errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
    ) {
        return true
    }
    var cur: Throwable? = this
    while (cur != null) {
        if (cur is AudioSink.UnexpectedDiscontinuityException) return true
        if (cur.message?.contains("discontinuity", ignoreCase = true) == true) return true
        cur = cur.cause
    }
    return false
}
