package com.example.texty.ui

import android.os.Bundle
import android.widget.ImageButton
import androidx.activity.ComponentActivity
import com.example.texty.R
import com.github.chrisbanes.photoview.PhotoView

/**
 * Actividad ligera para mostrar adjuntos de imagen a pantalla completa.
 */

/**
* Actividad de previsualización de imágenes.
*
* Flujo:
* 1) Recibe una clave de caché vía Intent ([EXTRA_KEY]).
* 2) Recupera el [Bitmap] desde [ImagePreviewCache] usando esa clave.
* 3) Muestra la imagen en un [PhotoView] para permitir zoom/drag.
* 4) Se cierra al tocar la imagen o al pulsar el botón de cerrar.
*
* Notas:
* - Si la clave o el bitmap no existen, la actividad se cierra inmediatamente para
*   evitar mostrar una vista vacía.
* - Esta actividad asume que el ciclo de carga/descarga del bitmap lo gestiona
*   el caché externo ([ImagePreviewCache]).
*/
class ImagePreviewActivity : ComponentActivity() {
    /**
     * Punto de entrada de la Activity.
     *
     * - Infla el layout `activity_image_preview`.
     * - Obtiene la clave del Intent ([EXTRA_KEY]); si falta, finaliza.
     * - Obtiene el bitmap del caché; si falta, finaliza.
     * - Asigna el bitmap al [PhotoView] y configura:
     *   - Tap sobre la imagen para cerrar (`setOnPhotoTapListener`).
     *   - Botón de cerrar (ImageButton) para finalizar la Activity.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_preview)

        //sin clave no imagen
        val key = intent.getStringExtra(EXTRA_KEY) ?: run { finish(); return }
        //si no esta en el bitmap tampoco
        val bmp = ImagePreviewCache.get(key) ?: run { finish(); return }

        //imagen con zoom
        findViewById<PhotoView>(R.id.photoView).apply {
            setImageBitmap(bmp)
            setOnPhotoTapListener { _, _, _ -> finish() } // tap para cerrar
        }
        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }
    }
    /**
     * Constantes de la Activity.
     *
     * @property EXTRA_KEY Clave del Intent para recuperar el identificador del bitmap en caché.
     */

    companion object { const val EXTRA_KEY = "preview_key" }
}
