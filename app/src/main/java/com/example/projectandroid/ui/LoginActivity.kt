package com.example.projectandroid.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.projectandroid.R
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase

class LoginActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_login)

    val emailInput = findViewById<EditText>(R.id.editEmail)
    val passwordInput = findViewById<EditText>(R.id.editPassword)
    val loginButton = findViewById<Button>(R.id.buttonLogin)
    val registerButton = findViewById<Button>(R.id.buttonRegister)

    loginButton.setOnClickListener {
      val email = emailInput.text.toString().trim()
      val password = passwordInput.text.toString().trim()
      if (email.isEmpty() || password.isEmpty()) return@setOnClickListener

      Firebase.auth.signInWithEmailAndPassword(email, password)
        .addOnSuccessListener {
          startActivity(Intent(this, ChatActivity::class.java))
          finish()
        }
        .addOnFailureListener { e ->
          Toast.makeText(this, e.localizedMessage ?: "Error", Toast.LENGTH_LONG).show()
        }
    }

    registerButton.setOnClickListener {
      startActivity(Intent(this, RegisterActivity::class.java))
    }
  }
}
