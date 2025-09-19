package com.example.texty.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.texty.R
import com.example.texty.model.ChatRoom
import com.example.texty.model.User
import com.example.texty.util.AppLogger
import com.example.texty.util.ErrorLogger
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import androidx.core.widget.addTextChangedListener
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale
import com.example.texty.repository.ChatRoomRepository
import com.example.texty.repository.UserRepository

class ChatListFragment : Fragment() {
    private val viewModel: ChatListViewModel by viewModels()
    private lateinit var adapter: ChatListAdapter
    private lateinit var searchInput: TextInputEditText
    private var allRooms: List<ChatRoom> = emptyList()
    private lateinit var recycler: RecyclerView
    private lateinit var placeholder: TextView
    private lateinit var progressBar: ProgressBar
    private var cachedFriends: List<User>? = null
    private var pendingRooms: List<ChatRoom>? = null
    private val userRepository = UserRepository()
    private val chatRoomRepository = ChatRoomRepository()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        setHasOptionsMenu(true)
        return inflater.inflate(R.layout.fragment_chat_list, container, false)
    }

   /* override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
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

        recycler = view.findViewById(R.id.recyclerChats)
        progressBar = view.findViewById(R.id.progressBar)
        placeholder = view.findViewById(R.id.textPlaceholder)

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
            val uid = Firebase.auth.currentUser!!.uid

            // Traer también la lista de amigos
            UserRepository().getFriends(uid, onSuccess = { friends ->
                // Crear ChatRoom "vacío" si no existe conversación aún
                val friendRooms = friends.map { user ->
                    ChatRoom(
                        id = user.uid, // puedes usar algo como "${uid}_${user.uid}" si prefieres
                        contactUid = user.uid,
                        contactName = user.displayName,
                        lastMessagePreview = null // vacío porque aún no hay chat
                    )
                }

                // Combinar rooms existentes con los amigos
                val combined = (list + friendRooms).distinctBy { it.contactUid }
                allRooms = combined

                if (allRooms.isEmpty()) {
                    view?.findViewById<TextView>(R.id.textPlaceholder)?.visibility = View.VISIBLE
                    view?.findViewById<RecyclerView>(R.id.recyclerChats)?.visibility = View.GONE
                } else {
                    view?.findViewById<TextView>(R.id.textPlaceholder)?.visibility = View.GONE
                    view?.findViewById<RecyclerView>(R.id.recyclerChats)?.visibility = View.VISIBLE
                    filterRooms(searchInput.text?.toString() ?: "")
                }
            }, onFailure = { e ->
                AppLogger.logError(requireContext(), e)
            })
        }

        /*viewModel.rooms.observe(viewLifecycleOwner) { list ->
            allRooms = list
            if (list.isEmpty()) {
                placeholder.visibility = View.VISIBLE
                recycler.visibility = View.GONE
            } else {
                placeholder.visibility = View.GONE
                recycler.visibility = View.VISIBLE
                filterRooms(searchInput.text?.toString() ?: "")
            }
        }*/

        viewModel.error.observe(viewLifecycleOwner) { e ->
            e?.let { AppLogger.logError(requireContext(), it) }
        }
        viewModel.startListening(currentUser.uid)
    }
*/

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val currentUser = Firebase.auth.currentUser!!
        val toolbar = view.findViewById<MaterialToolbar>(R.id.topAppBar)
        (requireActivity() as androidx.appcompat.app.AppCompatActivity).setSupportActionBar(toolbar)

        adapter = ChatListAdapter { room ->
            if (room.isGroup) {
                // 🔹 Abrir chat grupal
                val intent = Intent(requireContext(), ChatActivity::class.java).apply {
                    putExtra("roomId", room.id)
                    putExtra("isGroup", true)
                    putExtra("groupName", room.groupName ?: "Grupo sin nombre")
                }
                startActivity(intent)
            } else {
                // 🔹 Abrir chat privado
                val otherUid = room.participantIds.firstOrNull { it != currentUser.uid }
                val otherName = otherUid?.let { room.userNames[it] } ?: "Desconocido"

                if (otherUid.isNullOrBlank()) {
                    val error = IllegalArgumentException("ChatRoom privado sin participante válido")
                    ErrorLogger.log(requireContext(), error)
                    return@ChatListAdapter
                }

                val intent = Intent(requireContext(), ChatActivity::class.java).apply {
                    putExtra("recipientUid", otherUid)
                    putExtra("recipientName", otherName)
                }
                startActivity(intent)
            }
        }

        recycler = view.findViewById(R.id.recyclerChats)
        progressBar = view.findViewById(R.id.progressBar)
        placeholder = view.findViewById(R.id.textPlaceholder)

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
            val friends = cachedFriends
            if (friends != null) {
                pendingRooms = null
                combineRoomsAndRender(list, friends)
            } else {
                pendingRooms = list
            }
        }

        loadFriends(currentUser.uid)
        viewModel.startListening(currentUser.uid)
    }


    private fun loadFriends(uid: String) {
        userRepository.getFriends(uid, onSuccess = { friends ->
            cachedFriends = friends
            val currentRooms = pendingRooms ?: viewModel.rooms.value ?: emptyList()
            pendingRooms = null
            combineRoomsAndRender(currentRooms, friends)
        }, onFailure = { e ->
            AppLogger.logError(requireContext(), e)
        })
    }

    private fun combineRoomsAndRender(rooms: List<ChatRoom>, friends: List<User>) {
        val currentUid = Firebase.auth.currentUser?.uid ?: return
        val friendRooms = friends.map { user ->
            ChatRoom(
                id = user.uid,
                participantIds = listOf(currentUid, user.uid),
                userNames = mapOf(
                    currentUid to (Firebase.auth.currentUser?.displayName ?: "Yo"),
                    user.uid to user.displayName
                ),
                isGroup = false,
                lastMessagePreview = null
            )
        }

        val combined = (rooms + friendRooms).distinctBy { room ->
            if (room.isGroup) room.id else room.participantIds.sorted().joinToString("_")
        }

        allRooms = combined

        if (allRooms.isEmpty()) {
            placeholder.visibility = View.VISIBLE
            recycler.visibility = View.GONE
        } else {
            placeholder.visibility = View.GONE
            recycler.visibility = View.VISIBLE
            filterRooms(searchInput.text?.toString() ?: "")
        }
    }


    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_chat_list, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_create_group -> {
                openCreateGroupDialog()
                true
            }
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

   /* private fun filterRooms(query: String) {
        if (query.isBlank()) {
            adapter.submitList(allRooms)
        } else {
            val lower = query.lowercase(Locale.getDefault())
            adapter.submitList(allRooms.filter { it.contactName.lowercase(Locale.getDefault()).contains(lower) })
        }
    }*/

    private fun filterRooms(query: String) {
        if (query.isBlank()) {
            adapter.submitList(allRooms)
        } else {
            val lower = query.lowercase(Locale.getDefault())

            val filtered = allRooms.filter { room ->
                if (room.isGroup) {
                    room.groupName?.lowercase(Locale.getDefault())?.contains(lower) == true
                } else {
                    val currentUid = Firebase.auth.currentUser?.uid
                    val otherUid = room.participantIds.firstOrNull { it != currentUid }
                    val otherName = otherUid?.let { room.userNames[it] }
                    otherName?.lowercase(Locale.getDefault())?.contains(lower) == true
                }
            }

            adapter.submitList(filtered)
        }
    }

    private fun openCreateGroupDialog() {
        val context = requireContext()
        val uid = Firebase.auth.currentUser?.uid ?: return

        // 1. Layout dinámico para el diálogo
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_create_group, null)
        val recycler = dialogView.findViewById<RecyclerView>(R.id.recyclerFriends)
        val editGroupName = dialogView.findViewById<TextInputEditText>(R.id.editGroupName)
        val searchInput = dialogView.findViewById<TextInputEditText>(R.id.editSearchFriends)

        recycler.layoutManager = LinearLayoutManager(context)

        // 2. Cargar amigos desde UserRepository
        UserRepository().getFriends(uid, onSuccess = { friends ->

            val selectedFriends = mutableSetOf<String>()

            // Adaptador con filtro
            val adapter = object : RecyclerView.Adapter<FriendVH>() {
                private var filteredFriends = friends.toList()

                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FriendVH {
                    val v = LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_friend_checkbox, parent, false)
                    return FriendVH(v)
                }

                override fun getItemCount() = filteredFriends.size

                override fun onBindViewHolder(holder: FriendVH, position: Int) {
                    val friend = filteredFriends[position]
                    holder.checkBox.text = friend.displayName
                    holder.checkBox.isChecked = selectedFriends.contains(friend.uid)

                    holder.checkBox.setOnClickListener {
                        if (holder.checkBox.isChecked) selectedFriends.add(friend.uid)
                        else selectedFriends.remove(friend.uid)
                    }
                }

                fun filter(query: String) {
                    filteredFriends = if (query.isBlank()) {
                        friends
                    } else {
                        friends.filter { it.displayName.contains(query, ignoreCase = true) }
                    }
                    notifyDataSetChanged()
                }
            }

            recycler.adapter = adapter

            // 🔎 Filtrar en vivo
            searchInput.addTextChangedListener { text ->
                adapter.filter(text?.toString() ?: "")
            }

            // 3. Mostrar diálogo SOLO después de cargar amigos
            val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle("Nuevo grupo")
                .setView(dialogView)
                .setPositiveButton("Crear") { d, _ ->
                    val groupName = editGroupName.text?.toString()?.trim().orEmpty()
                    if (groupName.isBlank() || selectedFriends.isEmpty()) {
                        AppLogger.logError(context, Exception("Falta nombre o miembros"))
                        return@setPositiveButton
                    }

                    // Construir ChatRoom
                    val selectedUsers = friends.filter { selectedFriends.contains(it.uid) }

                    chatRoomRepository.createGroup(
                        context = context,
                        creatorUid = uid,
                        creatorDisplayName = Firebase.auth.currentUser?.displayName
                            ?: "Yo",
                        groupName = groupName,
                        members = selectedUsers,
                        onSuccess = {
                            AppLogger.logInfo("ChatGroup", "Grupo creado correctamente")
                        },
                        onFailure = { e ->
                            AppLogger.logError(context, e)
                            ErrorLogger.log(context, e)
                        },
                    )

                    d.dismiss()
                }
                .setNegativeButton("Cancelar") { d, _ -> d.dismiss() }
                .create()

            dialog.show()

        }, onFailure = { e ->
            AppLogger.logError(context, e)
        })
    }

    // ViewHolder para amigos
    private class FriendVH(view: View) : RecyclerView.ViewHolder(view) {
        val checkBox: CheckBox = view.findViewById(R.id.checkBoxFriend)
    }

}
