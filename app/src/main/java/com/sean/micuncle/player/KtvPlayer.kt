package com.sean.micuncle.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer as VlcMediaPlayer
import java.io.File
import java.security.MessageDigest

class KtvPlayer(private val context: Context) {
    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build()
    private val libVlc: LibVLC = LibVLC(
        context,
        arrayListOf("--no-drop-late-frames", "--no-skip-frames", "--avcodec-fast")
    )
    private val vlcPlayer: VlcMediaPlayer = VlcMediaPlayer(libVlc)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var boundSurfaceHolder: SurfaceHolder? = null
    private var boundSurfaceRef: Surface? = null
    private var activeBackend: Backend = Backend.EXO
    private var isExoPrepared: Boolean = false
    private var isVlcPrepared: Boolean = false
    private var isVlcViewsAttached: Boolean = false
    private var isCurrentMpegProgramStream: Boolean = false
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isOriginalVocal = MutableStateFlow(true) // true: 原唱, false: 伴唱
    val isOriginalVocal: StateFlow<Boolean> = _isOriginalVocal.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private var currentOriginalUri: String? = null
    private var onPlaybackCompleted: (() -> Unit)? = null
    private var pendingSeekPositionMs: Long = 0L
    private var currentLoadingUri: String? = null
    private var audioTrackRole: AudioTrackRole? = null

    init {
        exoPlayer.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        configureExoPlayer()
        configureVlcPlayer()
    }

    private fun configureVlcPlayer() {
        vlcPlayer.setEventListener { event ->
            when (event.type) {
                VlcMediaPlayer.Event.Playing -> {
                    isVlcPrepared = true
                    resolveVlcAudioTrackRoleIfNeeded()
                    applyOriginalTrackIfPossible()
                    if (_isMuted.value) {
                        vlcPlayer.setVolume(0)
                    } else {
                        vlcPlayer.setVolume(100)
                    }
                    if (pendingSeekPositionMs > 0L) {
                        vlcPlayer.time = pendingSeekPositionMs
                        pendingSeekPositionMs = 0L
                    }
                    _isPlaying.value = true
                    Log.d(TAG, "onReady(vlc): length=${vlcPlayer.length}")
                }
                VlcMediaPlayer.Event.Paused,
                VlcMediaPlayer.Event.Stopped -> {
                    _isPlaying.value = false
                }
                VlcMediaPlayer.Event.EndReached -> {
                    _isPlaying.value = false
                    Log.d(TAG, "onCompletion(vlc)")
                    onPlaybackCompleted?.invoke()
                }
                VlcMediaPlayer.Event.EncounteredError -> {
                    isVlcPrepared = false
                    _isPlaying.value = false
                    Log.e(TAG, "onError(vlc)")
                    val fallbackUri = currentLoadingUri
                    if (fallbackUri != null && activeBackend == Backend.VLC) {
                        mainHandler.post {
                            if (activeBackend == Backend.VLC) {
                                audioTrackRole = null
                                activeBackend = Backend.EXO
                                loadWithExo(fallbackUri)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun configureExoPlayer() {
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        isExoPrepared = true
                        if (activeBackend == Backend.EXO) {
                            resolveExoAudioTrackRoleIfNeeded()
                            applyOriginalTrackIfPossible()
                            if (pendingSeekPositionMs > 0L) {
                                exoPlayer.seekTo(pendingSeekPositionMs)
                                pendingSeekPositionMs = 0L
                            }
                            exoPlayer.playWhenReady = true
                            _isPlaying.value = true
                            Log.d(TAG, "onReady(exo): duration=${exoPlayer.duration}")
                        }
                    }
                    Player.STATE_ENDED -> {
                        if (activeBackend == Backend.EXO) {
                            _isPlaying.value = false
                            Log.d(TAG, "onCompletion(exo)")
                            onPlaybackCompleted?.invoke()
                        }
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (activeBackend == Backend.EXO) {
                    _isPlaying.value = isPlaying
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (activeBackend == Backend.EXO) {
                    _isPlaying.value = false
                }
                isExoPrepared = false
                Log.e(TAG, "onError(exo): code=${error.errorCode}, msg=${error.message}", error)
            }
        })
    }

    fun play(originalUri: String, accompanimentUri: String?) {
        currentOriginalUri = originalUri
        audioTrackRole = null

        // 默认播放原唱
        _isOriginalVocal.value = true
        loadMedia(originalUri, 0L, preferBackendForUri(originalUri))
    }

    private fun loadMedia(uri: String, startPosition: Long, preferredBackend: Backend) {
        pendingSeekPositionMs = startPosition.coerceAtLeast(0L)
        currentLoadingUri = uri
        audioTrackRole = null
        isCurrentMpegProgramStream = isMpegProgramStreamUri(uri)
        exoPlayer.videoScalingMode = if (isCurrentMpegProgramStream) {
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        } else {
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        }
        activeBackend = preferredBackend
        if (preferredBackend == Backend.VLC) {
            loadWithVlc(uri)
        } else {
            loadWithExo(uri)
        }
    }

    private fun loadWithVlc(uri: String) {
        isVlcPrepared = false
        _isPlaying.value = false
        runCatching {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            val sourceUri = resolvePlayableUri(uri)
            val media = Media(libVlc, sourceUri)
            media.setHWDecoderEnabled(false, false)
            vlcPlayer.media = media
            media.release()
            applySurfaceToActiveBackend()
            vlcPlayer.play()
            Log.d(TAG, "loadWithVlc: play uri=$sourceUri")
        }.onFailure { throwable ->
            isVlcPrepared = false
            _isPlaying.value = false
            Log.e(TAG, "loadWithVlc: failed uri=$uri", throwable)
        }
    }

    private fun loadWithExo(uri: String) {
        isExoPrepared = false
        _isPlaying.value = false
        runCatching {
            stopVlcPlayback()
            val sourceUri = resolvePlayableUri(uri)
            val mediaItem = MediaItem.fromUri(sourceUri)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            applySurfaceToActiveBackend()
            Log.d(TAG, "loadWithExo: prepare uri=$sourceUri")
        }.onFailure { throwable ->
            isExoPrepared = false
            _isPlaying.value = false
            Log.e(TAG, "loadWithExo: prepare failed uri=$uri", throwable)
        }
    }

    private fun resolvePlayableUri(uri: String): Uri {
        if (!uri.startsWith(ASSET_SCHEME_PREFIX)) {
            return Uri.parse(uri)
        }
        val assetPath = uri.removePrefix(ASSET_SCHEME_PREFIX)
        val cachedFile = ensureAssetCopiedToCache(assetPath)
        return Uri.fromFile(cachedFile)
    }

    private fun ensureAssetCopiedToCache(assetPath: String): File {
        val cacheDir = File(context.cacheDir, "media_assets")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        val extension = assetPath.substringAfterLast('.', "")
        val hashedName = sha256(assetPath)
        val cacheFileName = if (extension.isBlank()) hashedName else "$hashedName.$extension"
        val cacheFile = File(cacheDir, cacheFileName)
        val expectedLength = readAssetLengthSafely(assetPath)

        val shouldReuse = cacheFile.exists() &&
            (expectedLength == null || cacheFile.length() == expectedLength)
        if (shouldReuse) {
            return cacheFile
        }

        context.assets.open(assetPath).use { input ->
            cacheFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return cacheFile
    }

    private fun readAssetLengthSafely(assetPath: String): Long? {
        return try {
            context.assets.openFd(assetPath).use { afd ->
                if (afd.length > 0) afd.length else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun pause() {
        if (!_isPlaying.value) return
        when (activeBackend) {
            Backend.EXO -> {
                if (!isExoPrepared) return
                exoPlayer.pause()
            }
            Backend.VLC -> {
                if (!isVlcPrepared) return
                vlcPlayer.pause()
            }
        }
        _isPlaying.value = false
    }

    fun resume() {
        when (activeBackend) {
            Backend.EXO -> {
                if (!isExoPrepared) {
                    Log.w(TAG, "resume ignored(exo): player not prepared")
                    return
                }
                exoPlayer.play()
            }
            Backend.VLC -> {
                if (!isVlcPrepared) {
                    Log.w(TAG, "resume ignored(vlc): player not prepared")
                    return
                }
                vlcPlayer.play()
            }
        }
        _isPlaying.value = true
    }
    
    fun stop() {
        stopVlcPlayback()
        exoPlayer.stop()
        isExoPrepared = false
        isVlcPrepared = false
        _isPlaying.value = false
    }

    fun release() {
        detachSurface()
        stopVlcPlayback()
        exoPlayer.release()
        vlcPlayer.release()
        libVlc.release()
        isExoPrepared = false
        isVlcPrepared = false
        _isPlaying.value = false
    }

    fun setOnPlaybackCompletedListener(listener: (() -> Unit)?) {
        onPlaybackCompleted = listener
    }

    fun toggleMute() {
        val muted = !_isMuted.value
        when (activeBackend) {
            Backend.EXO -> exoPlayer.volume = if (muted) 0f else 1f
            Backend.VLC -> vlcPlayer.setVolume(if (muted) 0 else 100)
        }
        _isMuted.value = muted
    }

    // 当前统一按单文件音轨切换
    fun toggleVocal() {
        toggleSingleFileTrack()
    }

    private fun toggleSingleFileTrack() {
        val role = when (activeBackend) {
            Backend.EXO -> resolveExoAudioTrackRoleIfNeeded()
            Backend.VLC -> resolveVlcAudioTrackRoleIfNeeded()
        } ?: run {
            Log.w(TAG, "toggleSingleFileTrack ignored: no dual audio tracks")
            return
        }

        when (activeBackend) {
            Backend.EXO -> {
                val target = if (_isOriginalVocal.value) {
                    role.exoAccompanimentTrack
                } else {
                    role.exoOriginalTrack
                } ?: return
                val override = TrackSelectionOverride(target.group, listOf(target.trackIndex))
                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                    .setOverrideForType(override)
                    .build()
            }

            Backend.VLC -> {
                val target = if (_isOriginalVocal.value) role.vlcAccompanimentTrackId else role.vlcOriginalTrackId
                if (target == null || target == INVALID_TRACK_ID) return
                runCatching {
                    vlcPlayer.setAudioTrack(target)
                }.onFailure { throwable ->
                    Log.e(TAG, "toggleSingleFileTrack(vlc) failed: track=$target", throwable)
                    return
                }
            }
        }

        _isOriginalVocal.value = !_isOriginalVocal.value
    }

    private fun applyOriginalTrackIfPossible() {
        val role = audioTrackRole ?: return
        when (activeBackend) {
            Backend.EXO -> {
                val target = role.exoOriginalTrack ?: return
                val override = TrackSelectionOverride(target.group, listOf(target.trackIndex))
                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                    .setOverrideForType(override)
                    .build()
            }

            Backend.VLC -> {
                val trackId = role.vlcOriginalTrackId ?: return
                if (trackId == INVALID_TRACK_ID) return
                vlcPlayer.setAudioTrack(trackId)
            }
        }
        _isOriginalVocal.value = true
    }

    private fun resolveVlcAudioTrackRoleIfNeeded(): AudioTrackRole? {
        val current = audioTrackRole
        if (current?.supportsVlc == true) return current

        val tracks = runCatching { vlcPlayer.audioTracks?.toList().orEmpty() }
            .getOrElse { emptyList() }
            .filter { it.id != INVALID_TRACK_ID }
        if (tracks.size < 2) return null

        val originalKeywords = listOf("vocal", "original", "main", "原唱", "人声")
        val accompanimentKeywords = listOf("inst", "accompaniment", "karaoke", "music", "bgm", "伴奏", "消音")

        val originalTrack = tracks.firstOrNull { desc ->
            containsKeyword(desc.name.orEmpty(), originalKeywords)
        } ?: tracks.first()
        val accompanimentTrack = tracks.firstOrNull { desc ->
            containsKeyword(desc.name.orEmpty(), accompanimentKeywords) && desc.id != originalTrack.id
        } ?: tracks.firstOrNull { it.id != originalTrack.id } ?: return null

        val resolved = (audioTrackRole ?: AudioTrackRole()).copy(
            vlcOriginalTrackId = originalTrack.id,
            vlcAccompanimentTrackId = accompanimentTrack.id
        )
        audioTrackRole = resolved
        return resolved
    }

    private fun resolveExoAudioTrackRoleIfNeeded(): AudioTrackRole? {
        val current = audioTrackRole
        if (current?.supportsExo == true) return current

        val candidates = mutableListOf<ExoAudioTrackCandidate>()
        val tracks = exoPlayer.currentTracks.groups
        for (group in tracks) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                val format = group.getTrackFormat(index)
                val descriptor = listOf(format.label, format.language)
                    .filterNotNull()
                    .joinToString(" ")
                candidates += ExoAudioTrackCandidate(
                    track = ExoAudioTrack(group.mediaTrackGroup, index),
                    descriptor = descriptor
                )
            }
        }
        if (candidates.size < 2) return null

        val originalKeywords = listOf("vocal", "original", "main", "原唱", "人声")
        val accompanimentKeywords = listOf("inst", "accompaniment", "karaoke", "music", "bgm", "伴奏", "消音")

        val originalTrack = candidates.firstOrNull { containsKeyword(it.descriptor, originalKeywords) } ?: candidates.first()
        val accompanimentTrack = candidates.firstOrNull {
            containsKeyword(it.descriptor, accompanimentKeywords) && it.track != originalTrack.track
        } ?: candidates.firstOrNull { it.track != originalTrack.track } ?: return null

        val resolved = (audioTrackRole ?: AudioTrackRole()).copy(
            exoOriginalTrack = originalTrack.track,
            exoAccompanimentTrack = accompanimentTrack.track
        )
        audioTrackRole = resolved
        return resolved
    }

    private fun containsKeyword(source: String, keywords: List<String>): Boolean {
        val normalized = source.lowercase()
        if (normalized.isBlank()) return false
        return keywords.any { keyword -> normalized.contains(keyword.lowercase()) }
    }

    fun attachSurface(holder: SurfaceHolder) {
        val surface = holder.surface
        if (surface?.isValid != true) {
            Log.w(TAG, "attachSurface ignored: invalid surface")
            return
        }
        if (boundSurfaceHolder === holder && boundSurfaceRef === surface) {
            Log.d(TAG, "attachSurface skipped: holder+surface unchanged for $activeBackend")
            return
        }
        boundSurfaceHolder = holder
        boundSurfaceRef = surface
        applySurfaceToActiveBackend()
        Log.d(TAG, "attachSurface: display bound for $activeBackend")
    }

    fun onSurfaceSizeChanged(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        surfaceWidth = width
        surfaceHeight = height
        if (activeBackend == Backend.VLC && isVlcViewsAttached) {
            runCatching {
                vlcPlayer.vlcVout.setWindowSize(width, height)
                applyVlcScaleMode(width, height)
            }.onFailure { throwable ->
                Log.e(TAG, "onSurfaceSizeChanged(vlc) failed: ${width}x$height", throwable)
            }
        }
    }

    fun detachSurface(holder: SurfaceHolder? = null) {
        if (holder != null && boundSurfaceHolder !== holder) return
        exoPlayer.clearVideoSurface()
        detachVlcSurface()
        if (holder == null || boundSurfaceHolder === holder) {
            boundSurfaceHolder = null
            boundSurfaceRef = null
        }
        Log.d(TAG, "detachSurface: display cleared")
    }

    fun currentPosition(): Long {
        return when (activeBackend) {
            Backend.EXO -> exoPlayer.currentPosition.coerceAtLeast(0L)
            Backend.VLC -> vlcPlayer.time.coerceAtLeast(0L)
        }
    }

    fun duration(): Long {
        return when (activeBackend) {
            Backend.EXO -> exoPlayer.duration.coerceAtLeast(0L)
            Backend.VLC -> vlcPlayer.length.coerceAtLeast(0L)
        }
    }

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        when (activeBackend) {
            Backend.EXO -> {
                if (!isExoPrepared) {
                    pendingSeekPositionMs = target
                    Log.w(TAG, "seekTo deferred(exo): not prepared, target=$target")
                    return
                }
                exoPlayer.seekTo(target)
            }
            Backend.VLC -> {
                if (!isVlcPrepared) {
                    pendingSeekPositionMs = target
                    Log.w(TAG, "seekTo deferred(vlc): not prepared, target=$target")
                    return
                }
                vlcPlayer.time = target
            }
        }
        Log.d(TAG, "seekTo: target=$target, backend=$activeBackend")
    }

    private fun applySurfaceToActiveBackend() {
        val holder = boundSurfaceHolder ?: return
        if (holder.surface?.isValid != true) {
            Log.w(TAG, "applySurfaceToActiveBackend ignored: invalid surface")
            return
        }
        when (activeBackend) {
            Backend.EXO -> {
                detachVlcSurface()
                exoPlayer.setVideoSurfaceHolder(holder)
            }
            Backend.VLC -> {
                exoPlayer.clearVideoSurface()
                attachVlcSurface(holder)
            }
        }
    }

    private fun attachVlcSurface(holder: SurfaceHolder) {
        runCatching {
            val vout = vlcPlayer.vlcVout
            if (isVlcViewsAttached) {
                vout.detachViews()
                isVlcViewsAttached = false
            }
            vout.setVideoSurface(holder.surface, holder)
            vout.attachViews()
            isVlcViewsAttached = true
            val frame = holder.surfaceFrame
            val width = if (surfaceWidth > 0) surfaceWidth else frame.width()
            val height = if (surfaceHeight > 0) surfaceHeight else frame.height()
            if (width > 0 && height > 0) {
                vout.setWindowSize(width, height)
                applyVlcScaleMode(width, height)
            }
        }.onFailure { throwable ->
            Log.e(TAG, "attachVlcSurface failed", throwable)
        }
    }

    private fun detachVlcSurface() {
        if (!isVlcViewsAttached) return
        runCatching {
            vlcPlayer.vlcVout.detachViews()
            isVlcViewsAttached = false
        }.onFailure { throwable ->
            Log.e(TAG, "detachVlcSurface failed", throwable)
        }
    }

    private fun stopVlcPlayback() {
        runCatching {
            vlcPlayer.stop()
        }.onFailure {
            // ignore stop failures during rapid backend switches
        }
        isVlcPrepared = false
        detachVlcSurface()
    }

    private fun applyVlcScaleMode(width: Int, height: Int) {
        if (isCurrentMpegProgramStream) {
            // mpg/mpeg 需要铺满屏幕，允许裁剪
            vlcPlayer.setScale(0f)
            vlcPlayer.setAspectRatio("${width}:${height}")
        } else {
            // 其他格式保持默认适配，避免字幕被裁掉
            vlcPlayer.setScale(0f)
            vlcPlayer.setAspectRatio(null)
        }
    }

    private fun preferBackendForUri(uri: String): Backend {
        // 部分封装格式（如 avi/mkv/mov）在 Exo 上兼容性不稳定，优先走 VLC。
        val normalized = uri.substringBefore('?').lowercase()
        return if (
            normalized.endsWith(".mpg") ||
            normalized.endsWith(".mpeg") ||
            normalized.endsWith(".avi") ||
            normalized.endsWith(".mkv") ||
            normalized.endsWith(".mov")
        ) {
            Backend.VLC
        } else {
            Backend.EXO
        }
    }

    private fun isMpegProgramStreamUri(uri: String): Boolean {
        val normalized = uri.substringBefore('?').lowercase()
        return normalized.endsWith(".mpg") || normalized.endsWith(".mpeg")
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        val sb = StringBuilder(bytes.size * 2)
        bytes.forEach { byte ->
            sb.append(String.format("%02x", byte))
        }
        return sb.toString()
    }

    companion object {
        private const val ASSET_SCHEME_PREFIX = "asset:///"
        private const val TAG = "KtvPlayer"
        private const val INVALID_TRACK_ID = -1
    }

    private data class ExoAudioTrack(
        val group: androidx.media3.common.TrackGroup,
        val trackIndex: Int
    )

    private data class ExoAudioTrackCandidate(
        val track: ExoAudioTrack,
        val descriptor: String
    )

    private data class AudioTrackRole(
        val exoOriginalTrack: ExoAudioTrack? = null,
        val exoAccompanimentTrack: ExoAudioTrack? = null,
        val vlcOriginalTrackId: Int? = null,
        val vlcAccompanimentTrackId: Int? = null
    ) {
        val supportsExo: Boolean
            get() = exoOriginalTrack != null && exoAccompanimentTrack != null
        val supportsVlc: Boolean
            get() = vlcOriginalTrackId != null && vlcAccompanimentTrackId != null
    }

    private enum class Backend {
        EXO, VLC
    }
}
