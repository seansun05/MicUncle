package com.sean.micuncle.ui.home

import android.app.Activity
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sean.micuncle.R
import com.sean.micuncle.data.Song
import com.sean.micuncle.player.KtvPlayer
import com.sean.micuncle.ui.theme.*

import androidx.compose.ui.viewinterop.AndroidView
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.zIndex
import androidx.compose.foundation.interaction.MutableInteractionSource
import android.icu.text.Transliterator
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import java.util.Locale
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel()
) {
    val allSongs by viewModel.allSongs.collectAsState()
    val pendingDetectedSong by viewModel.pendingDetectedSong.collectAsState()
    val pendingDetectedSongsBatch by viewModel.pendingDetectedSongsBatch.collectAsState()
    val refreshResultMessage by viewModel.refreshResultMessage.collectAsState()
    val operationMessage by viewModel.operationMessage.collectAsState()
    val favoriteSongs by viewModel.favoriteSongs.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val queuedSongs by viewModel.queuedSongs.collectAsState()
    val currentScreen by viewModel.currentScreen.collectAsState()
    val context = LocalContext.current
    val orderedSongIds = remember(currentSong, queuedSongs) {
        buildSet {
            currentSong?.let { add(it.id) }
            addAll(queuedSongs.map { it.id })
        }
    }
    var isVideoFullscreen by remember { mutableStateOf(false) }

    val isPlaying by viewModel.ktvPlayer.isPlaying.collectAsState()
    val isOriginalVocal by viewModel.ktvPlayer.isOriginalVocal.collectAsState()
    val isMuted by viewModel.ktvPlayer.isMuted.collectAsState()
    var showQueueSheet by remember { mutableStateOf(false) }
    val queueSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val ktvPlayer = viewModel.ktvPlayer
    val density = LocalDensity.current
    var fullscreenControlsVisible by remember { mutableStateOf(false) }
    var fullscreenInteractionTick by remember { mutableIntStateOf(0) }
    var sliderPositionMs by remember { mutableLongStateOf(0L) }
    var sliderDurationMs by remember { mutableLongStateOf(0L) }
    var isSeekingProgress by remember { mutableStateOf(false) }
    var thumbnailOffsetPx by remember { mutableStateOf(IntOffset.Zero) }
    var thumbnailSizePx by remember { mutableStateOf(IntSize.Zero) }

    DisposableEffect(context, currentSong != null) {
        val activity = context as? Activity
        if (activity != null && currentSong != null) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(isVideoFullscreen, isSeekingProgress) {
        if (!isVideoFullscreen) return@LaunchedEffect
        while (isVideoFullscreen) {
            if (!isSeekingProgress) {
                sliderPositionMs = ktvPlayer.currentPosition()
                sliderDurationMs = ktvPlayer.duration()
            }
            delay(500)
        }
    }

    LaunchedEffect(isVideoFullscreen, fullscreenControlsVisible, fullscreenInteractionTick) {
        if (!isVideoFullscreen || !fullscreenControlsVisible) return@LaunchedEffect
        delay(4000)
        fullscreenControlsVisible = false
    }

    val openFullscreen = {
        if (currentSong != null) {
            isVideoFullscreen = true
            fullscreenControlsVisible = false
            fullscreenInteractionTick++
            sliderPositionMs = ktvPlayer.currentPosition()
            sliderDurationMs = ktvPlayer.duration()
        }
    }

    val currentBackground = when (currentScreen) {
        Screen.HOME -> R.drawable.bg_ktv_home
        Screen.SONG_LIST -> R.drawable.bg_ktv_song_list
        Screen.FAVORITES -> R.drawable.bg_ktv_favorites
        Screen.PINYIN_SEARCH -> R.drawable.bg_ktv_song_list
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ScreenBackground(currentBackground) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                // 1. 中部区域：根据状态切换内容（首页改为底部小入口）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    when (currentScreen) {
                        Screen.HOME -> HomeEntryScreen(
                            onNavigateToSongList = { viewModel.navigateTo(Screen.SONG_LIST) },
                            onNavigateToFavorites = { viewModel.navigateTo(Screen.FAVORITES) },
                            onNavigateToPinyinSearch = { viewModel.navigateTo(Screen.PINYIN_SEARCH) }
                        )
                        Screen.SONG_LIST -> SongListScreen(
                            songs = allSongs,
                            favoriteSongIds = favoriteSongs.map { it.id }.toSet(),
                            orderedSongIds = orderedSongIds,
                            onSongClick = { viewModel.playSong(it) },
                            onToggleFavorite = { viewModel.toggleFavorite(it) },
                            onDeleteSong = { viewModel.deleteSong(it) },
                            onRefresh = { viewModel.refreshLocalSongs() },
                            onBack = { viewModel.navigateTo(Screen.HOME) }
                        )
                        Screen.FAVORITES -> FavoriteListScreen(
                            songs = favoriteSongs,
                            favoriteSongIds = favoriteSongs.map { it.id }.toSet(),
                            orderedSongIds = orderedSongIds,
                            onSongClick = { viewModel.playSong(it) },
                            onToggleFavorite = { viewModel.toggleFavorite(it) },
                            onDeleteSong = { viewModel.deleteSong(it) },
                            onBack = { viewModel.navigateTo(Screen.HOME) }
                        )
                        Screen.PINYIN_SEARCH -> PinyinSearchScreen(
                            songs = allSongs,
                            orderedSongIds = orderedSongIds,
                            onSongClick = { viewModel.playSong(it) },
                            onBack = { viewModel.navigateTo(Screen.HOME) }
                        )
                    }
                }

                // 2. 底部区域：控制栏（左下角保留视频缩略区域锚点）
                ControlBar(
                    currentSong = currentSong,
                    queuedCount = queuedSongs.size,
                    onPlayPause = { viewModel.playPause() },
                    onNext = { viewModel.playNext() },
                    onReplay = { viewModel.replay() },
                    onOriginalToggle = { viewModel.toggleVocal() },
                    onMuteToggle = { viewModel.toggleMute() },
                    onShowQueue = { showQueueSheet = true },
                    isPlaying = isPlaying,
                    isOriginalVocal = isOriginalVocal,
                    isMuted = isMuted,
                    showVideoThumbnail = true,
                    onVideoThumbnailPositioned = { offset, size ->
                        thumbnailOffsetPx = offset
                        thumbnailSizePx = size
                    },
                    onVideoClick = openFullscreen,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .padding(bottom = 8.dp)
                )
            }
        }

        if (currentSong != null) {
            val thumbnailModifier = if (thumbnailSizePx.width > 0 && thumbnailSizePx.height > 0) {
                Modifier
                    .offset { thumbnailOffsetPx }
                    .size(
                        width = with(density) { thumbnailSizePx.width.toDp() },
                        height = with(density) { thumbnailSizePx.height.toDp() }
                    )
            } else {
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 24.dp, bottom = 24.dp)
                    .size(width = 128.dp, height = 72.dp)
            }

            val videoModifier = if (isVideoFullscreen) {
                Modifier
                    .fillMaxSize()
                    .zIndex(3f)
            } else {
                thumbnailModifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = openFullscreen)
                    .zIndex(2f)
            }

            KtvVideoSurface(
                player = ktvPlayer,
                modifier = videoModifier
            )
        }

        if (isVideoFullscreen && currentSong != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(4f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        fullscreenControlsVisible = !fullscreenControlsVisible
                        if (fullscreenControlsVisible) {
                            fullscreenInteractionTick++
                        }
                    }
            )

            if (fullscreenControlsVisible) {
                KtvBackButton(
                    onClick = { isVideoFullscreen = false },
                    modifier = Modifier
                        .zIndex(5f)
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                )

                Surface(
                    color = Color.Black.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                    modifier = Modifier
                        .zIndex(5f)
                        .align(Alignment.TopCenter)
                        .padding(top = 20.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        ControlButton(
                            text = if (isOriginalVocal) "原唱" else "伴唱",
                            icon = Icons.Rounded.Mic,
                            onClick = {
                                fullscreenInteractionTick++
                                viewModel.toggleVocal()
                            },
                            tint = if (isOriginalVocal) KtvPrimary else KtvTextSecondary
                        )

                        IconButton(
                            onClick = {
                                fullscreenInteractionTick++
                                viewModel.playPause()
                            },
                            modifier = Modifier
                                .size(70.dp)
                                .background(Color(0xFF7A73FF), RoundedCornerShape(50))
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = "播放/暂停",
                                tint = Color.Black,
                                modifier = Modifier.size(34.dp)
                            )
                        }

                        ControlButton(
                            text = "重唱",
                            icon = Icons.Rounded.Replay,
                            onClick = {
                                fullscreenInteractionTick++
                                viewModel.replay()
                            }
                        )
                        ControlButton(
                            text = "切歌",
                            icon = Icons.Rounded.SkipNext,
                            onClick = {
                                fullscreenInteractionTick++
                                viewModel.playNext()
                            }
                        )
                        ControlButton(
                            text = "静音",
                            icon = if (isMuted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                            onClick = {
                                fullscreenInteractionTick++
                                viewModel.toggleMute()
                            },
                            tint = if (isMuted) KtvPrimary else KtvTextPrimary
                        )
                        Button(
                            onClick = {
                                fullscreenInteractionTick++
                                showQueueSheet = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("已点", color = KtvSecondary, fontSize = 13.sp)
                                Text("${queuedSongs.size}", color = KtvTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Surface(
                    color = Color.Black.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                    modifier = Modifier
                        .zIndex(5f)
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                    ) {
                        Slider(
                            value = if (sliderDurationMs > 0) {
                                sliderPositionMs.toFloat() / sliderDurationMs.toFloat()
                            } else {
                                0f
                            },
                            onValueChange = { fraction ->
                                if (sliderDurationMs <= 0) return@Slider
                                isSeekingProgress = true
                                sliderPositionMs = (fraction * sliderDurationMs)
                                    .toLong()
                                    .coerceIn(0L, sliderDurationMs)
                                fullscreenInteractionTick++
                            },
                            onValueChangeFinished = {
                                ktvPlayer.seekTo(sliderPositionMs)
                                isSeekingProgress = false
                                fullscreenInteractionTick++
                            },
                            valueRange = 0f..1f,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                                .graphicsLayer { scaleY = 1.35f }
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatDuration(sliderPositionMs),
                                color = KtvTextPrimary,
                                fontSize = 13.sp
                            )
                            Text(
                                text = formatDuration(sliderDurationMs),
                                color = KtvTextPrimary,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }

    }

    if (showQueueSheet) {
        ModalBottomSheet(
            onDismissRequest = { showQueueSheet = false },
            sheetState = queueSheetState,
            containerColor = Color(0xFF111B33),
            contentColor = KtvTextPrimary
        ) {
            QueueSheetContent(
                songs = queuedSongs,
                onDismiss = {
                    scope.launch { queueSheetState.hide() }.invokeOnCompletion {
                        showQueueSheet = false
                    }
                },
                onPlayNow = { songId ->
                    viewModel.playQueuedSongNow(songId)
                    scope.launch { queueSheetState.hide() }.invokeOnCompletion {
                        showQueueSheet = false
                    }
                },
                onRemove = { songId ->
                    viewModel.removeQueuedSong(songId)
                }
            )
        }
    }

    if (pendingDetectedSongsBatch.isNotEmpty()) {
        val previewNames = pendingDetectedSongsBatch
            .take(8)
            .joinToString(separator = "\n") { "• ${it.fileName}" }
        val remainingCount = pendingDetectedSongsBatch.size - 8
        AlertDialog(
            onDismissRequest = { viewModel.dismissBatchDetectedSongs() },
            title = { Text("发现 ${pendingDetectedSongsBatch.size} 首新歌") },
            text = {
                Text(
                    buildString {
                        append("刷新检测到以下新歌，是否全部加入歌库：\n")
                        append(previewNames)
                        if (remainingCount > 0) {
                            append("\n… 以及另外 $remainingCount 首")
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.addAllDetectedSongsToLibrary() }) {
                    Text("全部加入")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.ignoreAllDetectedSongs() }) {
                    Text("全部忽略")
                }
            }
        )
    } else if (pendingDetectedSong != null) {
        AlertDialog(
            onDismissRequest = { viewModel.ignoreDetectedSong() },
            title = { Text("发现新歌") },
            text = {
                Text(
                    "检测到新歌，是否现在加入歌库\n${pendingDetectedSong?.fileName ?: ""}"
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.addDetectedSongToLibrary() }) {
                    Text("加入歌库")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.ignoreDetectedSong() }) {
                    Text("稍后")
                }
            }
        )
    }

    if (refreshResultMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissRefreshResultMessage() },
            title = { Text("刷新结果") },
            text = { Text(refreshResultMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissRefreshResultMessage() }) {
                    Text("知道了")
                }
            }
        )
    }

    if (operationMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissOperationMessage() },
            title = { Text("操作提示") },
            text = { Text(operationMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissOperationMessage() }) {
                    Text("知道了")
                }
            }
        )
    }
}

@Composable
fun ScreenBackground(
    backgroundResId: Int,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                View(context).apply {
                    setBackgroundResource(backgroundResId)
                }
            },
            update = { view ->
                view.setBackgroundResource(backgroundResId)
            },
            modifier = Modifier.fillMaxSize()
        )
        content()
    }
}

@Composable
fun KtvVideoSurface(
    player: KtvPlayer,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        player.attachSurface(holder)
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        player.onSurfaceSizeChanged(width, height)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        player.detachSurface(holder)
                    }
                })
            }
        },
        update = { view ->
            if (view.holder.surface?.isValid == true) {
                player.onSurfaceSizeChanged(view.width, view.height)
                player.attachSurface(view.holder)
            }
        },
        modifier = modifier
    )
}

@Composable
fun KtvDynamicBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "background")
    val offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "offset"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF2E003E), // 深紫
                        Color(0xFF3D0043),
                        Color(0xFF1A0033)
                    ),
                    start = androidx.compose.ui.geometry.Offset(offset, 0f),
                    end = androidx.compose.ui.geometry.Offset(offset + 500f, 500f),
                    tileMode = TileMode.Mirror
                )
            )
    ) {
        // 可以添加一些漂浮的音符或光斑
    }
}

@Composable
fun HomeEntryScreen(
    onNavigateToSongList: () -> Unit,
    onNavigateToFavorites: () -> Unit,
    onNavigateToPinyinSearch: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EntryCard(
                title = "点歌",
                subtitle = "SONGS",
                icon = null,
                color = KtvPrimary,
                backgroundAssetPath = "file:///android_asset/huatong.png",
                showTextBadge = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                onClick = onNavigateToSongList
            )

            EntryCard(
                title = "收藏",
                subtitle = "FAVORITES",
                icon = null,
                color = KtvSecondary,
                backgroundAssetPath = "file:///android_asset/shoucang.jpeg",
                showTextBadge = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                onClick = onNavigateToFavorites
            )

            EntryCard(
                title = "搜索",
                subtitle = "PINYIN",
                icon = null,
                color = KtvAccent,
                backgroundAssetPath = "file:///android_asset/sousuo.jpeg",
                showTextBadge = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                onClick = onNavigateToPinyinSearch
            )
        }
    }
}

@Composable
fun EntryCard(
    title: String,
    subtitle: String,
    icon: ImageVector?,
    color: Color,
    backgroundAssetPath: String? = null,
    showTextBadge: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x3320304A)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            if (backgroundAssetPath != null) {
                AsyncImage(
                    model = backgroundAssetPath,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                color.copy(alpha = if (backgroundAssetPath != null) 0.15f else 0.35f),
                                Color(0x3311182C),
                                Color(0x2210182A)
                            ),
                            start = androidx.compose.ui.geometry.Offset(0f, 0f),
                            end = androidx.compose.ui.geometry.Offset(420f, 240f)
                        )
                    )
            )

            if (icon != null) {
                Box(
                    modifier = Modifier
                        .padding(start = 16.dp, top = 20.dp)
                        .size(84.dp)
                        .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        color = if (showTextBadge) Color(0xE6212D46) else Color.Transparent,
                        shape = if (showTextBadge) {
                            RoundedCornerShape(
                                topStart = 0.dp,
                                topEnd = 0.dp,
                                bottomStart = 30.dp,
                                bottomEnd = 30.dp
                            )
                        } else {
                            RoundedCornerShape(0.dp)
                        }
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = title,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.78f),
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
fun PinyinSearchScreen(
    songs: List<Song>,
    orderedSongIds: Set<String>,
    onSongClick: (Song) -> Unit,
    onBack: () -> Unit
) {
    var keyword by remember { mutableStateOf("") }
    val searchableSongs = remember(songs) {
        songs.map { song ->
            SearchableSong(
                song = song,
                titleKey = buildSearchKey(song.title),
                artistKey = buildSearchKey(song.artist)
            )
        }
    }
    val filteredSongs = remember(keyword, searchableSongs) {
        val normalizedQuery = normalizeQuery(keyword)
        if (normalizedQuery.isBlank()) {
            searchableSongs.map { it.song }
        } else {
            searchableSongs
                .filter { searchable ->
                    searchable.titleKey.contains(normalizedQuery) ||
                        searchable.artistKey.contains(normalizedQuery)
                }
                .map { it.song }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KtvBackButton(onClick = onBack)
            Spacer(modifier = Modifier.width(12.dp))
            Text("拼音搜索", color = KtvTextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(14.dp))

        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = KtvTextSecondary)
            },
            placeholder = {
                Text("输入中文、拼音或首字母，例如：zhang / zxy", color = KtvTextSecondary)
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = KtvPrimary,
                unfocusedBorderColor = Color.White.copy(alpha = 0.22f),
                focusedTextColor = KtvTextPrimary,
                unfocusedTextColor = KtvTextPrimary,
                focusedContainerColor = Color(0x3320304A),
                unfocusedContainerColor = Color(0x3320304A),
                cursorColor = KtvPrimary
            ),
            shape = RoundedCornerShape(16.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (filteredSongs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("未找到匹配歌曲", color = KtvTextSecondary, fontSize = 18.sp)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredSongs) { song ->
                    SongCard(
                        song = song,
                        isOrdered = orderedSongIds.contains(song.id),
                        onClick = { onSongClick(song) }
                    )
                }
            }
        }
    }
}

@Composable
fun SongListScreen(
    songs: List<Song>,
    favoriteSongIds: Set<String>,
    orderedSongIds: Set<String>,
    onSongClick: (Song) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    onDeleteSong: (Song) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    var keyword by remember { mutableStateOf("") }
    var isSearchSplitMode by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val searchableSongs = remember(songs) {
        songs.map { song ->
            SearchableSong(
                song = song,
                titleKey = buildSearchKey(song.title),
                artistKey = buildSearchKey(song.artist)
            )
        }
    }
    val filteredSongs = remember(keyword, searchableSongs) {
        val normalizedQuery = normalizeQuery(keyword)
        if (normalizedQuery.isBlank()) {
            searchableSongs.map { it.song }
        } else {
            searchableSongs
                .filter { searchable ->
                    searchable.titleKey.contains(normalizedQuery) ||
                        searchable.artistKey.contains(normalizedQuery)
                }
                .map { it.song }
        }
    }
    val sourceSongs = if (isSearchSplitMode) filteredSongs else songs
    var deletingSong by remember { mutableStateOf<Song?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KtvBackButton(onClick = onBack)
            Spacer(modifier = Modifier.width(12.dp))
            Text("点歌台", color = KtvTextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = { isSearchSplitMode = true },
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x3325334D))
                    .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
            ) {
                Icon(Icons.Rounded.Search, contentDescription = "搜索", tint = KtvTextPrimary)
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onRefresh,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x3325334D))
                    .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = KtvTextPrimary)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (!isSearchSplitMode) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(sourceSongs) { song ->
                    SongListRowAction(
                        song = song,
                        isFavorite = favoriteSongIds.contains(song.id),
                        isOrdered = orderedSongIds.contains(song.id),
                        onPlayClick = { onSongClick(song) },
                        onToggleFavorite = { onToggleFavorite(song) },
                        onDelete = { deletingSong = song }
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (sourceSongs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("未找到匹配歌曲", color = KtvTextSecondary, fontSize = 18.sp)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(sourceSongs) { song ->
                            SongListRowAction(
                                song = song,
                                isFavorite = favoriteSongIds.contains(song.id),
                                isOrdered = orderedSongIds.contains(song.id),
                                onPlayClick = { onSongClick(song) },
                                onToggleFavorite = { onToggleFavorite(song) },
                                onDelete = { deletingSong = song }
                            )
                        }
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0x3320304A)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "搜索",
                                color = KtvTextPrimary,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                isSearchSplitMode = false
                                keyword = ""
                            }) {
                                Text("关闭")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = keyword,
                            onValueChange = { keyword = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            leadingIcon = {
                                Icon(Icons.Rounded.Search, contentDescription = null, tint = KtvTextSecondary)
                            },
                            placeholder = {
                                Text("输入中文、拼音或首字母", color = KtvTextSecondary)
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = KtvPrimary,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.22f),
                                focusedTextColor = KtvTextPrimary,
                                unfocusedTextColor = KtvTextPrimary,
                                focusedContainerColor = Color(0x3320304A),
                                unfocusedContainerColor = Color(0x3320304A),
                                cursorColor = KtvPrimary
                            ),
                            shape = RoundedCornerShape(14.dp)
                        )
                    }
                }
            }
        }
    }

    if (deletingSong != null) {
        val deleting = deletingSong!!
        val isLocal = deleting.id.startsWith("local::")
        AlertDialog(
            onDismissRequest = { deletingSong = null },
            title = { Text("确认删除") },
            text = {
                Text(
                    if (isLocal) {
                        "确认删除《${deleting.title}》吗？\n删除后将同时删除本地文件。"
                    } else {
                        "确认删除《${deleting.title}》吗？\n内置歌曲仅从歌单中删除。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSong(deleting)
                    deletingSong = null
                }) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingSong = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun SongListRowAction(
    song: Song,
    isFavorite: Boolean,
    isOrdered: Boolean,
    onPlayClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0x3320304A)),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = song.title,
                color = KtvTextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = song.artist,
                color = KtvTextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onPlayClick,
                enabled = !isOrdered,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF7A73FF),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF6F7485),
                    disabledContentColor = Color.White.copy(alpha = 0.82f)
                ),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text(if (isOrdered) "已点" else "点歌", fontSize = 12.sp, maxLines = 1)
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "更多操作",
                        tint = KtvTextPrimary
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(if (isFavorite) "取消收藏" else "收藏") },
                        onClick = {
                            menuExpanded = false
                            onToggleFavorite()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SongListRowCentered(song: Song, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0x3320304A)),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = song.title,
                color = KtvTextPrimary,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                color = KtvTextSecondary,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun FavoriteListScreen(
    songs: List<Song>,
    favoriteSongIds: Set<String>,
    orderedSongIds: Set<String>,
    onSongClick: (Song) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    onDeleteSong: (Song) -> Unit,
    onBack: () -> Unit
) {
    var keyword by remember { mutableStateOf("") }
    var isSearchSplitMode by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val searchableSongs = remember(songs) {
        songs.map { song ->
            SearchableSong(
                song = song,
                titleKey = buildSearchKey(song.title),
                artistKey = buildSearchKey(song.artist)
            )
        }
    }
    val filteredSongs = remember(keyword, searchableSongs) {
        val normalizedQuery = normalizeQuery(keyword)
        if (normalizedQuery.isBlank()) {
            searchableSongs.map { it.song }
        } else {
            searchableSongs
                .filter { searchable ->
                    searchable.titleKey.contains(normalizedQuery) ||
                        searchable.artistKey.contains(normalizedQuery)
                }
                .map { it.song }
        }
    }
    val sourceSongs = if (isSearchSplitMode) filteredSongs else songs
    var deletingSong by remember { mutableStateOf<Song?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KtvBackButton(onClick = onBack)
            Spacer(modifier = Modifier.width(12.dp))
            Text("收藏夹", color = KtvTextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = { isSearchSplitMode = true },
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x3325334D))
                    .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
            ) {
                Icon(Icons.Rounded.Search, contentDescription = "搜索", tint = KtvTextPrimary)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (!isSearchSplitMode) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(sourceSongs) { song ->
                    SongListRowAction(
                        song = song,
                        isFavorite = favoriteSongIds.contains(song.id),
                        isOrdered = orderedSongIds.contains(song.id),
                        onPlayClick = { onSongClick(song) },
                        onToggleFavorite = { onToggleFavorite(song) },
                        onDelete = { deletingSong = song }
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (sourceSongs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("未找到匹配歌曲", color = KtvTextSecondary, fontSize = 18.sp)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(sourceSongs) { song ->
                            SongListRowAction(
                                song = song,
                                isFavorite = favoriteSongIds.contains(song.id),
                                isOrdered = orderedSongIds.contains(song.id),
                                onPlayClick = { onSongClick(song) },
                                onToggleFavorite = { onToggleFavorite(song) },
                                onDelete = { deletingSong = song }
                            )
                        }
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0x3320304A)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "搜索",
                                color = KtvTextPrimary,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                isSearchSplitMode = false
                                keyword = ""
                            }) {
                                Text("关闭")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = keyword,
                            onValueChange = { keyword = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            leadingIcon = {
                                Icon(Icons.Rounded.Search, contentDescription = null, tint = KtvTextSecondary)
                            },
                            placeholder = {
                                Text("输入中文、拼音或首字母", color = KtvTextSecondary)
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = KtvPrimary,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.22f),
                                focusedTextColor = KtvTextPrimary,
                                unfocusedTextColor = KtvTextPrimary,
                                focusedContainerColor = Color(0x3320304A),
                                unfocusedContainerColor = Color(0x3320304A),
                                cursorColor = KtvPrimary
                            ),
                            shape = RoundedCornerShape(14.dp)
                        )
                    }
                }
            }
        }
    }

    if (deletingSong != null) {
        val deleting = deletingSong!!
        val isLocal = deleting.id.startsWith("local::")
        AlertDialog(
            onDismissRequest = { deletingSong = null },
            title = { Text("确认删除") },
            text = {
                Text(
                    if (isLocal) {
                        "确认删除《${deleting.title}》吗？\n删除后将同时删除本地文件。"
                    } else {
                        "确认删除《${deleting.title}》吗？\n内置歌曲仅从歌单中删除。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSong(deleting)
                    deletingSong = null
                }) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingSong = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun SongCard(song: Song, isOrdered: Boolean, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0x3320304A)),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = KtvPrimary,
                modifier = Modifier.size(32.dp)
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    color = KtvTextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist,
                    color = KtvTextSecondary,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            Button(
                onClick = onClick,
                enabled = !isOrdered,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF7A73FF),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF6F7485),
                    disabledContentColor = Color.White.copy(alpha = 0.82f)
                ),
                shape = RoundedCornerShape(50)
            ) {
                Text(if (isOrdered) "已点" else "点歌")
            }
        }
    }
}

@Composable
fun FavoriteSongItem(song: Song, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0x3320304A)),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = song.title,
                color = KtvTextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = song.artist,
                color = KtvTextSecondary,
                fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "Play",
                tint = KtvSecondary
            )
        }
    }
}

@Composable
fun ControlBar(
    currentSong: Song?,
    queuedCount: Int,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onReplay: () -> Unit,
    onOriginalToggle: () -> Unit,
    onMuteToggle: () -> Unit,
    onShowQueue: () -> Unit,
    modifier: Modifier = Modifier,
    // 新增状态参数
    isPlaying: Boolean = false,
    isOriginalVocal: Boolean = true,
    isMuted: Boolean = false,
    showVideoThumbnail: Boolean = false,
    onVideoThumbnailPositioned: (IntOffset, IntSize) -> Unit = { _, _ -> },
    onVideoClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .padding(horizontal = 24.dp)
            .background(Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // 左侧：当前歌曲信息
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            if (showVideoThumbnail && currentSong != null) {
                Box(
                    modifier = Modifier
                        .size(width = 128.dp, height = 72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.2f))
                        .onGloballyPositioned { coordinates ->
                            val position = coordinates.positionInRoot()
                            onVideoThumbnailPositioned(
                                IntOffset(position.x.roundToInt(), position.y.roundToInt()),
                                coordinates.size
                            )
                        }
                        .clickable { onVideoClick?.invoke() }
                ) {
                    Icon(
                        Icons.Default.MusicVideo,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.28f),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(28.dp)
                    )
                }
            } else {
                Surface(
                    color = Color(0x33243045),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.size(60.dp)
                ) {
                    Icon(
                        Icons.Default.MusicVideo,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            if (currentSong != null) {
                Column {
                    Text(currentSong.title, color = KtvTextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(currentSong.artist, color = KtvTextSecondary, fontSize = 16.sp)
                }
            } else {
                Text("暂无播放", color = KtvTextSecondary, fontSize = 20.sp)
            }
        }

        // 中间：控制按钮
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 原/伴唱
            ControlButton(
                text = if (isOriginalVocal) "原唱" else "伴唱", 
                icon = Icons.Rounded.Mic, 
                onClick = onOriginalToggle,
                tint = if (isOriginalVocal) KtvPrimary else KtvTextSecondary
            )
            
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier
                    .size(64.dp)
                    .background(Color(0xFF7A73FF), RoundedCornerShape(50))
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = "播放/暂停",
                    tint = Color.Black,
                    modifier = Modifier.size(32.dp)
                )
            }
            
            ControlButton(text = "重唱", icon = Icons.Rounded.Replay, onClick = onReplay)
            ControlButton(text = "切歌", icon = Icons.Rounded.SkipNext, onClick = onNext)
            ControlButton(
                text = "静音",
                icon = if (isMuted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                onClick = onMuteToggle,
                tint = if (isMuted) KtvPrimary else KtvTextPrimary
            )
        }

        // 右侧：已点列表
        Spacer(modifier = Modifier.width(24.dp))
        Button(
            onClick = onShowQueue,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("已点", color = KtvSecondary, fontSize = 14.sp)
                Text("$queuedCount", color = KtvTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ControlButton(text: String, icon: ImageVector, onClick: () -> Unit, tint: Color = KtvTextPrimary) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Icon(imageVector = icon, contentDescription = text, tint = tint, modifier = Modifier.size(30.dp))
        Text(text = text, color = tint, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun KtvBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x3325334D))
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(16.dp))
    ) {
        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = KtvTextPrimary)
    }
}

private data class SearchableSong(
    val song: Song,
    val titleKey: String,
    val artistKey: String
)

private val hanToLatinTransliterator: Transliterator by lazy {
    Transliterator.getInstance("Han-Latin; Latin-ASCII")
}

private fun buildSearchKey(text: String): String {
    val direct = normalizeQuery(text)
    val pinyin = normalizeQuery(hanToLatinTransliterator.transliterate(text))
    val initials = pinyin
        .split(" ")
        .filter { it.isNotBlank() }
        .joinToString(separator = "") { token -> token.first().toString() }
    return listOf(direct, pinyin, initials)
        .filter { it.isNotBlank() }
        .joinToString(separator = " ")
}

private fun normalizeQuery(input: String): String {
    return input
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9\\u4e00-\\u9fa5 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "00:00"
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

@Composable
fun QueueSheetContent(
    songs: List<Song>,
    onDismiss: () -> Unit,
    onPlayNow: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("已点歌曲", fontSize = 22.sp, color = KtvTextPrimary, fontWeight = FontWeight.Bold)
            TextButton(onClick = onDismiss) {
                Text("关闭", color = KtvSecondary)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (songs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("当前没有已点歌曲", color = KtvTextSecondary)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                items(songs) { song ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0x3320304A)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(song.title, color = KtvTextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(song.artist, color = KtvTextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = { onPlayNow(song.id) }) {
                                Text("切歌", color = KtvPrimary)
                            }
                            TextButton(onClick = { onRemove(song.id) }) {
                                Text("删除", color = Color(0xFFFF6B8A))
                            }
                        }
                    }
                }
            }
        }
    }
}
