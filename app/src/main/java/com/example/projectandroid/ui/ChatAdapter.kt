package com.example.projectandroid.ui

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.projectandroid.R
import com.example.projectandroid.model.Message
import java.text.SimpleDateFormat
import java.util.Locale

class ChatAdapter(
    private val myUid: String,
) : ListAdapter<Message, ChatAdapter.MessageViewHolder>(DIFF_CALLBACK) {

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: LinearLayout = view.findViewById(R.id.messageRoot)
        val messageText: TextView = view.findViewById(R.id.textMessage)
        val timeText: TextView = view.findViewById(R.id.textTime)
    }

    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = getItem(position)
        holder.messageText.text = message.text
        //holder.timeText.text = timeFormatter.format(message.createdAt.toDate())
        // nuevo
        val ts = message.createdAt
        if (ts != null) {
            holder.timeText.text = timeFormatter.format(ts.toDate())
            holder.timeText.visibility = View.VISIBLE
        } else {
            holder.timeText.text = ""
            holder.timeText.visibility = View.INVISIBLE
        }

        if (message.senderId == myUid) {
            holder.root.gravity = Gravity.END
            holder.messageText.setBackgroundResource(R.drawable.bg_bubble_me)
        } else {
            holder.root.gravity = Gravity.START
            holder.messageText.setBackgroundResource(R.drawable.bg_bubble_other)
        }
    }

    fun addOne(message: Message) {
        val newList = currentList.toMutableList()
        newList.add(message)
        submitList(newList)
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Message>() {
            override fun areItemsTheSame(oldItem: Message, newItem: Message): Boolean {
                return oldItem.createdAt == newItem.createdAt
            }

            override fun areContentsTheSame(oldItem: Message, newItem: Message): Boolean {
                return oldItem == newItem
            }
        }
    }
}
