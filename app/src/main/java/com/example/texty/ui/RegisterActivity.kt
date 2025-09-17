package com.example.texty.ui

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.texty.R
import com.example.texty.crypto.KeyManager
import com.example.texty.model.User
import com.example.texty.repository.KeyRepository
import com.example.texty.util.AppLogger
import com.google.android.material.appbar.MaterialToolbar
import android.widget.Button
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.ktx.auth
import com.google.firebase.auth.ktx.userProfileChangeRequest
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class RegisterActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_register)

    val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    val nameLayout = findViewById<TextInputLayout>(R.id.nameLayout)
    val nameInput = findViewById<TextInputEditText>(R.id.editName)
    val emailLayout = findViewById<TextInputLayout>(R.id.emailLayout)
    val emailInput = findViewById<TextInputEditText>(R.id.editEmail)
    val passwordLayout = findViewById<TextInputLayout>(R.id.passwordLayout)
    val passwordInput = findViewById<TextInputEditText>(R.id.editPassword)
    val registerButton = findViewById<Button>(R.id.buttonRegister)

    registerButton.setOnClickListener {
      val name = nameInput.text.toString().trim()
      val email = emailInput.text.toString().trim()
      val password = passwordInput.text.toString().trim()

      nameLayout.error = null
      emailLayout.error = null
      passwordLayout.error = null

      var isValid = true
      if (name.isBlank()) {
        nameLayout.error = getString(R.string.required_field)
        isValid = false
      }
      if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
        emailLayout.error = getString(R.string.invalid_email)
        isValid = false
      }
      if (password.length < 6) {
        passwordLayout.error = getString(R.string.invalid_password)
        isValid = false
      }
      if (!isValid) return@setOnClickListener

      lifecycleScope.launch {
        registerButton.isEnabled = false
        try {
          val authResult = Firebase.auth.createUserWithEmailAndPassword(email, password).await()
          val user = authResult.user ?: throw IllegalStateException("Unable to create user")

          val profileUpdates = userProfileChangeRequest { displayName = name }
          user.updateProfile(profileUpdates).await()

          val keyManager = KeyManager(applicationContext)
          val keyResult = withContext(Dispatchers.IO) { keyManager.ensureKeyBundle() }

          val profile = User(
            uid = user.uid,
            displayName = name,
            photoUrl = user.photoUrl?.toString(),
            isOnline = true,
            identityPublicKey = keyResult.bundle.identityPublicKey,
            identitySignaturePublicKey = keyResult.bundle.identitySignaturePublicKey,
            signedPreKeyId = keyResult.bundle.signedPreKeyId,
            signedPreKey = keyResult.bundle.signedPreKey,
            signedPreKeySignature = keyResult.bundle.signedPreKeySignature,
            oneTimePreKeys = keyResult.bundle.oneTimePreKeys,
          )

          Firebase.firestore.collection("users").document(user.uid).set(profile).await()

          KeyRepository.getInstance(applicationContext).cacheBundle(user.uid, keyResult.bundle)

          startActivity(Intent(this@RegisterActivity, MainActivity::class.java))
          finish()
        } catch (e: Exception) {
          AppLogger.logError(this@RegisterActivity, e)
          Toast.makeText(
            this@RegisterActivity,
            e.localizedMessage ?: getString(R.string.error_generic),
            Toast.LENGTH_LONG
          ).show()
        } finally {
          registerButton.isEnabled = true
        }
      }
    }
  }

  override fun onSupportNavigateUp(): Boolean {
    finish()
    return true
  }
}
