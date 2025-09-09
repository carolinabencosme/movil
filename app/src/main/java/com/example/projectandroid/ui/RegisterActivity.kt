package com.example.projectandroid.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.projectandroid.R
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class RegisterActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_register)

    val nameInput = findViewById<EditText>(R.id.editName)
    val emailInput = findViewById<EditText>(R.id.editEmail)
    val passwordInput = findViewById<EditText>(R.id.editPassword)
    val registerButton = findViewById<Button>(R.id.buttonRegister)

    registerButton.setOnClickListener {
      val name = nameInput.text.toString().trim()
      val email = emailInput.text.toString().trim()
      val password = passwordInput.text.toString().trim()
      if (name.isEmpty() || email.isEmpty() || password.isEmpty()) return@setOnClickListener

      Firebase.auth.createUserWithEmailAndPassword(email, password)
        .addOnSuccessListener { result ->
          val uid = result.user?.uid ?: return@addOnSuccessListener
          val data = mapOf(
            "name" to name,
            "uid" to uid,
          )
          Firebase.firestore.collection("users").document(uid).set(data)
            .addOnSuccessListener {
              startActivity(Intent(this, ChatActivity::class.java))
              finish()
            }
            .addOnFailureListener { e ->
              Toast.makeText(this, e.localizedMessage ?: "Error", Toast.LENGTH_LONG).show()
            }
        }
        .addOnFailureListener { e ->
          Toast.makeText(this, e.localizedMessage ?: "Error", Toast.LENGTH_LONG).show()
        }
    }
  }
}
