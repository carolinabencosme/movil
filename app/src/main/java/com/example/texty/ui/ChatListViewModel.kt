package com.example.texty.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.texty.model.ChatRoom
import com.example.texty.model.EncryptionPayload
import com.example.texty.model.SessionKeyInfo
import com.example.texty.repository.SessionKeyRepository
import com.example.texty.util.MessageCrypto
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * ViewModel que agrupa datos de salas, estados cifrados y manejo de listeners.
 */
/**
 * ViewModel responsable de:
 * - Escuchar en tiempo real las salas (rooms) del usuario actual desde Firestore.
 * - Descifrar el “summary” (preview del último mensaje) por room usando llaves de sesión.
 * - Sincronizar foto de usuario en chats 1:1.
 * - Publicar una lista combinada de [ChatRoom] hacia la UI (LiveData).
 *
 * Caches principales:
 * - [baseRooms]: rooms tal como vienen de Firestore (sin preview).
 * - [summaryStates]: estado del resumen (preview descifrado, error, requiere resincronización).
 * - [sessionCache]: llaves de sesión por room para evitar recargas repetidas.
 * - [userPhotoCache]: cache de photoUrl por userUid (solo 1:1).
 */
class ChatListViewModel : ViewModel() {

    private val sessionKeyRepository = SessionKeyRepository()

    private val _rooms = MutableLiveData<List<ChatRoom>>()
    /** LiveData de rooms combinadas (base + summary) listas para pintar en UI. */
    val rooms: LiveData<List<ChatRoom>> = _rooms

    private val _loading = MutableLiveData<Boolean>()
    /** LiveData de estado de carga (true = cargando). */
    val loading: LiveData<Boolean> = _loading

    private val _error = MutableLiveData<Exception?>()
    /** LiveData de error para diagnóstico/logging. */
    val error: LiveData<Exception?> = _error

    /** Listener principal de rooms (colección `rooms`). */
    private var roomsListener: ListenerRegistration? = null

    /** Listeners de estado por room (subcolección `userState` por ownerUid). */
    private val userStateListeners = mutableMapOf<String, ListenerRegistration>()

    /** Cache base de rooms por roomId (sin preview). */
    private val baseRooms = mutableMapOf<String, ChatRoom>()

    /** Cache de estados de resumen por roomId (preview, error, resync). */
    private val summaryStates = mutableMapOf<String, RoomSummaryState>()

    /** Cache de llaves de sesión por roomId. */
    private val sessionCache = mutableMapOf<String, SessionKeyInfo>()

    /** Rooms a las que ya se les “purgó” el campo legacy lastMessage. */
    private val purgedRooms = mutableSetOf<String>()

    /** Cache de photoUrl por userUid. */
    private val userPhotoCache = mutableMapOf<String, String?>()

    /** Listeners de foto de usuario por userUid. */
    private val userPhotoListeners = mutableMapOf<String, ListenerRegistration>()

    /** UID actual (dueño de la bandeja). */
    private var currentUserUid: String? = null

    /**
     * Inicia la escucha en tiempo real de las rooms del usuario actual.
     * - Fija [currentUserUid] y activa [_loading].
     * - Suscribe un snapshot listener a `rooms` filtrando por `participantIds` que contengan al usuario.
     * - Por cada documento:
     *   - Extrae campos base (participantes, nombres, flags, timestamps, unreadCounts).
     *   - Resuelve `photoUrl`:
     *       * Grupo: `groupPhotoUrl` (si guardas eso en el doc).
     *       * 1:1: desde [userPhotoCache] y añade listener con [ensureUserPhotoListener].
     *   - Purga campo legacy `lastMessage` (si existiera) con [purgeLegacyLastMessage].
     *   - Asegura listener de estado/summary con [ensureUserStateListener].
     * - Elimina rooms que ya no vienen en el snapshot.
     * - Publica la lista final combinada con [publishRooms].
     *
     * @param currentUserUid UID del usuario propietario de la bandeja de chats.
     */

    fun startListening(currentUserUid: String) {
        if (roomsListener != null) return
        this.currentUserUid = currentUserUid
        _loading.value = true
        roomsListener = Firebase.firestore
            .collection("rooms")
            .whereArrayContains("participantIds", currentUserUid)
            .addSnapshotListener { value, error ->
                _loading.value = false
                if (error != null) {
                    _error.value = error
                    return@addSnapshotListener
                }

                val documents = value?.documents.orEmpty()
                val seenRoomIds = mutableSetOf<String>()

                val ownerUid = currentUserUid ?: return@addSnapshotListener

                documents.forEach { doc ->

                    // --- Campos base del room ---
                    val participantIds = (doc.get("participantIds") as? List<*>)
                        ?.mapNotNull { it as? String }
                        ?.distinct()
                        ?: emptyList()

                    if (!participantIds.contains(ownerUid)) {
                        baseRooms.remove(doc.id)
                        summaryStates.remove(doc.id)
                        sessionCache.remove(doc.id)
                        userStateListeners.remove(doc.id)?.remove()
                        return@forEach
                    }

                    val userNames = (doc.get("userNames") as? Map<*, *>)
                        ?.mapNotNull { (k, v) -> if (k is String && v is String) k to v else null }
                        ?.toMap()
                        ?: emptyMap()

                    val isGroup = doc.getBoolean("isGroup") ?: false
                    val groupName = doc.getString("groupName")
                    val updatedAt = doc.getTimestamp("updatedAt") ?: Timestamp.now()

                    val unreadCountsLong = (doc.get("unreadCounts") as? Map<*, *>) ?: emptyMap<Any?, Any?>()
                    val unreadCounts = unreadCountsLong.mapNotNull { (k, v) ->
                        val uid = k as? String ?: return@mapNotNull null
                        val count = (v as? Number)?.toInt() ?: return@mapNotNull null
                        uid to count
                    }.toMap()

                    // --- Foto (avatar): otro usuario en 1:1 o foto de grupo si existe ---
                    val currentUid = ownerUid
                    val otherUid = if (!isGroup) participantIds.firstOrNull { it != currentUid } else null
                    val groupPhoto = doc.getString("groupPhotoUrl") // opcional, si lo guardas en el room

                    val photoUrl = when {
                        isGroup -> groupPhoto
                        otherUid != null -> userPhotoCache[otherUid] // se actualizará por listener
                        else -> null
                    }

                    // --- Construcción del ChatRoom (una sola vez) ---
                    val room = ChatRoom(
                        id = doc.id,
                        participantIds = participantIds,
                        userNames = userNames,
                        isGroup = isGroup,
                        groupName = groupName,
                        lastMessagePreview = null,
                        updatedAt = updatedAt,
                        unreadCounts = unreadCounts,
                        summaryError = false,
                        summaryRequiresResync = false,
                        photoUrl = photoUrl, // NUEVO
                    )

                    baseRooms[doc.id] = room
                    seenRoomIds.add(doc.id)

                    // Limpieza de campo legacy si aún existe
                    if (!purgedRooms.contains(doc.id) && doc.data?.containsKey("lastMessage") == true) {
                        purgedRooms.add(doc.id)
                        purgeLegacyLastMessage(doc.reference)
                    }

                    // Listener existente para summaries por usuario
                    ensureUserStateListener(doc.id)

                    // NUEVO: escucha la foto del "otro" usuario (solo 1:1)
                    if (!isGroup && otherUid != null) {
                        ensureUserPhotoListener(otherUid, doc.id)
                    }
                }

                val removed = baseRooms.keys - seenRoomIds
                removed.forEach { roomId ->
                    baseRooms.remove(roomId)
                    summaryStates.remove(roomId)
                    sessionCache.remove(roomId)
                    userStateListeners.remove(roomId)?.remove()
                }

                publishRooms()
            }
    }
    /**
     * Garantiza un listener de foto para el usuario dado (1:1). Al cambiar `photoUrl`
     * en `users/{userUid}`, actualiza el cache y **refresca** todas las rooms 1:1
     * donde participa este usuario, publicando la nueva lista con [publishRooms].
     *
     * @param userUid UID del usuario del cual se escucha `photoUrl`.
     * @param roomId Room que originó esta subscripción (no se usa directamente; se
     *               escucha por user para reutilizar la foto en múltiples rooms 1:1).
     */
    private fun ensureUserPhotoListener(userUid: String, roomId: String) {
        if (userPhotoListeners.containsKey(userUid)) return

        val l = Firebase.firestore.collection("users")
            .document(userUid)
            .addSnapshotListener { snap, _ ->
                val newUrl = snap?.getString("photoUrl") // ajusta el nombre del campo si usas otro
                val oldUrl = userPhotoCache[userUid]
                if (oldUrl == newUrl) return@addSnapshotListener

                userPhotoCache[userUid] = newUrl

                // Actualiza todas las rooms 1:1 donde participa este user
                val updated = baseRooms.mapValues { (rid, room) ->
                    if (!room.isGroup && room.participantIds.any { it == userUid }) {
                        room.copy(photoUrl = newUrl)
                    } else room
                }
                baseRooms.clear()
                baseRooms.putAll(updated)
                publishRooms()
            }

        userPhotoListeners[userUid] = l
    }

    /**
     * Limpia todos los listeners activos y caches asociados cuando el ViewModel
     * es destruido por el sistema (fin del ciclo de vida).
     * - Detiene listener global de rooms.
     * - Detiene listeners por room (userState).
     * - Detiene listeners de foto de usuario.
     */
    override fun onCleared() {
        roomsListener?.remove()
        userStateListeners.values.forEach { it.remove() }
        userStateListeners.clear()

        // NUEVO: limpia listeners de fotos
        userPhotoListeners.values.forEach { it.remove() }
        userPhotoListeners.clear()

        super.onCleared()
    }

    /**
     * Asegura un listener del documento `rooms/{roomId}/userState/{ownerUid}` para leer
     * el “summary” **cifrado** del último mensaje dirigido al propietario. En cada cambio:
     * - Si hay error → marca estado con `hasError=true`.
     * - Si no existe el doc → elimina estado.
     * - Si existe → lanza corrutina en IO que descifra el payload con [decryptSummary]
     *   y luego publica con [publishRooms].
     *
     * @param roomId Identificador de la sala.
     */

    private fun ensureUserStateListener(roomId: String) {
        val ownerUid = currentUserUid ?: return
        if (userStateListeners.containsKey(roomId)) return

        val listener = Firebase.firestore
            .collection("rooms")
            .document(roomId)
            .collection("userState")
            .document(ownerUid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    summaryStates[roomId] = RoomSummaryState(null,
                        hasError = true,
                        requiresResync = false)
                    publishRooms()
                    return@addSnapshotListener
                }

                if (snapshot == null || !snapshot.exists()) {
                    summaryStates.remove(roomId)
                    publishRooms()
                    return@addSnapshotListener
                }

                viewModelScope.launch(Dispatchers.IO) {
                    val summaryState = decryptSummary(roomId, snapshot, ownerUid)
                    if (summaryState == null) {
                        summaryStates.remove(roomId)
                    } else {
                        summaryStates[roomId] = summaryState
                    }
                    publishRooms()
                }
            }

        userStateListeners[roomId] = listener
    }
    /**
     * Descifra el “summary” del room:
     * - Obtiene/Cachea la sesión criptográfica ([SessionKeyInfo]) para la room.
     * - Construye el [EncryptionPayload] con [buildSummaryPayload] desde el snapshot.
     * - Construye metadatos con [buildSummaryMetadata].
     * - Llama a [MessageCrypto.decrypt] y obtiene el `preview` (texto) si todo sale bien.
     * - Marca `requiresResync` si la sesión lo indica o el resultado lo requiere.
     *
     * @param roomId Room objetivo.
     * @param snapshot Snapshot del doc `userState/{ownerUid}` con campos summary*.
     * @param ownerUid UID del propietario (destinatario del resumen).
     * @return [RoomSummaryState] con preview/flags, o null si falla carga de sesión.
     */
    private suspend fun decryptSummary(
        roomId: String,
        snapshot: DocumentSnapshot,
        ownerUid: String,
    ): RoomSummaryState? {
        val room = baseRooms[roomId] ?: return null
        val session = sessionCache[roomId] ?: run {
            val peerUid = if (room.isGroup) null else room.participantIds.firstOrNull { it != ownerUid }
            val info = try {
                sessionKeyRepository.loadSessionKey(
                    roomId = roomId,
                    ownerUid = ownerUid,
                    isGroup = room.isGroup,
                    peerUid = peerUid,
                )
            } catch (error: Exception) {
                _error.postValue(error)
                return null
            }
            sessionCache[roomId] = info
            info
        }

        val payload = buildSummaryPayload(snapshot) ?: return RoomSummaryState(
            preview = null,
            hasError = true,
            requiresResync = false,
        )

        val metadata = buildSummaryMetadata(snapshot, payload, session, ownerUid)
        val result = MessageCrypto.decrypt(session, payload, metadata)

        val previewText = result.body?.text
        val requiresResync = result.requiresResync || session.requiresReauth
        val hasError = previewText == null

        return RoomSummaryState(
            preview = previewText,
            hasError = hasError,
            requiresResync = requiresResync,
        )
    }
    /**
     * Construye el payload de cifrado (ciphertext, nonce, salt, versión de esquema
     * y objetivo de cifrado) a partir del snapshot `userState`.
     *
     * @param snapshot Documento con campos `summaryCiphertext`, `summaryNonce`,
     * `summarySalt`, `summarySchemeVersion`, `summaryEncryptionTarget`.
     * @return [EncryptionPayload] válido o `null` si faltan campos esenciales.
     */
    private fun buildSummaryPayload(snapshot: DocumentSnapshot): EncryptionPayload? {
        val ciphertext = snapshot.getString("summaryCiphertext") ?: return null
        val nonce = snapshot.getString("summaryNonce") ?: return null
        val salt = snapshot.getString("summarySalt") ?: return null
        val schemeVersion = snapshot.getLong("summarySchemeVersion")?.toInt()
            ?: MessageCrypto.CURRENT_SCHEME_VERSION
        val target = snapshot.getString("summaryEncryptionTarget") ?: ""
        return EncryptionPayload(
            ciphertext = ciphertext,
            nonce = nonce,
            salt = salt,
            schemeVersion = schemeVersion,
            encryptionTarget = target,
        )
    }
    /**
     * Construye metadatos de cifrado para el descifrado del summary:
     * - messageType: p. ej., `summary:text/plain` (default).
     * - senderId: remitente del summary (fallback al owner).
     * - readBy: lista de UIDs que leyeron (fallback al owner).
     * - schemeVersion y encryptionTarget: del payload o de la sesión.
     *
     * @param snapshot Snapshot con `summaryMessageType`, `summarySenderId`, `summaryReadBy`.
     * @param payload Payload con parámetros criptográficos.
     * @param session Sesión actual para la room (target/versión).
     * @param ownerUid UID del propietario (fallbacks).
     */
    private fun buildSummaryMetadata(
        snapshot: DocumentSnapshot,
        payload: EncryptionPayload,
        session: SessionKeyInfo,
        ownerUid: String,
    ): MessageCrypto.EncryptionMetadata {
        val messageType = snapshot.getString("summaryMessageType") ?: "summary:text/plain"
        val senderId = snapshot.getString("summarySenderId") ?: ownerUid
        val readByRaw = snapshot.get("summaryReadBy") as? List<*>
        val readBy = readByRaw?.mapNotNull { it as? String }?.ifEmpty { listOf(ownerUid) }
            ?: listOf(ownerUid)
        val encryptionTarget = payload.encryptionTarget.ifEmpty { session.encryptionTarget }

        return MessageCrypto.EncryptionMetadata(
            senderId = senderId,
            messageType = messageType,
            readBy = readBy,
            schemeVersion = payload.schemeVersion,
            encryptionTarget = encryptionTarget,
        )
    }
    /**
     * Purga el campo legacy `lastMessage` dentro del documento de room (si existe),
     * usando `FieldValue.delete()` con `SetOptions.merge()`. Se hace una sola vez por room,
     * controlado por [purgedRooms].
     *
     * @param reference Referencia al documento `rooms/{roomId}`.
     */
    private fun purgeLegacyLastMessage(reference: DocumentReference) {
        reference
            .set(mapOf("lastMessage" to FieldValue.delete()), SetOptions.merge())
            .addOnFailureListener {
            }
    }

    /**
     * Publica la lista de rooms combinadas hacia la UI:
     * - Toma [baseRooms].
     * - Mezcla cada room con su [RoomSummaryState] si existe (preview, flags).
     * - Ordena por `updatedAt` descendentemente.
     * - Emite por [_rooms] con `postValue` (thread-safe desde corrutinas).
     */
    private fun publishRooms() {
        val combined = baseRooms.values.map { room ->
            val summary = summaryStates[room.id]
            room.copy(
                lastMessagePreview = summary?.preview,
                summaryError = summary?.hasError == true,
                summaryRequiresResync = summary?.requiresResync == true,
            )
        }.sortedByDescending { it.updatedAt?.toDate()?.time ?: 0L }

        _rooms.postValue(combined)
    }
    /**
     * Estructura interna para guardar el resultado del descifrado del summary.
     *
     * @property preview Texto del preview (si fue posible descifrar).
     * @property hasError true si no se pudo obtener/descifrar el preview.
     * @property requiresResync true si se necesita resincronizar llaves/sesión.
     */
    private data class RoomSummaryState(
        val preview: String?,
        val hasError: Boolean,
        val requiresResync: Boolean,
    )
}
