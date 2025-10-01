package com.example.texty.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView // NUEVO
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide // NUEVO
import com.example.texty.R
import com.example.texty.model.ChatRoom
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import java.text.DateFormat

class ChatListAdapter(
    private val onClick: (ChatRoom) -> Unit,
) : ListAdapter<ChatRoom, ChatListAdapter.ChatRoomViewHolder>(DIFF_CALLBACK) {

    private var presenceByUser = emptyMap<String, Boolean>()

    class ChatRoomViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById(R.id.imageAvatar) // NUEVO
        val nameText: TextView = view.findViewById(R.id.textName)
        val lastMessageText: TextView = view.findViewById(R.id.textLastMessage)
        val statusView: View = view.findViewById(R.id.viewStatus)
        val unreadCountText: TextView = view.findViewById(R.id.textUnreadCount)
        val lastUpdatedText: TextView = view.findViewById(R.id.textLastUpdated)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatRoomViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat, parent, false)
        return ChatRoomViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatRoomViewHolder, position: Int) {
        val room = getItem(position)
        val context = holder.itemView.context

        // Nombre (grupo o individual)
        holder.nameText.text = if (room.isGroup) {
            room.groupName ?: "Grupo sin nombre"
        } else {
            val currentUserUid = Firebase.auth.currentUser?.uid
            val otherUid = room.participantIds.firstOrNull { it != currentUserUid }
            room.userNames[otherUid] ?: "Usuario desconocido"
        }

        // **Avatar** (photoUrl viene del ViewModel)
        val placeholder = if (room.isGroup) R.drawable.baseline_account_circle_24 else R.drawable.baseline_account_circle_24
        Glide.with(holder.itemView)
            .load(room.photoUrl)
            .placeholder(placeholder)
            .error(placeholder)
            .centerCrop()
            .into(holder.avatar)

        // **Preview**:
        // - Si requiere resincronizar: mostramos el aviso.
        // - Si no hay preview (primer chat): ocultamos el TextView (queda vacío).
        // - Si hay preview: lo mostramos tal cual (ya mapeaste "Imagen" desde el ViewModel).
        when {
            room.summaryRequiresResync -> {
                holder.lastMessageText.text = context.getString(R.string.chat_message_unavailable_resync)
                holder.lastMessageText.visibility = View.VISIBLE
            }
            room.lastMessagePreview.isNullOrBlank() -> {
                holder.lastMessageText.text = ""
                holder.lastMessageText.visibility = View.GONE // CAMBIO: ocultar cuando no hay mensajes
            }
            else -> {
                holder.lastMessageText.text = room.lastMessagePreview
                holder.lastMessageText.visibility = View.VISIBLE
            }
        }

        // Unread count
        val currentUid = Firebase.auth.currentUser?.uid
        val count = currentUid?.let { room.unreadCounts[it] } ?: 0
        holder.unreadCountText.apply {
            text = count.toString()
            visibility = if (count > 0) View.VISIBLE else View.GONE
        }

        // Hora última actualización
        val formattedTime = room.updatedAt?.toDate()?.let { timeFormatter.format(it) }
        holder.lastUpdatedText.apply {
            text = formattedTime ?: ""
            visibility = if (formattedTime != null) View.VISIBLE else View.GONE
        }

        // Estado online SOLO en chats individuales
        if (room.isGroup) {
            holder.statusView.visibility = View.GONE
        } else {
            holder.statusView.visibility = View.VISIBLE
            val otherUid = room.participantIds.firstOrNull { it != Firebase.auth.currentUser?.uid }
            val isOnline = otherUid?.let { presenceByUser[it] == true } ?: false
            holder.statusView.setBackgroundResource(
                if (isOnline) R.drawable.online_indicator else R.drawable.offline_indicator
            )
        }

        // Click para abrir el chat
        holder.itemView.setOnClickListener { onClick(room) }
    }

    fun updatePresence(map: Map<String, Boolean>) {
        presenceByUser = map
        notifyDataSetChanged()
    }

    companion object {
        private val timeFormatter = DateFormat.getTimeInstance(DateFormat.SHORT)
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
