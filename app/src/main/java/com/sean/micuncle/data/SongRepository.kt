package com.sean.micuncle.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SongRepository(private val context: Context) {

    suspend fun scanAssets(): List<Song> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Song>()
        try {
            val assetManager = context.assets
            val files = assetManager.list("videos") ?: emptyArray()

            // 临时存储：key = "歌手-歌名"
            val tempMap = mutableMapOf<String, TempSongData>()

            files.forEach { fileName ->
                // 当前主命名规范（默认可直接播放）：
                // "歌手-歌名(720高清)-国语-流行.mpg"
                // 兼容旧命名：*_vocals.* / *_inst.* / *.lrc
                val nameWithoutExt = fileName.substringBeforeLast(".")
                val ext = fileName.substringAfterLast(".").lowercase()
                val isVideoExt = ext in SUPPORTED_VIDEO_EXTENSIONS

                val type = when {
                    ext == "lrc" -> FileType.LRC
                    isVideoExt && nameWithoutExt.endsWith("_inst", ignoreCase = true) -> FileType.INST
                    isVideoExt && nameWithoutExt.endsWith("_vocals", ignoreCase = true) -> FileType.VOCAL
                    isVideoExt -> FileType.SINGLE_VIDEO
                    else -> FileType.UNKNOWN
                }

                if (type != FileType.UNKNOWN) {
                    val baseKey = when (type) {
                        FileType.INST, FileType.VOCAL -> nameWithoutExt.replace(
                            Regex("_(inst|vocals)$", RegexOption.IGNORE_CASE),
                            ""
                        )
                        else -> nameWithoutExt
                    }

                    val data = tempMap.getOrPut(baseKey) { TempSongData() }
                    val fullPath = "asset:///videos/$fileName"

                    when (type) {
                        FileType.LRC -> data.lrcPath = fullPath
                        FileType.INST -> data.instPath = fullPath
                        FileType.VOCAL -> data.vocalPath = fullPath
                        FileType.SINGLE_VIDEO -> data.singleVideoPath = fullPath
                        else -> {}
                    }
                }
            }

            // 4. 组装 Song 对象
            tempMap.forEach { (key, data) ->
                if (data.vocalPath != null || data.instPath != null || data.singleVideoPath != null) {
                    val (artist, title) = parseArtistAndTitle(key)
                    // 当前策略统一按单文件处理：优先使用主视频，其次兼容历史资源
                    val mainUri = data.singleVideoPath ?: data.vocalPath ?: data.instPath ?: ""

                    songs.add(
                        Song(
                            id = key, // 使用文件名作为ID
                            title = title,
                            artist = artist,
                            videoUri = mainUri,
                            accompanimentUri = null,
                            lrcUri = data.lrcPath
                        )
                    )
                }
            }

        } catch (e: Exception) {
            Log.e("SongRepository", "Error scanning assets", e)
        }
        return@withContext songs
    }

    private data class TempSongData(
        var vocalPath: String? = null,
        var instPath: String? = null,
        var singleVideoPath: String? = null,
        var lrcPath: String? = null
    )

    private enum class FileType {
        VOCAL, INST, SINGLE_VIDEO, LRC, UNKNOWN
    }

    private fun parseArtistAndTitle(baseKey: String): Pair<String, String> {
        val separatorIndex = baseKey.indexOf('-')
        if (separatorIndex <= 0) {
            return "未知歌手" to baseKey.trim()
        }

        val artist = baseKey.substring(0, separatorIndex).trim().ifEmpty { "未知歌手" }
        val songPart = baseKey.substring(separatorIndex + 1).trim()
        val titleSegment = songPart.substringBefore("-").trim().ifEmpty { songPart }
        val cleanedTitle = titleSegment
            .replace(Regex("[\\(\\[].*?[\\)\\]]"), "")
            .trim()
            .ifEmpty { titleSegment }

        return artist to cleanedTitle
    }

    companion object {
        private val SUPPORTED_VIDEO_EXTENSIONS = setOf("mp4", "mpg", "mpeg", "mkv", "avi", "mov")
    }
}
