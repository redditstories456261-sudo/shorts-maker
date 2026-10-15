package com.shortsmaker.app.pipeline

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/**
 * Downloads a pasted video link (YouTube and 1000+ other sites yt-dlp supports) to a local MP4.
 * Only ever use this for videos you have the rights to repurpose - your own channel, licensed
 * footage, Creative Commons, explicit permission, etc. Automated downloading of other people's
 * videos without permission can violate that platform's Terms of Service and copyright law.
 */
object Downloader {

    @Volatile private var initialized = false

    fun ensureInit(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                YoutubeDL.getInstance().init(context)
                initialized = true
            }
        }
    }

    fun isUrl(s: String): Boolean = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://").containsMatchIn(s.trim())

    fun download(context: Context, url: String, onProgress: (Float, String) -> Unit): File {
        ensureInit(context)
        val outDir = File(context.getExternalFilesDir(null), "downloads").apply { mkdirs() }
        val request = YoutubeDLRequest(url).apply {
            addOption("-f", "bv*[height<=1080][ext=mp4]+ba[ext=m4a]/b[height<=1080][ext=mp4]/best")
            addOption("--merge-output-format", "mp4")
            addOption("--no-playlist")
            addOption("-o", "${outDir.absolutePath}/%(id)s.%(ext)s")
        }
        val info = YoutubeDL.getInstance().getInfo(request)
        YoutubeDL.getInstance().execute(request) { progress, _, line ->
            onProgress(progress / 100f, line ?: "")
        }
        val candidate = outDir.listFiles()?.filter { it.nameWithoutExtension.contains(info.id ?: "") }
            ?.maxByOrNull { it.lastModified() }
        return candidate ?: throw IllegalStateException("Download finished but the output file could not be found.")
    }
}
