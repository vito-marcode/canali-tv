package it.mdv.dttchannels

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

/** App-wide Coil loader tuned for the box: big disk cache, cheap bitmaps, our own reveal animations. */
class DttApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(96L * 1024 * 1024).build() }
        // logos (Pi) and XMLTV artwork URLs are effectively immutable: always serve them from disk when we have them
        .respectCacheHeaders(false)
        .allowRgb565(true)          // half the memory for opaque artwork; logos with alpha stay ARGB
        .crossfade(false)           // reveal animations are done in Compose (see ui/Images.kt)
        .build()
}
