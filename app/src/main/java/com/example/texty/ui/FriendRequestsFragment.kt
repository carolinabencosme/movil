package com.example.texty.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.texty.R
import com.example.texty.model.FriendRequest
import com.example.texty.repository.FriendRequestRepository
import com.example.texty.util.AppLogger
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class FriendRequestsFragment : Fragment() {
    private val repository = FriendRequestRepository()
    private lateinit var adapter: FriendRequestAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        return inflater.inflate(R.layout.fragment_friend_requests, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val toolbar = view.findViewById<MaterialToolbar>(R.id.topAppBar)
        (requireActivity() as androidx.appcompat.app.AppCompatActivity).setSupportActionBar(toolbar)

        adapter = FriendRequestAdapter(
            onAccept = { request -> accept(request) },
            onReject = { request -> reject(request) },
        )
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerRequests)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
    }

    override fun onStart() {
        super.onStart()
        loadRequests()
    }

    private fun loadRequests() {
        val uid = Firebase.auth.currentUser?.uid ?: return
        repository.getIncomingRequests(uid, onSuccess = { list ->
            adapter.submitList(list)
        }, onFailure = { e -> AppLogger.logError(requireContext(), e) })
    }

    private fun accept(request: FriendRequest) {
        repository.acceptRequest(request.id, request.fromUid, request.toUid, onSuccess = {
            loadRequests()
        }, onFailure = { e -> AppLogger.logError(requireContext(), e) })
    }

    private fun reject(request: FriendRequest) {
        repository.rejectRequest(request.id, onSuccess = {
            loadRequests()
        }, onFailure = { e -> AppLogger.logError(requireContext(), e) })
    }

    private class FriendRequestAdapter(
        val onAccept: (FriendRequest) -> Unit,
        val onReject: (FriendRequest) -> Unit,
    ) : ListAdapter<FriendRequest, FriendRequestAdapter.ViewHolder>(DIFF) {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameText: TextView = view.findViewById(R.id.textName)
            val acceptButton: MaterialButton = view.findViewById(R.id.buttonAccept)
            val rejectButton: MaterialButton = view.findViewById(R.id.buttonReject)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_friend_request, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val request = getItem(position)
            //holder.nameText.text = request.fromUid
            Firebase.firestore.collection("users").document(request.fromUid)
                .get()
                .addOnSuccessListener { snap ->
                    val displayName = snap.getString("displayName") ?: request.fromUid
                    holder.nameText.text = displayName
                }
            holder.acceptButton.setOnClickListener { onAccept(request) }
            holder.rejectButton.setOnClickListener { onReject(request) }
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<FriendRequest>() {
                override fun areItemsTheSame(oldItem: FriendRequest, newItem: FriendRequest) = oldItem.id == newItem.id
                override fun areContentsTheSame(oldItem: FriendRequest, newItem: FriendRequest) = oldItem == newItem
            }
        }
    }
}
