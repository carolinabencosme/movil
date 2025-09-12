package com.example.texty.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.widget.addTextChangedListener
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.example.texty.R
import android.widget.Toast
import com.example.texty.repository.UserRepository
import com.example.texty.repository.FriendRequestRepository
import com.example.texty.util.AppLogger
import com.example.texty.util.ErrorLogger
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase

class SearchUserFragment : Fragment() {
    private val userRepository = UserRepository()
    private val friendRepository = FriendRequestRepository()
    private lateinit var adapter: UserAdapter
    private lateinit var currentUid: String

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_search_user, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val auth = Firebase.auth
        if (auth.currentUser == null) {
            startActivity(Intent(requireContext(), LoginActivity::class.java))
            requireActivity().finish()
            return
        }
        currentUid = auth.currentUser!!.uid

        val toolbar = view.findViewById<MaterialToolbar>(R.id.topAppBar)
        (requireActivity() as androidx.appcompat.app.AppCompatActivity).setSupportActionBar(toolbar)

        adapter = UserAdapter(
            onClick = { user ->
                friendRepository.areFriends(currentUid, user.uid) { isFriend ->
                    if (isFriend) {
                        val intent = Intent(requireContext(), ChatActivity::class.java).apply {
                            putExtra("recipientUid", user.uid)
                            putExtra("recipientName", user.displayName)
                        }
                        startActivity(intent)
                    } else {
                        Toast.makeText(requireContext(), R.string.error_not_friends, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onAddClick = { user ->
                friendRepository.sendRequest(currentUid, user.uid, onSuccess = {
                    val updated = adapter.currentList.map {
                        if (it.user.uid == user.uid) it.copy(requestStatus = "pending") else it
                    }
                    adapter.submitList(updated)
                }, onFailure = { e -> AppLogger.logError(requireContext(), e) })
            }
        )

        val recycler = view.findViewById<RecyclerView>(R.id.recyclerUsers)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        val searchInput = view.findViewById<TextInputEditText>(R.id.editSearch)
        searchInput.addTextChangedListener { text ->
            val q = text?.toString() ?: ""
            if (q.isBlank()) {
                adapter.submitList(emptyList())
            } else {
                userRepository.getUsersByDisplayName(q, onSuccess = { users ->
                    val items = users.filter { it.uid != currentUid }
                        .map { UserListItem(it, "none") }
                        .toMutableList()
                    adapter.submitList(items.toList())
                    items.forEachIndexed { index, item ->
                        friendRepository.areFriends(currentUid, item.user.uid) { isFriend ->
                            if (isFriend) {
                                items[index] = item.copy(requestStatus = "friend")
                                adapter.submitList(items.toList())
                            } else {
                                friendRepository.hasPendingRequest(currentUid, item.user.uid) { reqId ->
                                    if (reqId != null) {
                                        items[index] = item.copy(requestStatus = "pending")
                                        adapter.submitList(items.toList())
                                    }
                                }
                            }
                        }
                    }
                }, onFailure = { e ->
                    AppLogger.logError(requireContext(), e)
                })
            }
        }
    }
}
