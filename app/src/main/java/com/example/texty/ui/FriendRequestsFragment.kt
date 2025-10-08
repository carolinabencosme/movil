package com.example.texty.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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

/**
 * Fragmento que muestra solicitudes de amistad y permite aceptarlas o rechazarlas.
 */

/**
 * Fragmento que lista **solicitudes de amistad entrantes** y permite **aceptarlas** o **rechazarlas**.
 *
 * Flujo principal:
 * - En [onViewCreated] configura la barra superior, el RecyclerView y el Adapter.
 * - En [onStart] invoca [loadRequests] para traer datos actuales.
 * - Al aceptar/rechazar ([accept], [reject]) refresca la lista.
 *
 * Dependencias:
 * - **Firebase Auth** para obtener el `uid` del usuario actual.
 * - **Firestore** (vía [FriendRequestRepository] y lecturas puntuales en el adapter).
 * - **Material Components** para botones y app bar.
 */
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
        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(toolbar)

        adapter = FriendRequestAdapter(
            onAccept = { request -> accept(request) },
            onReject = { request -> reject(request) },
        )
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerRequests)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
    }

    /**
     * Al iniciar el fragmento (cuando vuelve a primer plano), carga las solicitudes actuales
     * invocando [loadRequests].
     */
    override fun onStart() {
        super.onStart()
        loadRequests()
    }

    /**
     * Carga las solicitudes entrantes del usuario actual desde el repositorio.
     * - Obtiene `uid` con `Firebase.auth.currentUser`.
     * - Llama `repository.getIncomingRequests(uid)` y pasa el resultado al adapter.
     * - Loguea errores con [AppLogger] si falla.
     */

    private fun loadRequests() {
        val uid = Firebase.auth.currentUser?.uid ?: return
        repository.getIncomingRequests(uid, onSuccess = { list ->
            adapter.submitList(list)
        }, onFailure = { e -> AppLogger.logError(requireContext(), e) })
    }
    /**
     * Acepta una solicitud de amistad:
     * - Delegado a [FriendRequestRepository.acceptRequest].
     * - Tras éxito, vuelve a cargar la lista con [loadRequests].
     * - En error, registra con [AppLogger].
     *
     * @param request La solicitud a aceptar (usa `id`, `fromUid`, `toUid`).
     */

    private fun accept(request: FriendRequest) {
        repository.acceptRequest(request.id, request.fromUid, request.toUid, onSuccess = {
            loadRequests()
        }, onFailure = { e -> AppLogger.logError(requireContext(), e) })
    }

    /**
     * Rechaza (elimina) una solicitud de amistad:
     * - Delegado a [FriendRequestRepository.rejectRequest].
     * - Tras éxito, vuelve a cargar la lista con [loadRequests].
     * - En error, registra con [AppLogger].
     *
     * @param request La solicitud a rechazar (usa `id`).
     */
    private fun reject(request: FriendRequest) {
        repository.rejectRequest(request.id, onSuccess = {
            loadRequests()
        }, onFailure = { e -> AppLogger.logError(requireContext(), e) })
    }

    /**
     * Adaptador de solicitudes de amistad. Muestra:
     * - Nombre del remitente (resuelto desde `users/{fromUid}.displayName`).
     * - Botón **Aceptar** y **Rechazar** por ítem.
     *
     * Usa `ListAdapter` con `DiffUtil` para actualizaciones eficientes.
     *
     * @property onAccept Callback al pulsar "Aceptar".
     * @property onReject Callback al pulsar "Rechazar".
     */
    private class FriendRequestAdapter(
        val onAccept: (FriendRequest) -> Unit,
        val onReject: (FriendRequest) -> Unit,
    ) : ListAdapter<FriendRequest, FriendRequestAdapter.ViewHolder>(DIFF) {

        /**
         * ViewHolder de un ítem de solicitud de amistad.
         * Contiene:
         * - [nameText]: nombre a mostrar del remitente.
         * - [acceptButton], [rejectButton]: acciones.
         */
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameText: TextView = view.findViewById(R.id.textName)
            val acceptButton: MaterialButton = view.findViewById(R.id.buttonAccept)
            val rejectButton: MaterialButton = view.findViewById(R.id.buttonReject)
        }

        /**
         * Infla el layout `item_friend_request` y crea un [ViewHolder].
         */
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_friend_request, parent, false)
            return ViewHolder(view)
        }
        /**
         * Bindea el ítem:
         * - Resuelve `displayName` leyendo una vez `users/{fromUid}` en Firestore (si falla, muestra `fromUid`).
         * - Conecta botones **Aceptar** y **Rechazar** a los callbacks recibidos.
         *
         * Nota: esta lectura puntual es simple pero puede hacerse “pesada” con listas largas;
         * para optimizar, cachea nombres en un ViewModel, usa un `Map<String,String>` o
         * agrega `fromDisplayName` a la colección de solicitudes.
         */
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

        /**
         * `DiffUtil` para identificar cambios entre listas.
         * - Identidad: por `id`.
         * - Contenido: por igualdad estructural del data class.
         */
        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<FriendRequest>() {
                override fun areItemsTheSame(oldItem: FriendRequest, newItem: FriendRequest) = oldItem.id == newItem.id
                override fun areContentsTheSame(oldItem: FriendRequest, newItem: FriendRequest) = oldItem == newItem
            }
        }
    }
}
