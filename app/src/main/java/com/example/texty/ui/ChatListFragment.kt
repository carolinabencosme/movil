package com.example.texty.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.texty.R
import com.example.texty.model.ChatRoom
import com.example.texty.model.User
import com.google.firebase.auth.ktx.auth
import java.util.Locale
import com.example.texty.repository.ChatRoomRepository
import com.example.texty.repository.UserRepository
import com.example.texty.util.AppLogger
import com.example.texty.util.ErrorLogger
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

/**
 * Fragmento que lista salas recientes y permite buscar o crear chats grupales.
 */
/**
 * Fragmento que lista salas de chat recientes y permite:
 * - Buscar chats (filtro por nombre de grupo o contacto).
 * - Crear nuevos chats grupales mediante un diálogo con multi-selección de amigos.
 * - Mostrar estado de presencia (online/offline) en chats individuales, sincronizado en tiempo real.
 *
 * Integra:
 * - **Firebase Auth**: para identificar al usuario actual.
 * - **Firebase Firestore**: snapshots en tiempo real para amigos/presencia.
 * - **ViewModel (ChatListViewModel)**: expone LiveData de rooms y loading.
 * - **RecyclerView + ChatListAdapter**: render y actualizaciones eficientes.
 * - **Material Components**: progreso, botones, TextInput y Snackbar.
 */
class ChatListFragment : Fragment() {
    /** ViewModel que provee rooms y estado de carga mediante LiveData. */
    private val viewModel: ChatListViewModel by viewModels()

    /** Adaptador de la lista de chats; maneja avatar, preview, presencia y clicks. */
    private lateinit var adapter: ChatListAdapter

    /** Campo de texto para buscar salas/usuarios por nombre. */
    private lateinit var searchInput: TextInputEditText

    /** Cache local de todas las rooms combinadas (rooms reales + “friend rooms”). */
    private var allRooms: List<ChatRoom> = emptyList()

    /** Recycler principal de la lista de chats. */
    private lateinit var recycler: RecyclerView

    /** Placeholder cuando no hay resultados/salas. */
    private lateinit var placeholder: TextView

    /** Indicador de progreso mientras carga/escucha datos. */
    private lateinit var progressBar: CircularProgressIndicator

    /** Cache de amigos (User) ya cargados desde Firestore. */
    private var cachedFriends: List<User>? = null

    /** Rooms pendientes de combinar hasta que lleguen los amigos. */
    private var pendingRooms: List<ChatRoom>? = null

    /** Registro del listener de cambios del documento del usuario (lista de amigos). */
    private var friendsRegistration: ListenerRegistration? = null

    /** Listeners de presencia por userId -> ListenerRegistration. */
    private val presenceListeners = mutableMapOf<String, ListenerRegistration>()

    /** Cache de presencia: userId -> User (incluye flag isOnline). */
    private val presenceCache = mutableMapOf<String, User>()

    /** Cache de amigos filtrados desde presencia (solo los friendIds). */
    private val friendCache = mutableMapOf<String, User>()

    /** Conjunto de ids de amigos que seguimos (para presencia). */
    private var trackedFriendIds: Set<String> = emptySet()

    /** Conjunto de ids de participantes (no amigos) que aparecen en rooms. */
    private var trackedParticipantIds: Set<String> = emptySet()

    /** Repositorio para crear grupos (write en Firestore). */
    private val chatRoomRepository = ChatRoomRepository()

    /**
     * Infla la vista del fragmento.
     *
     * Usa el layout `fragment_chat_list` que contiene:
     * - Recycler de chats
     * - Campos de búsqueda
     * - Botones (crear grupo, compartir logs, logout)
     * - Indicador de progreso y placeholder.
     *
     * @return La vista raíz inflada.
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_chat_list, container, false)
    }
    /**
     * Configura UI, listeners y suscripciones a LiveData/Snapshots.
     * Flujo principal:
     * 1) Verifica usuario actual (Firebase.auth.currentUser); si no existe, retorna (evita crash).
     * 2) Instancia `ChatListAdapter` con callback de click para abrir chats (grupal o 1-1).
     * 3) Prepara RecyclerView (LayoutManager + adapter) y campo de búsqueda (filtro en vivo).
     * 4) Wirea botones: crear grupo, compartir logs y logout.
     * 5) Observa LiveData del ViewModel: loading (progress) y rooms (datos).
     * 6) Inicia listeners en tiempo real:
     *    - Amigos del usuario actual (`startFriendsRealtime(uid)`).
     *    - Rooms (`viewModel.startListening(uid)`).
     *
     * @param view Vista raíz creada en onCreateView.
     * @param savedInstanceState Estado previo (si existe).
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val currentUser = Firebase.auth.currentUser ?: return
        adapter = ChatListAdapter { room ->
            if (room.isGroup) {
                val intent = Intent(requireContext(), ChatActivity::class.java).apply {
                    putExtra("roomId", room.id)
                    putExtra("isGroup", true)
                    putExtra("groupName", room.groupName ?: getString(R.string.chat_group_default_name))
                }
                startActivity(intent)
            } else {
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
        searchInput = view.findViewById(R.id.editSearch)
        searchInput.addTextChangedListener { text ->
            filterRooms(text?.toString() ?: "")
        }

        view.findViewById<MaterialButton>(R.id.buttonCreateGroup).setOnClickListener {
            openCreateGroupDialog()
        }

        view.findViewById<MaterialButton>(R.id.buttonShareLogs).setOnClickListener {
            AppLogger.shareLogs(requireContext())
        }

        view.findViewById<MaterialButton>(R.id.buttonLogout).setOnClickListener {
            performLogout()
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

        startFriendsRealtime(currentUser.uid)
        viewModel.startListening(currentUser.uid)
    }


    /**
     * Limpia listeners y caches asociados a la vista para evitar fugas de memoria:
     * - Detiene listener de amigos.
     * - Detiene todos los listeners de presencia.
     * - Limpia caches de presencia y amigos, así como sets de seguimiento.
     * Se invoca cuando la vista se destruye (ciclo de vida del Fragment).
     */
    override fun onDestroyView() {
        super.onDestroyView()
        friendsRegistration?.remove()
        friendsRegistration = null
        presenceListeners.values.forEach { it.remove() }
        presenceListeners.clear()
        presenceCache.clear()
        friendCache.clear()
        trackedFriendIds = emptySet()
        trackedParticipantIds = emptySet()
        cachedFriends = null
    }
    /**
     * Comienza a escuchar en tiempo real el documento del usuario actual para obtener
     * la lista de amigos (`users/{uid}.friends`). Cuando cambia:
     * - Calcula el nuevo set de friendIds.
     * - Llama a [updateTrackedFriends] para refrescar el seguimiento y presencia.
     *
     * @param uid UID del usuario actual (propietario de la lista de amigos).
     */
    private fun startFriendsRealtime(uid: String) {
        friendsRegistration?.remove()
        friendsRegistration = Firebase.firestore
            .collection("users")
            .document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    context?.let { AppLogger.logError(it, error) }
                    return@addSnapshotListener
                }

                val friendIds = (snapshot?.get("friends") as? List<*>)
                    ?.mapNotNull { it as? String }
                    ?.toSet()
                    ?: emptySet()

                updateTrackedFriends(friendIds)
            }
    }
    /**
     * Actualiza el set de amigos a seguir. Si hay cambios:
     * - Actualiza `trackedFriendIds`.
     * - Elimina del cache aquellos amigos que ya no están.
     * - Llama a [refreshPresenceListeners] para alinear listeners de presencia.
     *
     * @param friendIds Conjunto nuevo de UIDs de amigos.
     */
    private fun updateTrackedFriends(friendIds: Set<String>) {
        if (friendIds == trackedFriendIds) return

        trackedFriendIds = friendIds
        val removedFriends = friendCache.keys - trackedFriendIds
        removedFriends.forEach { friendCache.remove(it) }
        refreshPresenceListeners()
    }
    /**
     * Sincroniza los listeners de presencia en Firestore con los UIDs que
     * realmente necesitamos observar:
     * - Objetivo = amigos + participantes en rooms (excluye al current user).
     * - Remueve listeners sobrantes; agrega listeners faltantes.
     * - Cada snapshot convierte el documento a [User], lo guarda en caches
     *   y dispara [onPresenceCacheChanged] para refrescar la UI y el adaptador.
     *
     * Usa:
     * - `presenceListeners`: para saber qué UIDs ya tienen listener.
     * - `presenceCache`/`friendCache`: para exponer presencia y lista de amigos ordenada.
     */
    private fun refreshPresenceListeners() {
        val currentUid = Firebase.auth.currentUser?.uid ?: return
        val targetIds = (trackedFriendIds + trackedParticipantIds) - currentUid

        val removed = presenceListeners.keys - targetIds
        removed.forEach { uid ->
            presenceListeners.remove(uid)?.remove()
            presenceCache.remove(uid)
            friendCache.remove(uid)
        }

        val added = targetIds - presenceListeners.keys
        added.forEach { uid ->
            val registration = Firebase.firestore
                .collection("users")
                .document(uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        context?.let { AppLogger.logError(it, error) }
                        return@addSnapshotListener
                    }

                    val fetched = snapshot?.toObject(User::class.java)

                    val onlineFlag = (snapshot?.getBoolean("isOnline") == true) ||
                            (snapshot?.getBoolean("online") == true)

                    val lastActiveMs = snapshot?.getTimestamp("lastActive")?.toDate()?.time ?: 0L
                    val fresh = (System.currentTimeMillis() - lastActiveMs) < 90_000  // 90s

                    val computedOnline = onlineFlag && fresh

                    if (fetched != null) {
                        val base = if (fetched.uid.isBlank()) fetched.copy(uid = uid) else fetched
                        val user = base.copy(isOnline = computedOnline)   // ← fuerza el valor “real”
                        presenceCache[uid] = user
                        if (uid in trackedFriendIds) friendCache[uid] = user else friendCache.remove(uid)
                    } else {
                        presenceCache.remove(uid)
                        friendCache.remove(uid)
                    }
                    onPresenceCacheChanged()


                }
            presenceListeners[uid] = registration
        }

        if (removed.isNotEmpty()) {
            onPresenceCacheChanged()
        } else if (added.isEmpty()) {
            // No change in listeners but friend cache may have been trimmed (e.g., friend removed)
            onPresenceCacheChanged()
        }
    }

    /**
     * Se ejecuta tras cambios en los caches de presencia/amigos.
     * - Ordena amigos por `displayName` (fallback a uid) en minúsculas con locale actual.
     * - Actualiza `cachedFriends` (para combinación con rooms).
     * - Llama a `adapter.updatePresence(...)` mapeando userId -> isOnline.
     * - Combina rooms con “friend rooms” (si rooms reales ya llegaron).
     *
     * Origen de datos:
     * - `presenceCache`: contiene `User` con `isOnline`.
     * - `friendCache`: subconjunto de `presenceCache` que están en `trackedFriendIds`.
     * - `pendingRooms`/`viewModel.rooms.value`: rooms Firestore reales.
     */

    private fun onPresenceCacheChanged() {
        val friends = friendCache.values
            .map { it }
            .sortedBy { it.displayName.ifBlank { it.uid }.lowercase(Locale.getDefault()) }
        cachedFriends = friends
        adapter.updatePresence(presenceCache.mapValues { it.value.isOnline })

        val currentRooms = pendingRooms ?: viewModel.rooms.value ?: emptyList()
        pendingRooms = null
        combineRoomsAndRender(currentRooms, friends)
    }
    /**
     * Combina las rooms reales con “friend rooms” sintéticas (una por amigo),
     * de modo que el usuario pueda iniciar conversación aunque no existan mensajes todavía.
     * Pasos:
     * 1) Crea `friendRooms` a partir de `friends`: ChatRoom 1-a-1 (currentUid con friend.uid).
     * 2) Une `rooms + friendRooms` y elimina duplicados:
     *    - Si es grupo: por `room.id`.
     *    - Si es 1-a-1: por par ordenado de participantes.
     * 3) Actualiza `allRooms`, muestra placeholder si vacío o filtra según búsqueda.
     * 4) Extrae nuevos participantIds (no current) y refresca listeners de presencia.
     *
     * @param rooms Rooms reales desde Firestore (ViewModel).
     * @param friends Lista de amigos (para friendRooms).
     */
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

        val newParticipantIds = combined
            .filter { !it.isGroup }
            .flatMap { room -> room.participantIds.filter { it != currentUid } }
            .toSet()
        if (newParticipantIds != trackedParticipantIds) {
            trackedParticipantIds = newParticipantIds
            refreshPresenceListeners()
        }

        if (allRooms.isEmpty()) {
            placeholder.visibility = View.VISIBLE
            recycler.visibility = View.GONE
        } else {
            placeholder.visibility = View.GONE
            recycler.visibility = View.VISIBLE
            filterRooms(searchInput.text?.toString() ?: "")
        }
    }

    /**
     * Aplica filtro por `query` sobre `allRooms` y envía la lista resultante al adapter.
     * Reglas:
     * - Si vacío: muestra todo.
     * - Si no vacío:
     *   - Grupos: compara `groupName` (case-insensitive).
     *   - 1-a-1: busca el nombre del otro participante en `room.userNames`.
     * Tras submit, invoca `recycler.scheduleLayoutAnimation()` para animar cambios.
     *
     * @param query Cadena de búsqueda introducida por el usuario.
     */
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
        recycler.scheduleLayoutAnimation()
    }
    /**
     * Abre un diálogo para crear un grupo:
     * - **UI**: infla `dialog_create_group` con Recycler (amigos con CheckBox),
     *   campo de nombre del grupo y búsqueda de amigos.
     * - **Data**: pide `UserRepository().getFriends(uid)` y filtra en vivo (TextWatcher).
     * - **Validación**: exige nombre no vacío y al menos un miembro.
     * - **Acción**: usa [ChatRoomRepository.createGroup] para persistir en Firestore.
     * - **Feedback**: logs en éxito/fracaso y cierra el diálogo en éxito.
     *
     * Dependencias:
     * - Firebase Auth para `uid` y nombre del creador.
     * - AppLogger y ErrorLogger para registro y diagnóstico.
     */
    private fun openCreateGroupDialog() {
        val context = requireContext()
        val uid = Firebase.auth.currentUser?.uid ?: return

        // 1. Layout dinámico para el diálogo
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_create_group, null)
        val recycler = dialogView.findViewById<RecyclerView>(R.id.recyclerFriends)
        val editGroupName = dialogView.findViewById<TextInputEditText>(R.id.editGroupName)
        val groupNameLayout = dialogView.findViewById<TextInputLayout>(R.id.groupNameLayout)
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
                .setPositiveButton("Crear", null)
                .setNegativeButton("Cancelar") { d, _ -> d.dismiss() }
                .create()

            dialog.setOnShowListener {
                val createButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                createButton.isEnabled = !editGroupName.text.isNullOrBlank()

                editGroupName.addTextChangedListener { text ->
                    groupNameLayout.error = null
                    createButton.isEnabled = !text.isNullOrBlank()
                }

                createButton.setOnClickListener {
                    val groupName = editGroupName.text?.toString()?.trim().orEmpty()
                    var isValid = true

                    if (groupName.isBlank()) {
                        groupNameLayout.error = getString(R.string.error_group_name_required)
                        isValid = false
                    } else {
                        groupNameLayout.error = null
                    }

                    if (selectedFriends.isEmpty()) {
                        Snackbar.make(dialogView, R.string.error_group_members_required, Snackbar.LENGTH_SHORT).show()
                        isValid = false
                    }

                    if (!isValid) return@setOnClickListener

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

                    dialog.dismiss()
                }
            }

            dialog.show()

        }, onFailure = { e ->
            AppLogger.logError(context, e)
        })
    }
    /**
     * ViewHolder del listado de amigos en el diálogo de creación de grupo.
     * Contiene un CheckBox para seleccionar miembros.
     */
    private class FriendVH(view: View) : RecyclerView.ViewHolder(view) {
        val checkBox: CheckBox = view.findViewById(R.id.checkBoxFriend)
    }

    private fun performLogout() {
        FirebaseAuth.getInstance().signOut()
        val intent = Intent(requireContext(), LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        requireActivity().finish()
    }
}


