package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.i18n.bladeStrings
import com.example.data.model.UserProfile
import com.example.ui.components.VideoPlayerBox
import com.example.ui.components.filterOverlayColor
import com.example.ui.theme.BladeCrimson
import com.example.ui.theme.BladeCyan
import com.example.ui.theme.TextWhite
import com.example.ui.viewmodel.CreateStep
import com.example.ui.viewmodel.CreateViewModel
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun CreateScreen(
    createViewModel: CreateViewModel,
    currentUser: UserProfile?,
    onClose: () -> Unit,
    onRequireAuth: () -> Unit,
    onUploadSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by createViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val galleryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val durMs = runCatching {
                val mmr = MediaMetadataRetriever()
                mmr.setDataSource(context, uri)
                val d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 30_000L
                mmr.release()
                d
            }.getOrDefault(30_000L)
            createViewModel.onVideoSelected(uri, durMs)
        }
    }

    if (uiState.step == CreateStep.EDIT_AND_POST && uiState.selectedVideoUri != null) {
        BackHandler { createViewModel.backToCapture() }
        EditAndPublishStep(
            createViewModel = createViewModel,
            currentUser = currentUser,
            onBack = { createViewModel.backToCapture() },
            onRequireAuth = onRequireAuth,
            onUploadSuccess = onUploadSuccess,
            modifier = modifier
        )
    } else {
        CameraCaptureStep(
            createViewModel = createViewModel,
            onPickGallery = {
                galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            },
            onClose = onClose,
            modifier = modifier
        )
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun CameraCaptureStep(
    createViewModel: CreateViewModel,
    onPickGallery: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = bladeStrings()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by createViewModel.uiState.collectAsStateWithLifecycle()

    var hasPermissions by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasPermissions = grants[Manifest.permission.CAMERA] == true &&
            grants[Manifest.permission.RECORD_AUDIO] == true
    }

    var useFrontCamera by remember { mutableStateOf(false) }
    var flashEnabled by remember { mutableStateOf(false) }
    var maxDurationSec by remember { mutableIntStateOf(60) }
    var elapsedSec by remember { mutableIntStateOf(0) }
    var isRecording by remember { mutableStateOf(false) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            elapsedSec = 0
            while (isRecording && elapsedSec < maxDurationSec) {
                delay(1000)
                elapsedSec++
            }
            if (isRecording) {
                activeRecording?.stop()
                isRecording = false
            }
        }
    }

    LaunchedEffect(hasPermissions, useFrontCamera) {
        if (!hasPermissions) return@LaunchedEffect
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.SD))
                .build()
            val capture = VideoCapture.withOutput(recorder)
            videoCapture = capture

            val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            runCatching {
                cameraProvider.unbindAll()
                val cam = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                cam.cameraControl.enableTorch(flashEnabled && !useFrontCamera)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    DisposableEffect(Unit) {
        onDispose { activeRecording?.stop() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasPermissions) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )
            val tint = filterOverlayColor(uiState.selectedFilter)
            if (tint != Color.Transparent) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(tint)
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(Icons.Filled.Videocam, contentDescription = null, tint = BladeCrimson, modifier = Modifier.size(54.dp))
                Text(
                    text = "Camera & Mic Permission",
                    color = TextWhite,
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "Grant Camera and Audio access to record vertical Blades, or upload directly from your Gallery.",
                    color = TextWhite.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = {
                        permissionLauncher.launch(
                            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BladeCrimson)
                ) {
                    Text("Allow Camera & Mic")
                }
                OutlinedButton(onClick = onPickGallery) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null, tint = TextWhite)
                    Spacer(Modifier.width(8.dp))
                    Text(strings.uploadGallery, color = TextWhite)
                }
            }
        }

        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextWhite)
            }

            if (uiState.selectedSound != null) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.55f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.MusicNote, contentDescription = null, tint = BladeCyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(uiState.selectedSound!!.title, color = TextWhite, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = { flashEnabled = !flashEnabled },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                    Icon(
                        imageVector = if (flashEnabled) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                        contentDescription = "Flash",
                        tint = TextWhite
                    )
                }
                IconButton(
                    onClick = { useFrontCamera = !useFrontCamera },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                    Icon(Icons.Filled.Cameraswitch, contentDescription = "Switch Camera", tint = TextWhite)
                }
            }
        }

        // Bottom Controls: Filters, Duration, Record + Gallery
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(createViewModel.filters) { filter ->
                    val selected = uiState.selectedFilter == filter
                    Surface(
                        onClick = { createViewModel.updateFilter(filter) },
                        shape = RoundedCornerShape(50),
                        color = if (selected) BladeCrimson else Color.Black.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = filter,
                            color = TextWhite,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(15, 60, 180).forEach { sec ->
                    val label = if (sec == 180) "3m" else "${sec}s"
                    Text(
                        text = label,
                        color = if (maxDurationSec == sec) BladeCyan else TextWhite.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clickable(enabled = !isRecording) { maxDurationSec = sec }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (isRecording) {
                Text(
                    text = String.format("REC 00:%02d / 00:%02d", elapsedSec, maxDurationSec),
                    color = BladeCrimson,
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable(onClick = onPickGallery)
                        .testTag("pick_gallery_video")
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = strings.uploadGallery, tint = TextWhite)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(strings.uploadGallery, color = TextWhite, style = MaterialTheme.typography.labelSmall)
                }

                // Record Trigger
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .border(4.dp, TextWhite, CircleShape)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(if (isRecording) Color.Transparent else BladeCrimson)
                        .clickable(enabled = hasPermissions && videoCapture != null) {
                            if (isRecording) {
                                activeRecording?.stop()
                                isRecording = false
                            } else {
                                val capture = videoCapture ?: return@clickable
                                val outFile = File(context.cacheDir, "blade_rec_${System.currentTimeMillis()}.mp4")
                                val outputOptions = FileOutputOptions.Builder(outFile).build()
                                isRecording = true
                                activeRecording = capture.output
                                    .prepareRecording(context, outputOptions)
                                    .withAudioEnabled()
                                    .start(ContextCompat.getMainExecutor(context)) { event ->
                                        if (event is VideoRecordEvent.Finalize) {
                                            isRecording = false
                                            if (!event.hasError()) {
                                                createViewModel.onVideoSelected(
                                                    Uri.fromFile(outFile),
                                                    (elapsedSec * 1000L).coerceAtLeast(1500L)
                                                )
                                            }
                                        }
                                    }
                            }
                        }
                        .testTag("record_button"),
                    contentAlignment = Alignment.Center
                ) {
                    if (isRecording) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = BladeCrimson, modifier = Modifier.size(36.dp))
                    }
                }

                Spacer(Modifier.size(48.dp))
            }
        }
    }
}

@Composable
private fun EditAndPublishStep(
    createViewModel: CreateViewModel,
    currentUser: UserProfile?,
    onBack: () -> Unit,
    onRequireAuth: () -> Unit,
    onUploadSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = bladeStrings()
    val context = LocalContext.current
    val uiState by createViewModel.uiState.collectAsStateWithLifecycle()
    val uri = uiState.selectedVideoUri ?: return

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(strings.editAndPost, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.size(48.dp))
        }

        // Video Preview
        Box(
            modifier = Modifier
                .fillMaxWidth(0.56f)
                .aspectRatio(9f / 16f)
                .align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(18.dp))
        ) {
            VideoPlayerBox(
                videoUri = uri.toString(),
                isActive = true,
                filterName = uiState.selectedFilter,
                trimStartMs = uiState.trimStartMs,
                trimEndMs = uiState.trimEndMs,
                durationMs = uiState.videoDurationMs
            )
        }

        // Trim Slider
        Text(
            text = "Trim Video (${uiState.trimStartMs / 1000}s – ${uiState.trimEndMs / 1000}s)",
            style = MaterialTheme.typography.labelLarge
        )
        RangeSlider(
            value = uiState.trimStartMs.toFloat()..uiState.trimEndMs.toFloat(),
            onValueChange = { range ->
                createViewModel.updateTrimRange(range.start.toLong(), range.endInclusive.toLong())
            },
            valueRange = 0f..uiState.videoDurationMs.toFloat().coerceAtLeast(2000f)
        )

        // Filter Picker
        Text("Filter & Effect", style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(createViewModel.filters) { filter ->
                FilterChip(
                    selected = uiState.selectedFilter == filter,
                    onClick = { createViewModel.updateFilter(filter) },
                    label = { Text(filter) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BladeCrimson,
                        selectedLabelColor = TextWhite
                    )
                )
            }
        }

        // Sound Selector
        if (uiState.availableSounds.isNotEmpty()) {
            Text("Music & Sound", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = uiState.selectedSound == null,
                        onClick = { createViewModel.selectSound(null) },
                        label = { Text("Original Sound") }
                    )
                }
                items(uiState.availableSounds, key = { it.id }) { sound ->
                    FilterChip(
                        selected = uiState.selectedSound?.id == sound.id,
                        onClick = { createViewModel.selectSound(sound) },
                        label = { Text(sound.title) }
                    )
                }
            }
        }

        // Video Title
        OutlinedTextField(
            value = uiState.title,
            onValueChange = { createViewModel.updateTitle(it) },
            label = { Text(strings.videoTitleLabel) },
            placeholder = { Text(strings.videoTitlePlaceholder) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("upload_title_input")
        )

        // Caption & Hashtags
        OutlinedTextField(
            value = uiState.caption,
            onValueChange = { createViewModel.updateCaption(it) },
            label = { Text(strings.captionPlaceholder) },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("upload_caption_input")
        )

        // Location
        OutlinedTextField(
            value = uiState.location,
            onValueChange = { createViewModel.updateLocation(it) },
            label = { Text(strings.locationPlaceholder) },
            leadingIcon = { Icon(Icons.Filled.LocationOn, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Category
        Text("Category", style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(createViewModel.categories) { cat ->
                FilterChip(
                    selected = uiState.category == cat,
                    onClick = { createViewModel.updateCategory(cat) },
                    label = { Text(cat) }
                )
            }
        }

        // Privacy
        Text(strings.privacyLabel, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            createViewModel.privacies.forEach { priv ->
                FilterChip(
                    selected = uiState.privacy == priv,
                    onClick = { createViewModel.updatePrivacy(priv) },
                    label = { Text(priv) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BladeCyan.copy(alpha = 0.22f)
                    )
                )
            }
        }

        uiState.uploadError?.let { err ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("upload_error_banner")
            ) {
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        if (uiState.isCheckingSafety) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Scanning video for community safety...",
                    style = MaterialTheme.typography.labelMedium
                )
                LinearProgressIndicator(
                    color = BladeCyan,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else if (uiState.isUploading) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Uploading Blade... ${(uiState.uploadProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium
                )
                LinearProgressIndicator(
                    progress = { uiState.uploadProgress },
                    color = BladeCrimson,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Button(
            onClick = {
                if (currentUser == null) {
                    onRequireAuth()
                } else {
                    createViewModel.publish(context.contentResolver, onUploadSuccess)
                }
            },
            enabled = !uiState.isUploading && !uiState.isCheckingSafety,
            colors = ButtonDefaults.buttonColors(containerColor = BladeCrimson),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("publish_blade_button")
        ) {
            Text(
                text = when {
                    currentUser == null -> "Sign In to Publish"
                    uiState.isCheckingSafety -> "Checking Safety..."
                    uiState.isUploading -> "Uploading..."
                    else -> strings.publishBlade
                },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        }

        Text(
            text = "Adult, sexually explicit, or pornographic videos are strictly prohibited and blocked before upload.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
