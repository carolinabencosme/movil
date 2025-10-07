package com.example.texty.ui

import android.os.Bundle
import android.widget.ImageButton
import androidx.activity.ComponentActivity
import com.example.texty.R
import com.github.chrisbanes.photoview.PhotoView

class ImagePreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_preview)

        val key = intent.getStringExtra(EXTRA_KEY) ?: run { finish(); return }
        val bmp = ImagePreviewCache.get(key) ?: run { finish(); return }

        findViewById<PhotoView>(R.id.photoView).apply {
            setImageBitmap(bmp)
            setOnPhotoTapListener { _, _, _ -> finish() } // tap para cerrar
        }
        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }
    }
    companion object { const val EXTRA_KEY = "preview_key" }
}
