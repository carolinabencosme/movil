package com.example.projectandroid.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.projectandroid.R
import com.example.projectandroid.model.ChatRoom
import com.example.projectandroid.util.AppLogger
import com.example.projectandroid.util.ErrorLogger
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import androidx.core.widget.addTextChangedListener
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale

class ChatListFragment : Fragment() {
    private val viewModel: ChatListViewModel by viewModels()
    private lateinit var adapter: ChatListAdapter
    private lateinit var searchInput: TextInputEditText
    private var allRooms: List<ChatRoom> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        setHasOptionsMenu(true)
        return inflater.inflate(R.layout.fragment_chat_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val currentUser = Firebase.auth.currentUser!!
        val toolbar = view.findViewById<MaterialToolbar>(R.id.topAppBar)
        (requireActivity() as androidx.appcompat.app.AppCompatActivity).setSupportActionBar(toolbar)

        adapter = ChatListAdapter { room ->
            val uid = room.contactUid.takeUnless { it.isBlank() }
            val name = room.contactName.takeUnless { it.isBlank() }
            if (uid == null || name == null) {
                val error = IllegalArgumentException("ChatRoom missing contactUid or contactName")
                ErrorLogger.log(requireContext(), error)
                return@ChatListAdapter
            }
            val intent = Intent(requireContext(), ChatActivity::class.java).apply {
                putExtra("recipientUid", uid)
                putExtra("recipientName", name)
            }
            startActivity(intent)
        }

        val recycler = view.findViewById<RecyclerView>(R.id.recyclerChats)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBar)
        val placeholder = view.findViewById<TextView>(R.id.textPlaceholder)

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        recycler.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))

        searchInput = view.findViewById<TextInputEditText>(R.id.editSearch)
        searchInput.addTextChangedListener { text ->
            filterRooms(text?.toString() ?: "")
        }

        viewModel.loading.observe(viewLifecycleOwner) { isLoading ->
            progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
        viewModel.rooms.observe(viewLifecycleOwner) { list ->
            allRooms = list
            if (list.isEmpty()) {
                placeholder.visibility = View.VISIBLE
                recycler.visibility = View.GONE
            } else {
                placeholder.visibility = View.GONE
                recycler.visibility = View.VISIBLE
                filterRooms(searchInput.text?.toString() ?: "")
            }
        }
        viewModel.error.observe(viewLifecycleOwner) { e ->
            e?.let { AppLogger.logError(requireContext(), it) }
        }
        viewModel.startListening(currentUser.uid)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_chat_list, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_logout -> {
                FirebaseAuth.getInstance().signOut()
                val intent = Intent(requireContext(), LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                requireActivity().finish()
                true
            }
            R.id.action_share_logs -> {
                AppLogger.shareLogs(requireContext())
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun filterRooms(query: String) {
        if (query.isBlank()) {
            adapter.submitList(allRooms)
        } else {
            val lower = query.lowercase(Locale.getDefault())
            adapter.submitList(allRooms.filter { it.contactName.lowercase(Locale.getDefault()).contains(lower) })
        }
    }
}
