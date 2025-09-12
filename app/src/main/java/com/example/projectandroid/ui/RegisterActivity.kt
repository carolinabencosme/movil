package com.example.projectandroid.ui

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.projectandroid.R
import com.example.projectandroid.model.User
import com.example.projectandroid.util.AppLogger
import com.google.android.material.appbar.MaterialToolbar
import android.widget.Button
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.ktx.auth
import com.google.firebase.auth.ktx.userProfileChangeRequest
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

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

      Firebase.auth.createUserWithEmailAndPassword(email, password)
        .addOnSuccessListener { result ->
          val user = result.user ?: return@addOnSuccessListener
          // Update the FirebaseAuth profile with the provided display name
          val profileUpdates = userProfileChangeRequest { displayName = name }
          user.updateProfile(profileUpdates).addOnFailureListener { e -> AppLogger.logError(this, e) }

          val profile = User(
            uid = user.uid,
            displayName = name,
            photoUrl = user.photoUrl?.toString(),
            isOnline = true,
          )

          Firebase.firestore.collection("users").document(user.uid).set(profile)
            .addOnSuccessListener {
              startActivity(Intent(this, MainActivity::class.java))
              finish()
            }
            .addOnFailureListener { e ->
              AppLogger.logError(this, e)
              Toast.makeText(this, e.localizedMessage ?: getString(R.string.error_generic), Toast.LENGTH_LONG).show()
            }
        }
        .addOnFailureListener { e ->
          AppLogger.logError(this, e)
          Toast.makeText(this, e.localizedMessage ?: getString(R.string.error_generic), Toast.LENGTH_LONG).show()
        }
    }
  }

  override fun onSupportNavigateUp(): Boolean {
    finish()
    return true
  }
}
