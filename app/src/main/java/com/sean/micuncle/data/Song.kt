package com.sean.micuncle.data

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val videoUri: String, // 默认视频路径 (通常是原唱)
    val accompanimentUri: String? = null, // 伴奏视频路径 (可选)
    val lrcUri: String? = null, // 歌词文件路径
    val coverUri: String? = null, // 封面图路径 (可选，如果没有则用默认图)
    val isFavorite: Boolean = false,
    val playCount: Int = 0
)
