package com.sean.micuncle.ui.home

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import com.sean.micuncle.data.Song
import com.sean.micuncle.data.SongRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.ArrayDeque

import com.sean.micuncle.player.KtvPlayer

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    // 所有歌曲列表
    private val _allSongs = MutableStateFlow<List<Song>>(emptyList())
    val allSongs: StateFlow<List<Song>> = _allSongs.asStateFlow()

    // 收藏/常唱歌曲
    private val _favoriteSongs = MutableStateFlow<List<Song>>(emptyList())
    val favoriteSongs: StateFlow<List<Song>> = _favoriteSongs.asStateFlow()

    // 已点歌曲 (排队)
    private val _queuedSongs = MutableStateFlow<List<Song>>(emptyList())
    val queuedSongs: StateFlow<List<Song>> = _queuedSongs.asStateFlow()

    // 当前播放的歌曲
    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    // 播放器
    private val _ktvPlayer = KtvPlayer(application)
    val ktvPlayer: KtvPlayer = _ktvPlayer

    // 页面状态
    private val _currentScreen = MutableStateFlow(Screen.HOME)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    // 前台检测到的新歌（等待用户确认）
    private val _pendingDetectedSong = MutableStateFlow<DetectedLocalSong?>(null)
    val pendingDetectedSong: StateFlow<DetectedLocalSong?> = _pendingDetectedSong.asStateFlow()
    private val _pendingDetectedSongsBatch = MutableStateFlow<List<DetectedLocalSong>>(emptyList())
    val pendingDetectedSongsBatch: StateFlow<List<DetectedLocalSong>> = _pendingDetectedSongsBatch.asStateFlow()
    private val _refreshResultMessage = MutableStateFlow<String?>(null)
    val refreshResultMessage: StateFlow<String?> = _refreshResultMessage.asStateFlow()
    private val _operationMessage = MutableStateFlow<String?>(null)
    val operationMessage: StateFlow<String?> = _operationMessage.asStateFlow()

    private val songRepository = SongRepository(getApplication())
    private val appPrefs by lazy {
        getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val deletedAssetSongIds = mutableSetOf<String>()
    private val localSongQueue = ArrayDeque<DetectedLocalSong>()
    private val existingLocalSongKeys = mutableSetOf<String>()
    private val seenLocalSongKeys = mutableSetOf<String>()
    private val promptedLocalSongKeys = mutableSetOf<String>()
    private var localSongObserver: FileObserver? = null

    fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
    }

    init {
        loadDeletedAssetSongIds()
        scanAssets()
        startLocalSongObserver()
        _ktvPlayer.setOnPlaybackCompletedListener {
            viewModelScope.launch {
                playNext()
            }
        }
    }

    private fun scanAssets() {
        viewModelScope.launch {
            val songs = songRepository.scanAssets()
                .filterNot { deletedAssetSongIds.contains(it.id) }
            _allSongs.value = songs
            // 默认收藏前3首用于演示
            _favoriteSongs.value = songs.take(3).map { it.copy(isFavorite = true) }
            existingLocalSongKeys.clear()
            existingLocalSongKeys.addAll(songs.mapNotNull { extractLocalSongKey(it.id) })
        }
    }

    private fun startLocalSongObserver() {
        if (!hasMediaReadPermission()) return
        val targetDir = getTargetDirectory()

        viewModelScope.launch(Dispatchers.IO) {
            if (!targetDir.exists() || !targetDir.isDirectory) return@launch
            listSupportedMediaFilesRecursively(targetDir)
                .forEach { seenLocalSongKeys.add(composeSongKey(it.name, it.length())) }

            if (localSongObserver != null) return@launch
            localSongObserver = object : FileObserver(
                targetDir.absolutePath,
                CLOSE_WRITE or MOVED_TO
            ) {
                override fun onEvent(event: Int, path: String?) {
                    if (path.isNullOrBlank()) return
                    val file = File(targetDir, path)
                    if (!file.isFile) return
                    if (!isSupportedMediaFile(file.name)) return
                    viewModelScope.launch(Dispatchers.IO) {
                        kotlinx.coroutines.delay(1200)
                        if (!file.exists() || file.length() <= 0L) return@launch
                        handleDetectedFile(file)
                    }
                }
            }
            localSongObserver?.startWatching()
        }
    }

    private fun handleDetectedFile(file: File, fromManualRefresh: Boolean = false): Boolean {
        val songKey = composeSongKey(file.name, file.length())
        if (existingLocalSongKeys.contains(songKey)) return false
        if (!fromManualRefresh) {
            if (seenLocalSongKeys.contains(songKey)) return false
            if (promptedLocalSongKeys.contains(songKey)) return false
            seenLocalSongKeys.add(songKey)
            promptedLocalSongKeys.add(songKey)
        } else {
            val alreadyPending = _pendingDetectedSong.value?.songKey == songKey ||
                localSongQueue.any { it.songKey == songKey }
            if (alreadyPending) return false
        }

        val detectedSong = DetectedLocalSong(
            fileName = file.name,
            absolutePath = file.absolutePath,
            sizeBytes = file.length(),
            songKey = songKey
        )
        viewModelScope.launch {
            if (_pendingDetectedSong.value == null) {
                _pendingDetectedSong.value = detectedSong
            } else {
                localSongQueue.addLast(detectedSong)
            }
        }
        return true
    }

    fun refreshLocalSongs() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!hasMediaReadPermission()) {
                _refreshResultMessage.value = "仅扫描百度网盘下载目录；未授予本地媒体读取权限，已跳过本地目录扫描"
                return@launch
            }

            val targetDir = getTargetDirectory()
            if (!targetDir.exists() || !targetDir.isDirectory) {
                _refreshResultMessage.value = "仅扫描百度网盘下载目录；未找到目录：${targetDir.absolutePath}"
                return@launch
            }

            val mediaFiles = listSupportedMediaFilesRecursively(targetDir)

            val detectedSongs = mutableListOf<DetectedLocalSong>()
            mediaFiles.forEach { file ->
                createManualRefreshDetectedSong(file)?.let { detectedSongs.add(it) }
            }

            if (detectedSongs.isEmpty()) {
                _refreshResultMessage.value = "已扫描百度网盘下载目录，未发现可加入歌库的新歌"
            } else {
                _pendingDetectedSongsBatch.value = detectedSongs
            }
        }
    }

    private fun createManualRefreshDetectedSong(file: File): DetectedLocalSong? {
        val songKey = composeSongKey(file.name, file.length())
        if (existingLocalSongKeys.contains(songKey)) return null
        val existsInSinglePrompt = _pendingDetectedSong.value?.songKey == songKey ||
            localSongQueue.any { it.songKey == songKey }
        if (existsInSinglePrompt) return null
        val existsInBatch = _pendingDetectedSongsBatch.value.any { it.songKey == songKey }
        if (existsInBatch) return null
        return DetectedLocalSong(
            fileName = file.name,
            absolutePath = file.absolutePath,
            sizeBytes = file.length(),
            songKey = songKey
        )
    }

    fun addAllDetectedSongsToLibrary() {
        val pendingBatch = _pendingDetectedSongsBatch.value
        if (pendingBatch.isEmpty()) return

        val newSongs = mutableListOf<Song>()
        pendingBatch.forEach { detectedSong ->
            if (existingLocalSongKeys.contains(detectedSong.songKey)) return@forEach
            val file = File(detectedSong.absolutePath)
            if (!file.exists() || file.length() <= 0L) return@forEach
            val (artist, title) = parseArtistAndTitle(file.nameWithoutExtension)
            newSongs.add(
                Song(
                    id = LOCAL_SONG_ID_PREFIX + detectedSong.songKey,
                    title = title,
                    artist = artist,
                    videoUri = Uri.fromFile(file).toString()
                )
            )
            existingLocalSongKeys.add(detectedSong.songKey)
        }

        if (newSongs.isNotEmpty()) {
            _allSongs.value = (_allSongs.value + newSongs).distinctBy { it.id }
            _refreshResultMessage.value = "已批量加入 ${newSongs.size} 首新歌"
        } else {
            _refreshResultMessage.value = "本次发现的新歌已失效或已存在，未新增歌曲"
        }
        _pendingDetectedSongsBatch.value = emptyList()
    }

    fun ignoreAllDetectedSongs() {
        val ignoredCount = _pendingDetectedSongsBatch.value.size
        _pendingDetectedSongsBatch.value = emptyList()
        if (ignoredCount > 0) {
            _refreshResultMessage.value = "已忽略 $ignoredCount 首新歌"
        }
    }

    fun dismissBatchDetectedSongs() {
        _pendingDetectedSongsBatch.value = emptyList()
    }

    fun dismissRefreshResultMessage() {
        _refreshResultMessage.value = null
    }

    fun dismissOperationMessage() {
        _operationMessage.value = null
    }

    fun addDetectedSongToLibrary() {
        val detectedSong = _pendingDetectedSong.value ?: return
        if (existingLocalSongKeys.contains(detectedSong.songKey)) {
            showNextDetectedSong()
            return
        }

        val file = File(detectedSong.absolutePath)
        if (!file.exists() || file.length() <= 0L) {
            showNextDetectedSong()
            return
        }

        val (artist, title) = parseArtistAndTitle(file.nameWithoutExtension)
        val localSong = Song(
            id = LOCAL_SONG_ID_PREFIX + detectedSong.songKey,
            title = title,
            artist = artist,
            videoUri = Uri.fromFile(file).toString()
        )
        _allSongs.value = _allSongs.value + localSong
        existingLocalSongKeys.add(detectedSong.songKey)
        showNextDetectedSong()
    }

    fun ignoreDetectedSong() {
        showNextDetectedSong()
    }

    private fun showNextDetectedSong() {
        _pendingDetectedSong.value = if (localSongQueue.isNotEmpty()) {
            localSongQueue.removeFirst()
        } else {
            null
        }
    }

    private fun parseArtistAndTitle(baseName: String): Pair<String, String> {
        val separatorIndex = baseName.indexOf('-')
        if (separatorIndex <= 0) {
            return "未知歌手" to baseName.trim()
        }

        val artist = baseName.substring(0, separatorIndex).trim().ifEmpty { "未知歌手" }
        val songPart = baseName.substring(separatorIndex + 1).trim()
        val titleSegment = songPart.substringBefore("-").trim().ifEmpty { songPart }
        val cleanedTitle = titleSegment
            .replace(Regex("[\\(\\[].*?[\\)\\]]"), "")
            .trim()
            .ifEmpty { titleSegment }
        return artist to cleanedTitle
    }

    private fun composeSongKey(fileName: String, fileSize: Long): String {
        return "${fileName.lowercase()}#$fileSize"
    }

    private fun extractLocalSongKey(songId: String): String? {
        if (!songId.startsWith(LOCAL_SONG_ID_PREFIX)) return null
        return songId.removePrefix(LOCAL_SONG_ID_PREFIX)
    }

    private fun isSupportedMediaFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in SUPPORTED_LOCAL_MEDIA_EXTENSIONS
    }

    private fun listSupportedMediaFilesRecursively(rootDir: File): List<File> {
        if (!rootDir.exists() || !rootDir.isDirectory) return emptyList()
        val result = mutableListOf<File>()
        val stack = ArrayDeque<File>()
        stack.add(rootDir)

        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            children.forEach { child ->
                when {
                    child.isDirectory -> stack.add(child)
                    child.isFile && isSupportedMediaFile(child.name) -> result.add(child)
                }
            }
        }
        return result
    }

    @Suppress("DEPRECATION")
    private fun getTargetDirectory(): File {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(downloadDir, "BaiduNetdisk")
    }

    private fun hasMediaReadPermission(): Boolean {
        val context = getApplication<Application>()
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                android.Manifest.permission.READ_MEDIA_AUDIO,
                android.Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return permissions.any { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun playSong(song: Song) {
        if (isSongQueuedOrPlaying(song.id)) return
        if (_currentSong.value == null) {
            // 如果当前没有播放，直接播放
            _currentSong.value = song
            _ktvPlayer.play(song.videoUri, song.accompanimentUri)
        } else {
            // 如果正在播放，加入已点列表
            addToQueue(song)
        }
    }

    fun addToQueue(song: Song) {
        if (isSongQueuedOrPlaying(song.id)) return
        val currentQueue = _queuedSongs.value.toMutableList()
        currentQueue.add(song)
        _queuedSongs.value = currentQueue
    }

    private fun isSongQueuedOrPlaying(songId: String): Boolean {
        if (_currentSong.value?.id == songId) return true
        return _queuedSongs.value.any { it.id == songId }
    }

    fun toggleFavorite(song: Song) {
        val favorites = _favoriteSongs.value.toMutableList()
        val index = favorites.indexOfFirst { it.id == song.id }
        if (index >= 0) {
            favorites.removeAt(index)
        } else {
            favorites.add(song.copy(isFavorite = true))
        }
        _favoriteSongs.value = favorites
    }

    fun deleteSong(song: Song) {
        viewModelScope.launch(Dispatchers.IO) {
            val isLocalSong = song.id.startsWith(LOCAL_SONG_ID_PREFIX)
            if (isLocalSong) {
                val path = Uri.parse(song.videoUri).path
                if (!path.isNullOrBlank()) {
                    val file = File(path)
                    if (file.exists() && !file.delete()) {
                        _operationMessage.value = "删除失败：无法删除本地文件 ${file.name}"
                        return@launch
                    }
                }
            }

            removeSongEverywhere(song.id)
            if (isLocalSong) {
                extractLocalSongKey(song.id)?.let { existingLocalSongKeys.remove(it) }
            } else {
                markAssetSongDeleted(song.id)
            }
        }
    }

    private fun loadDeletedAssetSongIds() {
        deletedAssetSongIds.clear()
        deletedAssetSongIds.addAll(appPrefs.getStringSet(KEY_DELETED_ASSET_SONG_IDS, emptySet()).orEmpty())
    }

    private fun markAssetSongDeleted(songId: String) {
        if (!deletedAssetSongIds.add(songId)) return
        appPrefs.edit()
            .putStringSet(KEY_DELETED_ASSET_SONG_IDS, deletedAssetSongIds.toSet())
            .apply()
    }

    private fun removeSongEverywhere(songId: String) {
        _allSongs.value = _allSongs.value.filterNot { it.id == songId }
        _favoriteSongs.value = _favoriteSongs.value.filterNot { it.id == songId }
        _queuedSongs.value = _queuedSongs.value.filterNot { it.id == songId }
        if (_currentSong.value?.id == songId) {
            _currentSong.value = null
            _ktvPlayer.stop()
        }
    }

    fun removeQueuedSong(songId: String) {
        val currentQueue = _queuedSongs.value.toMutableList()
        val index = currentQueue.indexOfFirst { it.id == songId }
        if (index >= 0) {
            currentQueue.removeAt(index)
            _queuedSongs.value = currentQueue
        }
    }

    fun playQueuedSongNow(songId: String) {
        val currentQueue = _queuedSongs.value.toMutableList()
        val index = currentQueue.indexOfFirst { it.id == songId }
        if (index < 0) return

        val selectedSong = currentQueue.removeAt(index)
        _queuedSongs.value = currentQueue
        _currentSong.value = selectedSong
        _ktvPlayer.play(selectedSong.videoUri, selectedSong.accompanimentUri)
    }

    fun playNext() {
        val currentQueue = _queuedSongs.value.toMutableList()
        if (currentQueue.isNotEmpty()) {
            val nextSong = currentQueue.removeAt(0)
            _queuedSongs.value = currentQueue
            _currentSong.value = nextSong
            _ktvPlayer.play(nextSong.videoUri, nextSong.accompanimentUri)
        } else {
            // 列表播放完毕，停止或循环
            _currentSong.value = null
            _ktvPlayer.stop()
        }
    }
    
    fun replay() {
        _currentSong.value?.let { song ->
             _ktvPlayer.play(song.videoUri, song.accompanimentUri)
        }
    }
    
    fun toggleVocal() {
        _ktvPlayer.toggleVocal()
    }
    
    fun playPause() {
        if (_ktvPlayer.isPlaying.value) {
            _ktvPlayer.pause()
        } else {
            _ktvPlayer.resume()
        }
    }

    fun toggleMute() {
        _ktvPlayer.toggleMute()
    }
    
    override fun onCleared() {
        super.onCleared()
        localSongObserver?.stopWatching()
        localSongObserver = null
        _ktvPlayer.setOnPlaybackCompletedListener(null)
        _ktvPlayer.release()
    }

    companion object {
        private const val LOCAL_SONG_ID_PREFIX = "local::"
        private const val PREFS_NAME = "home_vm_prefs"
        private const val KEY_DELETED_ASSET_SONG_IDS = "deleted_asset_song_ids"
        private val SUPPORTED_LOCAL_MEDIA_EXTENSIONS = setOf(
            "mp3", "flac", "wav", "m4a", "aac", "ogg",
            "mp4", "mpg", "mpeg", "mkv", "avi", "mov"
        )
    }
}

data class DetectedLocalSong(
    val fileName: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val songKey: String
)
