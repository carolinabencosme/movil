package com.example.texty.model

import com.google.firebase.Timestamp

data class Message(
    val id: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val text: String = "",
    //val createdAt: Timestamp = Timestamp.now()
    val createdAt: Timestamp? = null,
    val imageUrl: String? = null
)
