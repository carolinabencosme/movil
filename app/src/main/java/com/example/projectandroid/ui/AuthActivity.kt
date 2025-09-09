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

class AuthActivity : AppCompatActivity() {

    private val auth by lazy { Firebase.auth }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        val emailField = findViewById<EditText>(R.id.editEmail)
        val passwordField = findViewById<EditText>(R.id.editPassword)
        val loginButton = findViewById<Button>(R.id.buttonLogin)
        val registerButton = findViewById<Button>(R.id.buttonRegister)

        loginButton.setOnClickListener {
            val email = emailField.text.toString().trim()
            val password = passwordField.text.toString()
            if (email.isEmpty() || password.isEmpty()) return@setOnClickListener
            auth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener { openChat() }
                .addOnFailureListener { Toast.makeText(this, it.message, Toast.LENGTH_SHORT).show() }
        }

        registerButton.setOnClickListener {
            val email = emailField.text.toString().trim()
            val password = passwordField.text.toString()
            if (email.isEmpty() || password.isEmpty()) return@setOnClickListener
            auth.createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener { openChat() }
                .addOnFailureListener { Toast.makeText(this, it.message, Toast.LENGTH_SHORT).show() }
        }
    }

    override fun onStart() {
        super.onStart()
        if (auth.currentUser != null) {
            openChat()
        }
    }

    private fun openChat() {
        startActivity(Intent(this, ChatActivity::class.java))
        finish()
    }
}
