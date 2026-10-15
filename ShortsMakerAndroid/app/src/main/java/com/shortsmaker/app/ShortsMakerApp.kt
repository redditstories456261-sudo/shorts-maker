package com.shortsmaker.app

import android.app.Application
import com.shortsmaker.app.pipeline.Downloader

class ShortsMakerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Cheap and must happen before the first download; safe to call even if never used.
        Downloader.ensureInit(this)
    }
}
