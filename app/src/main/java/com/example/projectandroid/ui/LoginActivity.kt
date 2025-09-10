package com.example.projectandroid.ui

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.projectandroid.R
import com.example.projectandroid.util.ErrorLogger
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
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

      Firebase.auth.signInWithEmailAndPassword(email, password)
        .addOnSuccessListener {
          startActivity(Intent(this, ChatListActivity::class.java))
          finish()
        }
        .addOnFailureListener { e ->
          ErrorLogger.log(this, e)
          Toast.makeText(this, e.localizedMessage ?: getString(R.string.error_generic), Toast.LENGTH_LONG).show()
        }
    }

    registerButton.setOnClickListener {
      startActivity(Intent(this, RegisterActivity::class.java))
    }
  }
}
