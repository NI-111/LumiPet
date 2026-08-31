package com.lumi.pet

import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import java.io.File

class ScreenshotObserver(private val onDetected: () -> Unit) {

    private val observers = mutableListOf<FileObserver>()

    private val paths = listOf(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            .resolve("Screenshots").absolutePath,
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            .resolve("Screenshots").absolutePath,
        "/storage/emulated/0/Pictures/Screenshots",
        "/storage/emulated/0/DCIM/Screenshots"
    ).distinct()

    fun start() {
        for (p in paths) {
            val dir = File(p)
            if (!dir.exists()) continue
            val obs = object : FileObserver(dir, CREATE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && isImage(path)) {
                        Handler(Looper.getMainLooper()).post { onDetected() }
                    }
                }
            }
            obs.startWatching()
            observers.add(obs)
        }
    }

    private fun isImage(name: String): Boolean {
        val l = name.lowercase()
        return l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg")
    }

    fun stop() {
        observers.forEach { it.stopWatching() }
        observers.clear()
    }
}