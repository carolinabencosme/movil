package com.example.texty.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.texty.R
import com.example.texty.model.ChatRoom
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class ChatListAdapter(
    private val onClick: (ChatRoom) -> Unit,
) : ListAdapter<ChatRoom, ChatListAdapter.ChatRoomViewHolder>(DIFF_CALLBACK) {

    class ChatRoomViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.textName)
        val lastMessageText: TextView = view.findViewById(R.id.textLastMessage)
        val statusView: View = view.findViewById(R.id.viewStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatRoomViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat, parent, false)
        return ChatRoomViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatRoomViewHolder, position: Int) {
        val room = getItem(position)

        // Mostrar nombre (grupo o individual)
        holder.nameText.text = if (room.isGroup) {
            room.groupName ?: "Grupo sin nombre"
        } else {
            val currentUserUid = Firebase.auth.currentUser?.uid
            val otherUid = room.participantIds.firstOrNull { it != currentUserUid }
            room.userNames[otherUid] ?: "Usuario desconocido"
        }

        // Último mensaje
        holder.lastMessageText.apply {
            text = room.lastMessage
            visibility = View.VISIBLE
        }

        // Estado online SOLO en chats individuales
        if (room.isGroup) {
            holder.statusView.visibility = View.GONE
        } else {
            holder.statusView.visibility = View.VISIBLE
            val otherUid = room.participantIds.firstOrNull { it != Firebase.auth.currentUser?.uid }
            if (otherUid != null) {
                Firebase.firestore.collection("users").document(otherUid).get()
                    .addOnSuccessListener { snapshot ->
                        val online = snapshot.getBoolean("isOnline") == true
                        holder.statusView.setBackgroundResource(
                            if (online) R.drawable.online_indicator
                            else R.drawable.offline_indicator
                        )
                    }
            } else {
                holder.statusView.setBackgroundResource(R.drawable.offline_indicator)
            }
        }

        // Click para abrir el chat
        holder.itemView.setOnClickListener { onClick(room) }
    }


    /*override fun onBindViewHolder(holder: ChatRoomViewHolder, position: Int) {
        val room = getItem(position)
        holder.nameText.text = room.contactName
        holder.lastMessageText.apply {
            text = room.lastMessage
            visibility = View.VISIBLE
        }
        holder.statusView.setBackgroundResource(R.drawable.offline_indicator)
        Firebase.firestore.collection("users").document(room.contactUid).get()
            .addOnSuccessListener { snapshot ->
                val online = snapshot.getBoolean("isOnline") == true
                holder.statusView.setBackgroundResource(if (online) R.drawable.online_indicator else R.drawable.offline_indicator)
            }
        holder.itemView.setOnClickListener { onClick(room) }
    }*/

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ChatRoom>() {
            override fun areItemsTheSame(oldItem: ChatRoom, newItem: ChatRoom): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: ChatRoom, newItem: ChatRoom): Boolean {
                return oldItem == newItem
            }
        }
    }
}
