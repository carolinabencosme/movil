package com.example.texty.ui

import android.graphics.Bitmap
import androidx.collection.LruCache

/**
 * Caché en memoria que comparte bitmaps entre la lista y la vista de detalle.
 */
object ImagePreviewCache {
    private val cache = LruCache<String, Bitmap>(8)
    fun put(key: String, bmp: Bitmap) { cache.put(key, bmp) }
    fun get(key: String): Bitmap? = cache.get(key)
    fun remove(key: String) { cache.remove(key) }
}
