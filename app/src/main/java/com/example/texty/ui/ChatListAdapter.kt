package com.example.texty.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView // NUEVO
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide // NUEVO
import com.example.texty.R
import com.example.texty.model.ChatRoom
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import java.text.DateFormat

/**
 * Adaptador que muestra tarjetas de salas en la lista principal de chats.
 * Adaptador para la lista de conversaciones (rooms) del chat.
 * Soporta:
 * - Avatares (cargados con Glide)
 * - Preview del último mensaje con manejo de resincronización
 * - Contador de no leídos por usuario
 * - Formateo de hora de última actualización
 * - Indicador de presencia (online/offline) en chats individuales, con payloads parciales
 *
 * Usa DiffUtil para actualizaciones eficientes y payloads para evitar rebind completo
 * cuando solo cambia el estado de presencia.
 *
 * @param onClick Callback invocado al tocar un ítem; recibe el [ChatRoom] seleccionado.
 */
class ChatListAdapter(
    private val onClick: (ChatRoom) -> Unit,
) : ListAdapter<ChatRoom, ChatListAdapter.ChatRoomViewHolder>(DIFF_CALLBACK) {

    // Mapa local con el estado de presencia por userId. Se actualiza vía updatePresence().
    private var presenceByUser = emptyMap<String, Boolean>()

    /**
     * ViewHolder de un room de chat.
     * Mantiene referencias a vistas típicas del ítem de lista.
     */
    class ChatRoomViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById(R.id.imageAvatar) // NUEVO
        val nameText: TextView = view.findViewById(R.id.textName)
        val lastMessageText: TextView = view.findViewById(R.id.textLastMessage)
        val statusView: View = view.findViewById(R.id.viewStatus)
        val unreadCountText: TextView = view.findViewById(R.id.textUnreadCount)
        val lastUpdatedText: TextView = view.findViewById(R.id.textLastUpdated)
    }
    /**
     * Infla el layout base del ítem de chat y crea un [ChatRoomViewHolder].
     *
     * @param parent ViewGroup padre.
     * @param viewType Tipo de vista (único en este adaptador).
     * @return Nuevo [ChatRoomViewHolder] listo para bind.
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatRoomViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat, parent, false)
        return ChatRoomViewHolder(view)
    }
    /**
     * Variante de bind con payloads. Si el payload indica solo presencia ([PAYLOAD_PRESENCE]),
     * actualiza exclusivamente el indicador online/offline sin rehacer el bind completo.
     *
     * @param holder ViewHolder a actualizar.
     * @param position Posición del ítem.
     * @param payloads Lista de payloads de cambio. Si contiene únicamente [PAYLOAD_PRESENCE],
     * se invoca [bindPresence] y se retorna.
     */
    override fun onBindViewHolder(
        holder: ChatRoomViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_PRESENCE }) {
            val room = getItem(position)
            bindPresence(holder, room)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }
    /**
     * Bind completo del ítem de chat:
     * - Resuelve nombre (grupo o 1-1).
     * - Carga avatar con Glide (placeholder + error).
     * - Muestra preview del último mensaje (manejo de resincronización y visibilidad).
     * - Muestra/oculta contador de no leídos.
     * - Formatea y muestra hora de última actualización.
     * - Aplica indicador de presencia (solo en 1-1).
     * - Configura el clic del ítem.
     *
     * @param holder ViewHolder a actualizar.
     * @param position Posición del ítem.
     */
    override fun onBindViewHolder(holder: ChatRoomViewHolder, position: Int) {
        val room = getItem(position)
        val context = holder.itemView.context

        // Nombre (grupo o individual)
        holder.nameText.text = if (room.isGroup) {
            room.groupName ?: context.getString(R.string.chat_group_default_name)
        } else {
            val currentUserUid = Firebase.auth.currentUser?.uid
            val otherUid = room.participantIds.firstOrNull { it != currentUserUid }
            room.userNames[otherUid] ?: "Usuario desconocido"
        }

        // **Avatar** (photoUrl viene del ViewModel)
        val placeholder = if (room.isGroup) R.drawable.baseline_account_circle_24 else R.drawable.baseline_account_circle_24
        Glide.with(holder.itemView)
            .load(room.photoUrl)
            .placeholder(placeholder)
            .error(placeholder)
            .centerCrop()
            .into(holder.avatar)

        // **Preview**:
        // - Si requiere resincronizar: mostramos el aviso.
        // - Si no hay preview (primer chat): ocultamos el TextView (queda vacío).
        // - Si hay preview: lo mostramos tal cual (ya mapeo "Imagen" desde el ViewModel).
        when {
            room.summaryRequiresResync -> {
                holder.lastMessageText.text = context.getString(R.string.chat_message_unavailable_resync)
                holder.lastMessageText.visibility = View.VISIBLE
            }
            room.lastMessagePreview.isNullOrBlank() -> {
                holder.lastMessageText.text = ""
                holder.lastMessageText.visibility = View.GONE // CAMBIO: ocultar cuando no hay mensajes
            }
            else -> {
                holder.lastMessageText.text = room.lastMessagePreview
                holder.lastMessageText.visibility = View.VISIBLE
            }
        }

        // Unread count
        val currentUid = Firebase.auth.currentUser?.uid
        val count = currentUid?.let { room.unreadCounts[it] } ?: 0
        holder.unreadCountText.apply {
            text = count.toString()
            visibility = if (count > 0) View.VISIBLE else View.GONE
        }

        // Hora última actualización
        val formattedTime = room.updatedAt?.toDate()?.let { timeFormatter.format(it) }
        holder.lastUpdatedText.apply {
            text = formattedTime ?: ""
            visibility = if (formattedTime != null) View.VISIBLE else View.GONE
        }

        // Estado online SOLO en chats individuales
        bindPresence(holder, room)

        // Click para abrir el chat
        holder.itemView.setOnClickListener { onClick(room) }
    }
    /**
     * Actualiza el mapa de presencia y notifica, mediante payloads parciales, únicamente
     * los ítems afectados (chats 1-1 cuyo "otro" usuario cambió de estado).
     *
     * @param map Mapa userId -> online?.
     */
    fun updatePresence(map: Map<String, Boolean>) {
        val previousPresence = presenceByUser
        presenceByUser = map

        if (previousPresence == presenceByUser) return

        val currentUid = Firebase.auth.currentUser?.uid ?: return

        val changedUserIds = (previousPresence.keys + presenceByUser.keys)
            .filter { previousPresence[it] != presenceByUser[it] }
            .toSet()

        if (changedUserIds.isEmpty()) return

        currentList.forEachIndexed { index, room ->
            if (!room.isGroup) {
                val otherUid = room.participantIds.firstOrNull { it != currentUid }
                if (otherUid != null && changedUserIds.contains(otherUid)) {
                    notifyItemChanged(index, PAYLOAD_PRESENCE)
                }
            }
        }
    }

    companion object {
        // Formateador de hora corta (locale-aware)
        private val timeFormatter = DateFormat.getTimeInstance(DateFormat.SHORT)

        /**
         * DiffUtil para comparar items y contenidos de ChatRoom.
         * - areItemsTheSame: compara identidad por id.
         * - areContentsTheSame: compara campos relevantes que afectan la UI.
         */
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ChatRoom>() {
            override fun areItemsTheSame(oldItem: ChatRoom, newItem: ChatRoom): Boolean {
                return oldItem.id == newItem.id
            }
            /**
             * Determina si dos ChatRoom representan el mismo ítem (misma identidad).
             *
             * @param oldItem Ítem anterior.
             * @param newItem Ítem nuevo.
             * @return true si comparten el mismo id; false en caso contrario.
             */
            override fun areContentsTheSame(oldItem: ChatRoom, newItem: ChatRoom): Boolean {
                return oldItem.participantIds == newItem.participantIds &&
                    oldItem.userNames == newItem.userNames &&
                    oldItem.isGroup == newItem.isGroup &&
                    oldItem.groupName == newItem.groupName &&
                    oldItem.lastMessagePreview == newItem.lastMessagePreview &&
                    oldItem.updatedAt == newItem.updatedAt &&
                    oldItem.unreadCounts == newItem.unreadCounts &&
                    oldItem.summaryError == newItem.summaryError &&
                    oldItem.summaryRequiresResync == newItem.summaryRequiresResync &&
                    oldItem.photoUrl == newItem.photoUrl
            }
        }

        // Payload para notificar cambios de presencia sin rebinder todo.
        private const val PAYLOAD_PRESENCE = "payload_presence"
    }
    /**
     * Enlaza el estado de presencia para el ítem:
     * - Si es grupo: oculta el indicador.
     * - Si es 1-1: muestra el indicador y cambia el background según online/offline
     *   utilizando [presenceByUser] y el "otro" uid del room.
     *
     * @param holder ViewHolder objetivo.
     * @param room ChatRoom asociado.
     */
    private fun bindPresence(holder: ChatRoomViewHolder, room: ChatRoom) {
        if (room.isGroup) {
            holder.statusView.visibility = View.GONE
        } else {
            holder.statusView.visibility = View.VISIBLE
            val otherUid = room.participantIds.firstOrNull { it != Firebase.auth.currentUser?.uid }
            val isOnline = otherUid?.let { presenceByUser[it] == true } ?: false
            holder.statusView.setBackgroundResource(
                if (isOnline) R.drawable.online_indicator else R.drawable.offline_indicator
            )
        }
    }
}
