package com.example.texty.ui

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.texty.R
import com.example.texty.model.Message
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Adaptador de mensajes que pinta burbujas, estados y adjuntos en el RecyclerView del chat.


/**
 * Adaptador de chat basado en [ListAdapter] que muestra mensajes en forma de burbuja
 * y mensajes de sistema/estado. Soporta chats grupales, adjuntos mediante callback
 * y aplica DiffUtil para actualizaciones eficientes.
 *
 * @param myUid UID del usuario actual; se usa para distinguir mensajes enviados vs. recibidos.
 * @param onBindAttachment Callback que permite enlazar/visualizar adjuntos por mensaje.
 * Recibe el [Message], el [ImageView] de la burbuja y el [TextView] del texto.
 * @param isGroupChat Indica si es un chat grupal para mostrar el nombre del remitente.
 * @param resolveSenderName Función para resolver el nombre a partir del senderId (si no viene en el mensaje).
 */
class ChatAdapter(
    private val myUid: String,
    private val onBindAttachment: (Message, ImageView, TextView) -> Unit,
    private val isGroupChat: Boolean = false,
    private val resolveSenderName: (String) -> String? = { null }
) : ListAdapter<Message, ChatAdapter.MessageViewHolder>(DIFF_CALLBACK) {

    sealed class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        /**
         * ViewHolder para burbujas de mensajes normales (texto, hora, imagen opcional).
         */
        class Bubble(view: View) : MessageViewHolder(view) {
            val root: LinearLayout = view.findViewById(R.id.messageRoot)
            val senderText: TextView = view.findViewById(R.id.textSender)
            val messageText: TextView = view.findViewById(R.id.textMessage)
            val timeText: TextView = view.findViewById(R.id.textTime)
            val imageView: ImageView = view.findViewById(R.id.imageMessage)
        }
        /**
         * ViewHolder para mensajes de sistema o estado (texto centrado).
         */
        class Status(view: View) : MessageViewHolder(view) {
            val statusText: TextView = view.findViewById(R.id.textStatus)
        }
    }

    private val timeFormatter = SimpleDateFormat("hh:mm a", Locale.getDefault())

    /**
     * Determina el tipo de vista por posición (sistema, enviado, recibido).
     *
     * @param position Posición del elemento en la lista.
     * @return Entero que representa el tipo de la vista (ver constantes TYPE_*).
     */
    override fun getItemViewType(position: Int): Int {
        val message = getItem(position)
        return when {
            message.isSystemMessage() -> TYPE_SYSTEM
            message.senderId == myUid -> TYPE_SENT
            else -> TYPE_RECEIVED
        }
    }
    /**
     * Infla y crea el [MessageViewHolder] adecuado según el [viewType].
     *
     * @param parent ViewGroup padre del RecyclerView.
     * @param viewType Tipo de vista (TYPE_SYSTEM | TYPE_SENT | TYPE_RECEIVED).
     * @return Un nuevo [MessageViewHolder].
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SYSTEM -> {
                val view = inflater.inflate(R.layout.item_message_status, parent, false)
                MessageViewHolder.Status(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_message, parent, false)
                MessageViewHolder.Bubble(view)
            }
        }
    }

    /**
     * Enlaza los datos del [Message] con el ViewHolder correspondiente.
     *
     * @param holder ViewHolder que será actualizado.
     * @param position Posición del elemento en la lista.
     */
    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = getItem(position)
        when (holder) {
            is MessageViewHolder.Status -> bindStatusMessage(holder, message)
            is MessageViewHolder.Bubble -> bindBubbleMessage(holder, message, position)
        }
    }
    /**
     * Enlaza un mensaje de sistema/estado en un [MessageViewHolder.Status].
     * Resuelve el texto a mostrar considerando estados de descifrado o etiquetas de tipo.
     *
     * @param holder ViewHolder de estado.
     * @param message Mensaje a representar.
     */

    private fun bindStatusMessage(holder: MessageViewHolder.Status, message: Message) {
        val context = holder.itemView.context
        val text = when {
            message.decryptionError && message.requiresKeyResync ->
                context.getString(R.string.chat_message_unavailable_resync)
            message.decryptionError ->
                context.getString(R.string.chat_message_unavailable)
            message.decrypted?.displayText?.isNotBlank() == true ->
                message.decrypted.displayText.trim()
            message.decrypted?.body?.text?.isNullOrBlank() == false ->
                message.decrypted.body?.text?.trim().orEmpty()
            !message.messageType.isNullOrBlank() ->
                message.messageType.orEmpty()
            else ->
                context.getString(R.string.chat_message_unavailable)
        }
        holder.statusText.text = text
    }
    /**
     * Enlaza una burbuja de mensaje (enviado/recibido). Aplica:
     * - Fondo/alineación según si el mensaje es propio.
     * - Hora formateada si existe timestamp.
     * - Lógica de texto considerando errores de descifrado.
     * - Callback de adjuntos [onBindAttachment].
     * - Encabezado con remitente en chats grupales (solo al cambiar de remitente).
     *
     * @param holder ViewHolder de burbuja.
     * @param message Mensaje a representar.
     * @param position Posición del elemento (se usa para comparar remitente previo).
     */
    private fun bindBubbleMessage(
        holder: MessageViewHolder.Bubble,
        message: Message,
        position: Int,
    ) {
        holder.imageView.setImageDrawable(null)
        holder.imageView.visibility = View.GONE

        val context = holder.itemView.context
        val decrypted = message.decrypted
        val textToDisplay = when {
            message.decryptionError -> {
                if (message.requiresKeyResync) {
                    context.getString(R.string.chat_message_unavailable_resync)
                } else {
                    context.getString(R.string.chat_message_unavailable)
                }
            }
            decrypted != null -> {
                val value = decrypted.displayText.trim()
                if (value.isNotEmpty()) value
                else context.getString(R.string.chat_message_empty_placeholder)
            }
            else -> context.getString(R.string.chat_message_unavailable)
        }

        holder.messageText.visibility = View.VISIBLE
        holder.messageText.text = textToDisplay

        val isMine = message.senderId == myUid
        if (isMine) {
            holder.root.gravity = Gravity.END
            holder.messageText.setBackgroundResource(R.drawable.bubble_outgoing)
            holder.imageView.setBackgroundResource(R.drawable.bubble_outgoing)
        } else {
            holder.root.gravity = Gravity.START
            holder.messageText.setBackgroundResource(R.drawable.bubble_incoming)
            holder.imageView.setBackgroundResource(R.drawable.bubble_incoming)
        }

        val ts = message.createdAt
        if (ts != null) {
            holder.timeText.text = timeFormatter.format(ts.toDate())
            holder.timeText.visibility = View.VISIBLE
        } else {
            holder.timeText.text = ""
            holder.timeText.visibility = View.INVISIBLE
        }

        onBindAttachment(message, holder.imageView, holder.messageText)

        val prevSenderId = getPrevSenderId(position)
        val showSender = isGroupChat && !isMine && message.senderId != prevSenderId

        if (showSender) {
            val name = message.senderName.ifBlank { resolveSenderName(message.senderId) }
            if (!name.isNullOrBlank()) {
                holder.senderText.text = name
                holder.senderText.visibility = View.VISIBLE
            } else {
                holder.senderText.visibility = View.GONE
            }
        } else {
            holder.senderText.visibility = View.GONE
        }
    }

    /**
     * Busca hacia atrás el último mensaje no-sistema para recuperar su senderId.
     * Se utiliza para decidir si se debe volver a mostrar el encabezado con el nombre.
     *
     * @param position Posición actual.
     * @return El senderId del mensaje previo no-sistema, o null si no existe.
     */

    private fun getPrevSenderId(position: Int): String? {
        if (position <= 0) return null
        for (index in position - 1 downTo 0) {
            val previous = getItem(index)
            if (!previous.isSystemMessage()) {
                return previous.senderId
            }
        }
        return null
    }

    /**
     * Agrega un [Message] al final de la lista actual y hace submit de la nueva lista.
     * Útil para inserciones unitarias manteniendo DiffUtil.
     *
     * **Nota:** Esto crea una copia de la lista actual; para inserciones masivas
     * es preferible construir la lista completa y usar [submitList].
     *
     * @param message Mensaje a agregar.
     */

    fun addOne(message: Message) {
        val newList = currentList.toMutableList()
        newList.add(message)
        submitList(newList)
    }

    companion object {
        private const val TYPE_SENT = 0
        private const val TYPE_RECEIVED = 1
        private const val TYPE_SYSTEM = 2
        /**
         * Callback de DiffUtil para calcular cambios entre listas de [Message].
         * Compara identidad por id y contenido por igualdad estructural.
         */
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Message>() {
            /**
             * Determina si dos elementos representan el mismo ítem.
             *
             * @param oldItem Elemento previo.
             * @param newItem Elemento nuevo.
             * @return true si comparten el mismo id, false en caso contrario.
             */
            override fun areItemsTheSame(oldItem: Message, newItem: Message): Boolean {
                return oldItem.id == newItem.id
            }
            /**
             * Determina si el contenido de dos elementos es igual.
             *
             * @param oldItem Elemento previo.
             * @param newItem Elemento nuevo.
             * @return true si son iguales (no hay cambios), false si difieren.
             */
            override fun areContentsTheSame(oldItem: Message, newItem: Message): Boolean {
                return oldItem == newItem
            }
        }
    }
    /**
     * Extensión para determinar si un [Message] debe considerarse “de sistema”.
     * Se marca como sistema si:
     * - El senderId está en blanco, o
     * - messageType (normalizado a lowercase) inicia con "system" o "status".
     *
     * @receiver [Message] a evaluar.
     * @return true si es de sistema/estado; false en caso contrario.
     */
    private fun Message.isSystemMessage(): Boolean {
        if (senderId.isBlank()) return true
        val normalizedType = messageType?.lowercase(Locale.US) ?: return false
        return normalizedType.startsWith("system") || normalizedType.startsWith("status")
    }
}
