package com.example.texty.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.texty.R
import com.example.texty.model.User
import com.google.android.material.button.MaterialButton

/**
 * Modelo de ítem para la lista de resultados de búsqueda de usuarios.
 *
 * @property user Usuario a mostrar.
 * @property requestStatus Estado de relación con el usuario en el contexto de amistad.
 *  - "none": no hay relación ni solicitud enviada; se permite enviar solicitud.
 *  - "pending": hay una solicitud pendiente (enviada por el usuario actual o recibida por el otro).
 *  - "friend": ya son amigos; no se permite enviar solicitud.
 */
data class UserListItem(val user: User, val requestStatus: String)

/**
 * Adaptador que muestra resultados de búsqueda y su estado de amistad.
 */
/**
 * Adaptador de RecyclerView que muestra resultados de búsqueda de usuarios y su estado de amistad.
 *
 * Funcionalidad:
 * - Renderiza el nombre del usuario y un indicador de presencia (online/offline).
 * - Muestra un botón contextual:
 *   - "Añadir" (habilitado) si requestStatus = "none" → dispara [onAddClick].
 *   - "Pendiente" (deshabilitado) si requestStatus = "pending".
 *   - "Amigos" (deshabilitado) si requestStatus = "friend".
 * - Permite abrir la conversación o una acción asociada al usuario al pulsar el ítem completo (callback [onClick]).
 *
 * Eficiencia:
 * - Extiende [ListAdapter] con [DiffUtil.ItemCallback] para calcular diferencias entre listas
 *   y actualizar únicamente los ítems necesarios (mejora rendimiento y animaciones).
 *
 * @param onClick Acción al pulsar el ítem completo (por ejemplo, abrir chat si ya son amigos).
 * @param onAddClick Acción al pulsar "Añadir" (enviar solicitud de amistad).
 */
class UserAdapter(
    private val onClick: (User) -> Unit,
    private val onAddClick: (User) -> Unit,
) : ListAdapter<UserListItem, UserAdapter.UserViewHolder>(DIFF_CALLBACK) {

    /**
     * ViewHolder que contiene las vistas de un ítem de usuario:
     * - [nameText]: nombre a mostrar.
     * - [statusView]: indicador de presencia (verde/online, gris/offline).
     * - [addButton]: botón para enviar solicitud o mostrar el estado (pending/friend).
     */
    class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.textName)
        val statusView: View = view.findViewById(R.id.viewStatus)
        val addButton: MaterialButton = view.findViewById(R.id.buttonAdd)
    }

    /**
     * Infla el layout del ítem de usuario (`item_user`) y crea el [UserViewHolder].
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_user, parent, false)
        return UserViewHolder(view)
    }
    /**
     * Enlaza los datos del [UserListItem] con las vistas:
     * - Nombre del usuario.
     * - Indicador de presencia según [User.isOnline] (selector de fondo `online_indicator` / `offline_indicator`).
     * - Click del ítem completo → [onClick].
     * - Configura el botón según [UserListItem.requestStatus]:
     *   - "pending" → texto "Pendiente", deshabilitado.
     *   - "friend" → texto "Amigos", deshabilitado.
     *   - Otro (por defecto "none") → texto "Añadir", habilitado y con [onAddClick].
     */
    override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
        val item = getItem(position)
        val user = item.user
        holder.nameText.text = user.displayName
        holder.statusView.setBackgroundResource(
            if (user.isOnline) R.drawable.online_indicator else R.drawable.offline_indicator
        )
        holder.itemView.setOnClickListener { onClick(user) }
        when (item.requestStatus) {
            "pending" -> {
                holder.addButton.text = "Pendiente"
                holder.addButton.isEnabled = false
            }
            "friend" -> {
                holder.addButton.text = "Amigos"
                holder.addButton.isEnabled = false
            }
            else -> {
                holder.addButton.text = "Añadir"
                holder.addButton.isEnabled = true
                holder.addButton.setOnClickListener { onAddClick(user) }
            }
        }
    }

    companion object {
        /**
         * Callback de DiffUtil para comparar ítems y contenidos:
         * - Identidad: por `user.uid` (estable en el tiempo).
         * - Contenido: por igualdad estructural del data class [UserListItem] (incluye `user` y `requestStatus`).
         *
         * Con esto, [ListAdapter.submitList] puede calcular cambios mínimos (insert, remove, change)
         * y evitar rebinds innecesarios.
         */
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<UserListItem>() {
            /** Devuelve true si ambos representan el mismo usuario (misma identidad). */
            override fun areItemsTheSame(oldItem: UserListItem, newItem: UserListItem): Boolean {
                return oldItem.user.uid == newItem.user.uid
            }

            /** Devuelve true si el contenido visible no cambió (nombre, estado online, requestStatus, etc.) */
            override fun areContentsTheSame(oldItem: UserListItem, newItem: UserListItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
