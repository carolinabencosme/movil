package com.example.projectandroid.ui

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.projectandroid.R
import com.example.projectandroid.util.AppLogger
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase

class LoginActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_login)

    val emailInput = findViewById<TextInputEditText>(R.id.editEmail)
    val passwordInput = findViewById<TextInputEditText>(R.id.editPassword)
    val loginButton = findViewById<MaterialButton>(R.id.buttonLogin)
    val registerButton = findViewById<MaterialButton>(R.id.buttonRegister)
    val progressBar = findViewById<ProgressBar>(R.id.progressBar)

    loginButton.setOnClickListener {
      val email = emailInput.text.toString().trim()
      val password = passwordInput.text.toString().trim()

      var isValid = true
      if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
        emailInput.error = getString(R.string.invalid_email)
        isValid = false
      }
      if (password.length < 6) {
        passwordInput.error = getString(R.string.invalid_password)
        isValid = false
      }
      if (!isValid) return@setOnClickListener

      progressBar.visibility = View.VISIBLE
      loginButton.isEnabled = false

      Firebase.auth.signInWithEmailAndPassword(email, password)
        .addOnCompleteListener {
          progressBar.visibility = View.GONE
          loginButton.isEnabled = true
        }
        .addOnSuccessListener {
          startActivity(Intent(this, SearchUserActivity::class.java))
          finish()
        }
        .addOnFailureListener { e ->
          AppLogger.logError(this, e)
          val message = when (e) {
            is FirebaseAuthInvalidUserException -> getString(R.string.error_invalid_user)
            is FirebaseAuthInvalidCredentialsException -> getString(R.string.error_invalid_credentials)
            is FirebaseNetworkException -> getString(R.string.error_network)
            else -> getString(R.string.error_generic)
          }
          Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    registerButton.setOnClickListener {
      startActivity(Intent(this, RegisterActivity::class.java))
    }
  }
}
