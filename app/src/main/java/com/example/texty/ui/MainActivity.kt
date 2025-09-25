package com.example.texty.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.texty.R
import com.example.texty.repository.KeyRepository
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // -------------------- Launchers por permiso --------------------

    // Notificaciones (Android 13+)
    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                sendFcmToken()
            } else {
                Log.i(TAG, "POST_NOTIFICATIONS no concedido")
            }
        }

    // Cámara
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                openCamera()
            } else {
                Log.i(TAG, "CAMERA no concedido")
            }
        }

    // Galería (solo si NO usas Photo Picker en ≤ Android 12)
    private val galleryPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                openGalleryLegacy()
            } else {
                Log.i(TAG, "READ_* no concedido")
            }
        }

    // Photo Picker moderno
    private val pickMedia =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                handlePickedImage(uri)
            } else {
                Log.i(TAG, "No se seleccionó imagen")
            }
        }

    // -------------------- Ciclo de vida --------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNavigation)
        if (savedInstanceState == null) {
            replaceFragment(ChatListFragment())
        }

        FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
            lifecycleScope.launch {
                KeyRepository.getInstance(applicationContext).ensureLocalKeys(uid)
            }
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_chats -> { replaceFragment(ChatListFragment()); true }
                R.id.navigation_search -> { replaceFragment(SearchUserFragment()); true }
                R.id.navigation_requests -> { replaceFragment(FriendRequestsFragment()); true }
                R.id.navigation_profile -> { replaceFragment(ProfileFragment()); true }
                else -> false
            }
        }
    }

    override fun onStart() {
        super.onStart()
        setOnlineStatus(true)

        // Solo pedimos notificaciones al iniciar (Android 13+).
        requestNotificationsWithRationale()

        maybePromptMediaPermsOnFirstRun()

        FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
            lifecycleScope.launch {
                KeyRepository.getInstance(applicationContext).refreshOneTimePreKeysIfNeeded(uid)
            }
        }
    }

    override fun onStop() {
        setOnlineStatus(false)
        super.onStop()
    }

    private fun setOnlineStatus(online: Boolean) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        Firebase.firestore.collection("users").document(uid).update("isOnline", online)
    }

    // -------------------- Notificaciones: token FCM --------------------

    private fun sendFcmToken() {
        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.w(TAG, "Fetching FCM registration token failed", task.exception)
                    return@addOnCompleteListener
                }

                val token = task.result
                val user = FirebaseAuth.getInstance().currentUser ?: return@addOnCompleteListener

                Firebase.firestore.collection("users")
                    .document(user.uid)
                    .set(
                        mapOf("fcmTokens" to FieldValue.arrayUnion(token)),
                        SetOptions.merge()
                    )
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Error saving token FCM", e)
                    }
            }
    }

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    // -------------------- Permisos: mensajes por permiso --------------------

    /** Notificaciones (Android 13+) */
    private fun requestNotificationsWithRationale() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            sendFcmToken(); return
        }
        val perm = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            sendFcmToken(); return
        }
        if (shouldShowRequestPermissionRationale(perm)) {
            showRationaleDialog(
                title = "Permitir notificaciones",
                message = "Te avisaremos cuando tengas nuevos mensajes, incluso con la app cerrada."
            ) { notifPermissionLauncher.launch(perm) }
        } else {
            notifPermissionLauncher.launch(perm)
        }
    }

    /** Cámara */
    fun requestCameraWithRationale() {
        val perm = Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            openCamera(); return
        }
        if (shouldShowRequestPermissionRationale(perm)) {
            showRationaleDialog(
                title = "Acceso a la cámara",
                message = "Necesitamos la cámara para que puedas tomar fotos y enviarlas en el chat."
            ) { cameraPermissionLauncher.launch(perm) }
        } else {
            cameraPermissionLauncher.launch(perm)
        }
    }

    /** Galería
     *  - Recomendado: Photo Picker (sin permisos en Android 13+)
     *  - Alternativa legacy: pedir READ_* y abrir Intent a la galería
     */
    fun requestGalleryWithRationale(usePhotoPicker: Boolean = true) {
        if (usePhotoPicker) {
            openPhotoPicker(); return
        }
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            openGalleryLegacy(); return
        }
        if (shouldShowRequestPermissionRationale(perm)) {
            showRationaleDialog(
                title = "Acceso a tus fotos",
                message = "Necesitamos acceder a tus imágenes para adjuntarlas en las conversaciones."
            ) { galleryPermissionLauncher.launch(perm) }
        } else {
            galleryPermissionLauncher.launch(perm)
        }
    }

    private fun showRationaleDialog(title: String, message: String, onProceed: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Continuar") { _, _ -> onProceed() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // -------------------- Acciones posteriores a permisos --------------------

    /** Abre cámara (implementa tu flujo real aquí) */
    private fun openCamera() {
        // TODO: implementar Intent de cámara o flujo de captura (CameraX, etc.)
        Log.d(TAG, "openCamera()")
    }

    /** Abre Photo Picker moderno (recomendado) */
    private fun openPhotoPicker() {
        pickMedia.launch(
            PickVisualMediaRequest(
                ActivityResultContracts.PickVisualMedia.ImageOnly
            )
        )
    }

    /** Recibe la Uri del Photo Picker */
    private fun handlePickedImage(uri: android.net.Uri) {
        // TODO: subir/adjuntar la imagen seleccionada
        Log.d(TAG, "Imagen seleccionada: $uri")
    }

    /** Alternativa legacy para abrir galería (si pediste READ_*) */
    private fun openGalleryLegacy() {
        // TODO: tu Intent.ACTION_GET_CONTENT / ACTION_PICK si decides no usar Photo Picker
        Log.d(TAG, "openGalleryLegacy()")
    }

    // Guarda en SharedPreferences que ya mostramos el onboarding de permisos de media
    private fun maybePromptMediaPermsOnFirstRun() {
        val prefs = getSharedPreferences("texty_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("media_perms_prompted", false)) return

        // 1) Cámara (si no está concedido)
        val camGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!camGranted) {
            requestCameraWithRationale()
        }

        // 2) Galería SOLO si estás en Android 12 o menor (API <= 32) y no usas Photo Picker.
        //   En Android 13+ con Photo Picker NO hay permiso de galería que pedir.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            requestGalleryPermissionOnlyIfNeeded()
        }

        prefs.edit().putBoolean("media_perms_prompted", true).apply()
    }

    /** Pide SOLO el permiso de galería sin abrir pickers ni intents (legacy, API <= 32). */
    private fun requestGalleryPermissionOnlyIfNeeded() {
        val perm = Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) return

        if (shouldShowRequestPermissionRationale(perm)) {
            showRationaleDialog(
                title = "Acceso a tus fotos",
                message = "Para adjuntar imágenes desde tu galería en este dispositivo, necesitamos permiso de lectura."
            ) { galleryPermissionLauncher.launch(perm) }
        } else {
            galleryPermissionLauncher.launch(perm)
        }
    }

}
