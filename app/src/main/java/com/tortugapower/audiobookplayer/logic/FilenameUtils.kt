package com.tortugapower.audiobookplayer.logic

object FilenameUtils {
    fun sanitizeFilename(filename: String): String {
        var sanitized = filename.replace("/", "_").replace("\\", "_")
        sanitized = sanitized.replace(Regex("[<>:\"/\\\\|?*]"), "_")
        sanitized = sanitized.replace(Regex("__+"), "_")
        sanitized = sanitized.trim('_', '.')
        if (sanitized.isEmpty()) {
            return "untitled_file"
        }
        return sanitized
    }
}
