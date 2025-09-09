package com.example.projectandroid.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.projectandroid.R
import com.example.projectandroid.model.Message

class ChatAdapter(
    private val myUid: String,
    private val items: MutableList<Message>
) : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageText: TextView = view.findViewById(R.id.textMessage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = items[position]
        holder.messageText.text = message.text
        val background = if (message.senderId == myUid) {
            R.drawable.bg_bubble_me
        } else {
            R.drawable.bg_bubble_other
        }
        holder.messageText.setBackgroundResource(background)
    }

    override fun getItemCount(): Int = items.size

    fun submit(newList: List<Message>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
    }

    fun addOne(message: Message) {
        items.add(message)
        notifyItemInserted(items.size - 1)
    }
}
