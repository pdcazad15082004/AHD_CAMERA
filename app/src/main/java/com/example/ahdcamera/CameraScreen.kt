package com.example.ahdcamera

import android.content.Context
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.VideoView
import java.util.Locale
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ahdcamera.ui.theme.AHDCameraTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class CameraMode { PHOTO, VIDEO }

data class AhdChannelOption(
    val label: String,
    val csiNum: Int,
    val channel: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sharedPrefs = remember { context.getSharedPreferences("ahd_rec_prefs", Context.MODE_PRIVATE) }

    val externalHelper = remember { ExternalCameraHelper(context) }
    val recorderHelper = remember { AhdRecorderHelper(context) }
    val galleryManager = remember { MediaGalleryManager(context) }

    val ahdOptions = remember {
        listOf(
            AhdChannelOption("AHD 1 (Channel 0)", csiNum = 0, channel = 0),
            AhdChannelOption("AHD 2 (Channel 1)", csiNum = 0, channel = 1),
            AhdChannelOption("AHD 3 (Channel 2)", csiNum = 0, channel = 2),
            AhdChannelOption("AHD 4 (Channel 3)", csiNum = 0, channel = 3)
        )
    }

    var selectedAhdIndex by remember { mutableIntStateOf(sharedPrefs.getInt("ahd_channel", 1)) }
    val activeAhdOption = ahdOptions[selectedAhdIndex % ahdOptions.size]

    var cameraMode by remember { mutableStateOf(CameraMode.PHOTO) }

    var selectedResolution by remember {
        mutableStateOf(ResolutionOption.values()[sharedPrefs.getInt("resolution", 0).coerceIn(0, 3)])
    }
    var selectedFps by remember {
        mutableStateOf(FpsOption.values()[sharedPrefs.getInt("fps", 0).coerceIn(0, FpsOption.values().size - 1)])
    }
    var selectedCodec by remember {
        mutableStateOf(CodecOption.values()[sharedPrefs.getInt("codec", 0).coerceIn(0, 1)])
    }

    val defaultCameraDetails = remember(activeAhdOption, selectedResolution) {
        val ahdLabel = "AHD ${activeAhdOption.channel + 1} (Channel ${activeAhdOption.channel}, CSI ${activeAhdOption.csiNum})"
        "External Tablet Cam ($ahdLabel) Active (${selectedResolution.width}x${selectedResolution.height})"
    }

    var statusText by remember { mutableStateOf(defaultCameraDetails) }
    var surfaceViewRef by remember { mutableStateOf<SurfaceView?>(null) }

    val isRecording by AhdRecordService.isRecording.collectAsState()
    val recordingTimerSeconds by AhdRecordService.elapsedSeconds.collectAsState()
    val segmentCount by AhdRecordService.segmentCount.collectAsState()
    val serviceStatusMessage by AhdRecordService.statusMessage.collectAsState()

    // Sync status text with service status updates
    LaunchedEffect(serviceStatusMessage) {
        if (serviceStatusMessage.isNotEmpty()) {
            statusText = serviceStatusMessage
        }
    }


    // Revert status text to camera details after 5 seconds if a temporary file name or message is shown
    LaunchedEffect(statusText, defaultCameraDetails, isRecording) {
        if (!isRecording && statusText != defaultCameraDetails && statusText.isNotEmpty()) {
            delay(5000L)
            if (!isRecording) {
                statusText = defaultCameraDetails
            }
        }
    }

    var mediaList by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var previewMediaIndex by remember { mutableIntStateOf(-1) }
    
    // Dialog & Dropdown Expansion States
    var showSettingsDialog by remember { mutableStateOf(false) }
    var ahdDropdownExpanded by remember { mutableStateOf(false) }

    var resDropdownInDialog by remember { mutableStateOf(false) }
    var codecDropdownInDialog by remember { mutableStateOf(false) }
    var fpsDropdownInDialog by remember { mutableStateOf(false) }

    fun refreshGallery() {
        scope.launch {
            mediaList = galleryManager.loadRecentMedia()
        }
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                refreshGallery()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        refreshGallery()
    }

    // Refresh media gallery when a new 5-minute segment finishes or recording stops
    LaunchedEffect(segmentCount, isRecording) {
        refreshGallery()
    }

    LaunchedEffect(mediaList) {
        if (mediaList.isEmpty()) {
            previewMediaIndex = -1
        } else if (previewMediaIndex >= mediaList.size) {
            previewMediaIndex = (mediaList.size - 1).coerceAtLeast(0)
        }
    }

    val latestMedia = mediaList.firstOrNull()

    // Outer container with systemBarsPadding to keep all controls inside safe drawing bounds
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {

        // -------------------------------------------------------------
        // Main Camera Preview Surface
        // -------------------------------------------------------------
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    surfaceViewRef = this
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            externalHelper.startPreview(
                                surfaceView = this@apply,
                                csiNum = activeAhdOption.csiNum,
                                channel = activeAhdOption.channel,
                                width = selectedResolution.width,
                                height = selectedResolution.height,
                                onStatus = { statusText = it }
                            )
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            externalHelper.stopPreview(activeAhdOption.channel)
                        }
                    })
                }
            }
        )

        // -------------------------------------------------------------
        // Top Left Control Bar (AHD Selector & Settings Button)
        // -------------------------------------------------------------
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Standalone AHD Channel Selector
            Box {
                Surface(
                    onClick = { ahdDropdownExpanded = true },
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF0284C7).copy(alpha = 0.9f),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = activeAhdOption.label,
                            fontSize = 13.sp,
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Select AHD Channel",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                DropdownMenu(
                    expanded = ahdDropdownExpanded,
                    onDismissRequest = { ahdDropdownExpanded = false },
                    modifier = Modifier.background(Color(0xFF1E293B))
                ) {
                    ahdOptions.forEachIndexed { idx, option ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = option.label,
                                    color = if (idx == selectedAhdIndex) Color(0xFF38BDF8) else Color.White,
                                    fontSize = 13.sp
                                )
                            },
                            onClick = {
                                if (isRecording) {
                                    statusText = "Stopping & saving video before channel switch..."
                                    AhdRecordService.stopRecording(context)
                                }

                                selectedAhdIndex = idx
                                sharedPrefs.edit().putInt("ahd_channel", idx).apply()
                                ahdDropdownExpanded = false

                                surfaceViewRef?.let { sView ->
                                    externalHelper.stopPreview()
                                    externalHelper.startPreview(
                                        surfaceView = sView,
                                        csiNum = option.csiNum,
                                        channel = option.channel,
                                        width = selectedResolution.width,
                                        height = selectedResolution.height,
                                        onStatus = { statusText = it }
                                    )
                                }
                            },
                            leadingIcon = {
                                if (idx == selectedAhdIndex) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color(0xFF38BDF8),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        )
                    }
                }
            }

            // Settings Gear Icon Button
            Surface(
                onClick = { showSettingsDialog = true },
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.75f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                shadowElevation = 4.dp
            ) {
                Box(
                    modifier = Modifier.padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Camera Settings",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Recording Status Badge (Left-Aligned next to Settings)
            if (isRecording) {
                val mins = recordingTimerSeconds / 60
                val secs = recordingTimerSeconds % 60
                val timerString = String.format(Locale.getDefault(), "%02d:%02d", mins, secs)

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFDC2626),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(Color.White, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val segText = if (segmentCount > 1) " (Seg #$segmentCount)" else ""
                        Text(
                            text = "REC $timerString$segText",
                            color = Color.White,
                            fontSize = 13.sp,
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // Top Right Status Toast Banner
        // -------------------------------------------------------------
        if (statusText.isNotEmpty()) {
            Surface(
                color = Color.Black.copy(alpha = 0.8f),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 16.dp)
            ) {
                Text(
                    text = statusText,
                    color = Color(0xFFFDE047),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // -------------------------------------------------------------
        // End Center (Right Center): Main Camera Shutter Click Button
        // -------------------------------------------------------------
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 28.dp)
        ) {
            if (cameraMode == CameraMode.PHOTO) {
                // Photo Shutter Button (White Ring & Camera Icon)
                IconButton(
                    onClick = {
                        val sView = surfaceViewRef ?: return@IconButton
                        statusText = "Capturing Photo..."
                        recorderHelper.capturePhoto(
                            externalHelper = externalHelper,
                            surfaceView = sView,
                            resolution = selectedResolution,
                            onComplete = { success, msg ->
                                statusText = msg
                                if (success) refreshGallery()
                            }
                        )
                    },
                    modifier = Modifier
                        .size(58.dp)
                        .background(Color.White.copy(alpha = 0.95f), CircleShape)
                        .border(3.dp, Color(0xFF0284C7), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "Take Photo",
                        tint = Color.Black,
                        modifier = Modifier.size(32.dp)
                    )
                }
            } else {
                // Video Record / Stop Button (Red Ring & Circle/Stop Square)
                IconButton(
                    onClick = {
                        if (!isRecording) {
                            statusText = "Starting continuous recording (5-min loop)..."
                            AhdRecordService.startRecording(
                                context = context,
                                channel = activeAhdOption.channel,
                                csiNum = activeAhdOption.csiNum,
                                width = selectedResolution.width,
                                height = selectedResolution.height,
                                fps = selectedFps.fps,
                                mimeType = selectedCodec.mimeType
                            )
                        } else {
                            statusText = "Stopping recording & saving video..."
                            AhdRecordService.stopRecording(context)
                        }
                    },
                    modifier = Modifier
                        .size(52.dp)
                        .background(if (isRecording) Color(0xFFDC2626) else Color.White, CircleShape)
                        .border(3.dp, if (isRecording) Color.White else Color(0xFFDC2626), CircleShape)
                ) {
                    if (isRecording) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(Color.White, RoundedCornerShape(4.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(Color(0xFFDC2626), CircleShape)
                        )
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // Bottom Start (Bottom Left): Circular Media Preview Button
        // -------------------------------------------------------------
        Surface(
            onClick = {
                if (mediaList.isNotEmpty()) {
                    previewMediaIndex = 0
                } else {
                    statusText = "No captured photos or videos yet"
                }
            },
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.75f),
            border = androidx.compose.foundation.BorderStroke(
                2.dp,
                if (latestMedia?.type == MediaType.VIDEO) Color(0xFFDC2626) else Color.White.copy(alpha = 0.8f)
            ),
            shadowElevation = 6.dp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 24.dp, bottom = 20.dp)
                .size(56.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (latestMedia != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(latestMedia.uriPath)
                            .apply {
                                if (latestMedia.type == MediaType.VIDEO) {
                                    videoFrameMillis(1000)
                                }
                            }
                            .crossfade(true)
                            .build(),
                        contentDescription = "Gallery Preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )

                    if (latestMedia.type == MediaType.VIDEO) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                .padding(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.Collections,
                        contentDescription = "Gallery",
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }

        // -------------------------------------------------------------
        // Bottom Center: Mode Selector Pill (VIDEO | PHOTO)
        // -------------------------------------------------------------
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color.Black.copy(alpha = 0.75f),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
            shadowElevation = 6.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp)
        ) {
            Row(
                modifier = Modifier.padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // VIDEO Mode Tab
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (cameraMode == CameraMode.VIDEO) Color(0xFFDC2626) else Color.Transparent)
                        .clickable { cameraMode = CameraMode.VIDEO }
                        .padding(horizontal = 18.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "VIDEO",
                        color = if (cameraMode == CameraMode.VIDEO) Color.White else Color(0xFF94A3B8),
                        fontSize = 13.sp,
                        style = MaterialTheme.typography.titleSmall
                    )
                }

                // PHOTO Mode Tab
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (cameraMode == CameraMode.PHOTO) Color(0xFF0284C7) else Color.Transparent)
                        .clickable {
                            if (cameraMode != CameraMode.PHOTO) {
                                if (isRecording) {
                                    statusText = "Stopping & saving video..."
                                    AhdRecordService.stopRecording(context)
                                }
                                cameraMode = CameraMode.PHOTO
                            }
                        }
                        .padding(horizontal = 18.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "PHOTO",
                        color = if (cameraMode == CameraMode.PHOTO) Color.White else Color(0xFF94A3B8),
                        fontSize = 13.sp,
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
        }

        // -------------------------------------------------------------
        // Settings Modal Dialog
        // -------------------------------------------------------------
        if (showSettingsDialog) {
            Dialog(onDismissRequest = { showSettingsDialog = false }) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF1E293B),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .widthIn(max = 380.dp)
                        .fillMaxWidth(0.92f)
                        .padding(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(22.dp)
                                )
                                Text(
                                    text = "Camera Settings",
                                    color = Color.White,
                                    fontSize = 17.sp,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            IconButton(
                                onClick = { showSettingsDialog = false },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

                        // Setting 1: Resolution
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Resolution",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp,
                                style = MaterialTheme.typography.labelMedium
                            )
                            Box {
                                Surface(
                                    onClick = { resDropdownInDialog = true },
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFF334155),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Resolution: ${selectedResolution.label}",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = "Select Resolution",
                                            tint = Color.White
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = resDropdownInDialog,
                                    onDismissRequest = { resDropdownInDialog = false },
                                    modifier = Modifier
                                        .background(Color(0xFF0F172A))
                                        .widthIn(min = 200.dp, max = 300.dp)
                                ) {
                                    ResolutionOption.values().forEachIndexed { idx, res ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = res.label,
                                                    color = if (res == selectedResolution) Color(0xFF38BDF8) else Color.White,
                                                    fontSize = 13.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            },
                                            onClick = {
                                                selectedResolution = res
                                                sharedPrefs.edit().putInt("resolution", idx).apply()
                                                resDropdownInDialog = false

                                                surfaceViewRef?.let { sView ->
                                                    externalHelper.stopPreview()
                                                    externalHelper.startPreview(
                                                        surfaceView = sView,
                                                        csiNum = activeAhdOption.csiNum,
                                                        channel = activeAhdOption.channel,
                                                        width = res.width,
                                                        height = res.height,
                                                        onStatus = { statusText = it }
                                                    )
                                                }
                                            },
                                            leadingIcon = {
                                                if (res == selectedResolution) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = Color(0xFF38BDF8),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Setting 2: Format / Codec
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Format (Video Codec)",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp,
                                style = MaterialTheme.typography.labelMedium
                            )
                            Box {
                                Surface(
                                    onClick = { codecDropdownInDialog = true },
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFF334155),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Format: ${selectedCodec.label}",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = "Select Format",
                                            tint = Color.White
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = codecDropdownInDialog,
                                    onDismissRequest = { codecDropdownInDialog = false },
                                    modifier = Modifier
                                        .background(Color(0xFF0F172A))
                                        .widthIn(min = 200.dp, max = 300.dp)
                                ) {
                                    CodecOption.values().forEachIndexed { idx, codec ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = codec.label,
                                                    color = if (codec == selectedCodec) Color(0xFF38BDF8) else Color.White,
                                                    fontSize = 13.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            },
                                            onClick = {
                                                selectedCodec = codec
                                                sharedPrefs.edit().putInt("codec", idx).apply()
                                                codecDropdownInDialog = false
                                            },
                                            leadingIcon = {
                                                if (codec == selectedCodec) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = Color(0xFF38BDF8),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Setting 3: FPS
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Frame Rate",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp,
                                style = MaterialTheme.typography.labelMedium
                            )
                            Box {
                                Surface(
                                    onClick = { fpsDropdownInDialog = true },
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFF334155),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "FPS: ${selectedFps.label}",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = "Select FPS",
                                            tint = Color.White
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = fpsDropdownInDialog,
                                    onDismissRequest = { fpsDropdownInDialog = false },
                                    modifier = Modifier
                                        .background(Color(0xFF0F172A))
                                        .widthIn(min = 200.dp, max = 300.dp)
                                ) {
                                    FpsOption.values().forEachIndexed { idx, fps ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = fps.label,
                                                    color = if (fps == selectedFps) Color(0xFF38BDF8) else Color.White,
                                                    fontSize = 13.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            },
                                            onClick = {
                                                selectedFps = fps
                                                sharedPrefs.edit().putInt("fps", idx).apply()
                                                fpsDropdownInDialog = false
                                            },
                                            leadingIcon = {
                                                if (fps == selectedFps) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = Color(0xFF38BDF8),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // Fullscreen Media Viewer Dialog with Touch-Scrubbing Video Player & Controls
        // -------------------------------------------------------------
        if (previewMediaIndex in mediaList.indices) {
            val item = mediaList[previewMediaIndex]
            Dialog(onDismissRequest = { previewMediaIndex = -1 }) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF1E293B),
                    modifier = Modifier
                        .widthIn(max = 440.dp)
                        .fillMaxWidth(0.95f)
                        .padding(4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Dialog Header: File Name & Item Index Counter
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${previewMediaIndex + 1} / ${mediaList.size} — ${item.name}",
                                color = Color.White,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { previewMediaIndex = -1 },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Media Container Area
                        if (item.type == MediaType.VIDEO) {
                            CustomVideoPlayer(
                                item = item,
                                onPrev = if (previewMediaIndex < mediaList.size - 1) { { previewMediaIndex++ } } else null,
                                onNext = if (previewMediaIndex > 0) { { previewMediaIndex-- } } else null
                            )
                        } else {
                            // Photo Viewer
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black),
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(item.uriPath)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = item.name,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )

                                // Previous Item Arrow (<)
                                if (previewMediaIndex < mediaList.size - 1) {
                                    IconButton(
                                        onClick = { previewMediaIndex++ },
                                        modifier = Modifier
                                            .align(Alignment.CenterStart)
                                            .padding(start = 6.dp)
                                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                            .size(34.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ChevronLeft,
                                            contentDescription = "Previous",
                                            tint = Color.White
                                        )
                                    }
                                }

                                // Next Item Arrow (>)
                                if (previewMediaIndex > 0) {
                                    IconButton(
                                        onClick = { previewMediaIndex-- },
                                        modifier = Modifier
                                            .align(Alignment.CenterEnd)
                                            .padding(end = 6.dp)
                                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                            .size(34.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ChevronRight,
                                            contentDescription = "Next",
                                            tint = Color.White
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Size: ${item.size / 1024} KB",
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                            Text(
                                text = if (item.type == MediaType.VIDEO) "Video (MP4)" else "Photo (JPEG)",
                                color = if (item.type == MediaType.VIDEO) Color(0xFFF87171) else Color(0xFF38BDF8),
                                fontSize = 11.sp,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CustomVideoPlayer(
    item: MediaItem,
    onPrev: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null
) {
    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isUserDraggingSlider by remember { mutableStateOf(false) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }

    LaunchedEffect(videoViewRef, item.uriPath) {
        while (true) {
            videoViewRef?.let { vv ->
                if (!isUserDraggingSlider) {
                    currentPositionMs = vv.currentPosition.toLong().coerceAtLeast(0L)
                    val dur = vv.duration.toLong()
                    if (dur > 0L) durationMs = dur
                }
                isPlaying = vv.isPlaying
            }
            delay(200)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Video View Frame Box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        videoViewRef = this
                        if (item.uriPath.startsWith("content://")) {
                            setVideoURI(Uri.parse(item.uriPath))
                        } else {
                            setVideoPath(item.uriPath)
                        }
                        setOnPreparedListener { mp ->
                            mp.isLooping = true
                            durationMs = mp.duration.toLong()
                            start()
                            isPlaying = true
                        }
                    }
                },
                update = { videoView ->
                    videoViewRef = videoView
                    if (item.uriPath.startsWith("content://")) {
                        videoView.setVideoURI(Uri.parse(item.uriPath))
                    } else {
                        videoView.setVideoPath(item.uriPath)
                    }
                    videoView.start()
                    isPlaying = true
                },
                modifier = Modifier.fillMaxSize()
            )

            // Previous Item Arrow (<) over video frame
            if (onPrev != null) {
                IconButton(
                    onClick = onPrev,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 6.dp)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        .size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ChevronLeft,
                        contentDescription = "Previous",
                        tint = Color.White
                    )
                }
            }

            // Next Item Arrow (>) over video frame
            if (onNext != null) {
                IconButton(
                    onClick = onNext,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 6.dp)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        .size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Next",
                        tint = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Controls bar BELOW the video frame (not overlaying video)
        Surface(
            color = Color(0xFF0F172A),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Play / Pause Button
                IconButton(
                    onClick = {
                        videoViewRef?.let { vv ->
                            if (vv.isPlaying) {
                                vv.pause()
                                isPlaying = false
                            } else {
                                vv.start()
                                isPlaying = true
                            }
                        }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White
                    )
                }

                // Current Elapsed Time Counter (e.g. 00:04)
                val currentSecs = (currentPositionMs / 1000).coerceAtLeast(0)
                val curMins = currentSecs / 60
                val curSecs = currentSecs % 60
                val curTimeString = String.format(Locale.getDefault(), "%02d:%02d", curMins, curSecs)

                Text(
                    text = curTimeString,
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )

                // Interactive Touch Scrubbing Slider
                val sliderValue = if (durationMs > 0L) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                Slider(
                    value = sliderValue,
                    onValueChange = { newValue ->
                        isUserDraggingSlider = true
                        currentPositionMs = (newValue * durationMs).toLong()
                    },
                    onValueChangeFinished = {
                        isUserDraggingSlider = false
                        videoViewRef?.seekTo(currentPositionMs.toInt())
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF38BDF8),
                        activeTrackColor = Color(0xFF0284C7),
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.weight(1f)
                )

                // Total End Duration Counter (e.g. 01:25)
                val durSecsTotal = (durationMs / 1000).coerceAtLeast(0)
                val durMins = durSecsTotal / 60
                val durSecs = durSecsTotal % 60
                val durTimeString = String.format(Locale.getDefault(), "%02d:%02d", durMins, durSecs)

                Text(
                    text = durTimeString,
                    color = Color.Gray,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}

// Android Studio Jetpack Compose IDE Preview
@Preview(
    name = "AHD Camera Screen Landscape Preview",
    showBackground = true,
    device = "spec:width=1280dp,height=720dp,orientation=landscape,dpi=240"
)
@Composable
fun CameraScreenPreview() {
    AHDCameraTheme {
        CameraScreen()
    }
}
