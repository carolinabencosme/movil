package com.example.projectandroid.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.projectandroid.R
import com.example.projectandroid.model.ChatRoom
import java.text.SimpleDateFormat
import java.util.Locale

class ChatListAdapter(
    private val onClick: (ChatRoom) -> Unit,
) : ListAdapter<ChatRoom, ChatListAdapter.ChatRoomViewHolder>(DIFF_CALLBACK) {

    class ChatRoomViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.textName)
        val lastMessageText: TextView = view.findViewById(R.id.textLastMessage)
        val timeText: TextView = view.findViewById(R.id.textTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatRoomViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_room, parent, false)
        return ChatRoomViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatRoomViewHolder, position: Int) {
        val room = getItem(position)
        holder.nameText.text = room.contactName
        holder.lastMessageText.text = room.lastMessage
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        holder.timeText.text = timeFormat.format(room.updatedAt.toDate())
        holder.itemView.setOnClickListener { onClick(room) }
    }

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
