package com.example.texty.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
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

/**
 * Fragmento para buscar usuarios y enviar solicitudes de amistad.
 */
/**
 * Fragmento para **buscar usuarios por nombre** y **gestionar solicitudes de amistad**.
 *
 * Funcionalidad:
 * - Búsqueda incremental por displayName (UserRepository).
 * - Estado de relación por usuario: "friend" (ya amigos), "pending" (solicitud enviada),
 *   "none" (sin relación).
 * - Acciones por ítem:
 *   - Tap → si son amigos, abre ChatActivity; si no, muestra aviso.
 *   - Botón "Agregar" → envía solicitud y marca el ítem como "pending".
 *
 * Dependencias:
 * - Firebase Auth para conocer el `currentUid`.
 * - UserRepository para búsquedas.
 * - FriendRequestRepository para amistad/solicitudes.
 */
class SearchUserFragment : Fragment() {
    private val userRepository = UserRepository()
    private val friendRepository = FriendRequestRepository()
    private lateinit var adapter: UserAdapter
    private lateinit var currentUid: String
    /**
     * Configura UI, verifica sesión y prepara la búsqueda:
     *
     * Flujo:
     * 1) Valida que haya usuario autenticado; si no, navega a LoginActivity y finaliza la Activity.
     * 2) Configura la Toolbar como ActionBar.
     * 3) Crea el [UserAdapter] con dos callbacks:
     *    - onClick(user): si ya son amigos (areFriends), abre ChatActivity; de lo contrario, muestra Toast.
     *    - onAddClick(user): envía solicitud (sendRequest) y actualiza el ítem a estado "pending".
     * 4) Configura RecyclerView (LinearLayoutManager + adapter).
     * 5) Búsqueda en vivo con `addTextChangedListener`:
     *    - Si query vacía → lista vacía.
     *    - Si query con texto:
     *        a) `getUsersByDisplayName(q)` (excluye al currentUid).
     *        b) Inicializa cada item como `UserListItem(user, "none")`.
     *        c) Para cada item:
     *           - `areFriends(currentUid, uid)` → si true, marca "friend".
     *           - Si no amigos: `hasPendingRequest(currentUid, uid)` → si existe, marca "pending".
     *        d) Tras cada resolución, vuelve a hacer `submitList` para reflejar el estado.
     */
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
        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(toolbar)

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
                // Enviar solicitud y marcar estado "pending" en la lista visible
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
                // Si no hay query, limpia resultados
                adapter.submitList(emptyList())
            } else {
                //busca por display name
                userRepository.getUsersByDisplayName(q, onSuccess = { users ->
                    //excluyo mi user
                    val items = users.filter { it.uid != currentUid }
                        .map { UserListItem(it, "none") }
                        .toMutableList()
                    // Publica lista inicial (sin resolver estados)
                    adapter.submitList(items.toList())
                    // Para cada usuario, resuelve estado de relación y actualiza la lista
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
