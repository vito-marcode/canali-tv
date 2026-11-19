package it.mdv.dttchannels

import android.app.Application
import android.content.ComponentCallbacks2
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.imageLoader
import coil.memory.MemoryCache

/** App-wide Coil loader tuned for the box: big disk cache, cheap bitmaps, our own reveal animations. */
class DttApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        // 1 GB box shared by a dozen apps: a small decoded cache (all logos at tile size + a few artworks fit);
        // everything else comes back from the disk cache in a few ms
        .memoryCache { MemoryCache.Builder(this).maxSizeBytes(12 * 1024 * 1024).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(96L * 1024 * 1024).build() }
        // logos (Pi) and XMLTV artwork URLs are effectively immutable: always serve them from disk when we have them
        .respectCacheHeaders(false)
        .allowRgb565(true)          // half the memory for opaque artwork; logos with alpha stay ARGB
        .crossfade(false)           // reveal animations are done in Compose (see ui/Images.kt)
        .build()

    /**
     * Coil only clears its cache from TRIM_MEMORY_BACKGROUND up. Off screen nothing is drawn, so drop the decoded
     * bitmaps as soon as the UI is hidden: the cached process then holds no artwork at all.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) imageLoader.memoryCache?.clear()
    }
}
