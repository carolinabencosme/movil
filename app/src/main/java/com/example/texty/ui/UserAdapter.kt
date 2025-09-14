package com.example.texty.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.texty.R
import com.example.texty.model.User
import com.google.android.material.button.MaterialButton

/**
 * Item combining a user with friend request status.
 */
data class UserListItem(val user: User, val requestStatus: String)

class UserAdapter(
    private val onClick: (User) -> Unit,
    private val onAddClick: (User) -> Unit,
) : ListAdapter<UserListItem, UserAdapter.UserViewHolder>(DIFF_CALLBACK) {

    class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.textName)
        val statusView: View = view.findViewById(R.id.viewStatus)
        val addButton: MaterialButton = view.findViewById(R.id.buttonAdd)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_user, parent, false)
        return UserViewHolder(view)
    }

    override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
        val item = getItem(position)
        val user = item.user
        holder.nameText.text = user.displayName
        holder.statusView.setBackgroundResource(
            if (user.isOnline) R.drawable.online_indicator else R.drawable.offline_indicator
        )
        holder.itemView.setOnClickListener { onClick(user) }
        when (item.requestStatus) {
            "pending" -> {
                holder.addButton.text = "Pendiente"
                holder.addButton.isEnabled = false
            }
            "friend" -> {
                holder.addButton.text = "Amigos"
                holder.addButton.isEnabled = false
            }
            else -> {
                holder.addButton.text = "Añadir"
                holder.addButton.isEnabled = true
                holder.addButton.setOnClickListener { onAddClick(user) }
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<UserListItem>() {
            override fun areItemsTheSame(oldItem: UserListItem, newItem: UserListItem): Boolean {
                return oldItem.user.uid == newItem.user.uid
            }

            override fun areContentsTheSame(oldItem: UserListItem, newItem: UserListItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
