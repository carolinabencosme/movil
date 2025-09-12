package com.example.projectandroid.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import com.example.projectandroid.R
import com.example.projectandroid.repository.UserRepository
import com.example.projectandroid.util.AppLogger
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase

class SearchUserFragment : Fragment() {
    private val userRepository = UserRepository()
    private lateinit var adapter: UserAdapter

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

        val toolbar = view.findViewById<Toolbar>(R.id.topAppBar)
        (requireActivity() as androidx.appcompat.app.AppCompatActivity).setSupportActionBar(toolbar)

        adapter = UserAdapter { user ->
            val intent = Intent(requireContext(), ChatActivity::class.java).apply {
                putExtra("recipientUid", user.uid)
                putExtra("recipientName", user.displayName)
            }
            startActivity(intent)
        }

        val recycler = view.findViewById<RecyclerView>(R.id.recyclerUsers)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        val searchView = view.findViewById<SearchView>(R.id.searchView)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                val q = newText ?: ""
                if (q.isBlank()) {
                    adapter.submitList(emptyList())
                } else {
                    userRepository.getUsersByDisplayName(q, onSuccess = { users ->
                        adapter.submitList(users)
                    }, onFailure = { e ->
                        AppLogger.logError(requireContext(), e)
                    })
                }
                return true
            }
        })
    }
}
