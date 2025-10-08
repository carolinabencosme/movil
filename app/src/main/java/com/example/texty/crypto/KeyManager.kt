package com.example.texty.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.texty.model.DEFAULT_IDENTITY_KEY_ID
import com.example.texty.model.KeyBundle
import com.example.texty.model.OneTimePreKeyInfo
import com.google.crypto.tink.config.TinkConfig
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.X25519
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Gestiona la generación y almacenamiento seguro de llaves compatibles con Signal.
 */
/**
 * Clase responsable de la gestión, generación y almacenamiento seguro de llaves criptográficas
 * compatibles con el protocolo Signal.
 *
 * Esta clase maneja las llaves necesarias para establecer sesiones cifradas de extremo a extremo:
 * - Llave de identidad (X25519)
 * - Llave de firma (Ed25519)
 * - Signed PreKey (llave temporal firmada)
 * - One-Time PreKeys (llaves efímeras de un solo uso)
 *
 * Utiliza [EncryptedSharedPreferences] para almacenar las llaves de forma cifrada y [Google Tink]
 * para generar y firmar las claves.
 */
class KeyManager(context: Context) {

    /**
     * Resultado agregado al asegurar llaves.
     */
    /**
     * Resultado agregado del proceso de generación o verificación de llaves.
     *
     * @property bundle Paquete de llaves públicas listo para enviar al servidor.
     * @property identityKeyUpdated Indica si se generó una nueva llave de identidad.
     * @property signedPreKeyUpdated Indica si se generó una nueva signed pre-key.
     * @property oneTimePreKeysUpdated Indica si se generaron nuevas one-time pre-keys.
     */
    data class KeyGenerationResult(
        val bundle: KeyBundle,
        val identityKeyUpdated: Boolean,
        val signedPreKeyUpdated: Boolean,
        val oneTimePreKeysUpdated: Boolean
    )

    /**
     * Representa un par de llaves cacheado.
     */
    /** Representa un par de llaves cacheado (pública y privada en Base64). */
    private data class StoredKeyPair(val publicKey: String, val privateKey: String)

    /**
     * Representa un signed pre-key almacenado localmente.
     */
    private data class StoredSignedPreKey(
        val keyId: Int,
        val publicKey: String,
        val privateKey: String,
        val signature: String
    )

    /** Representa una one-time pre-key almacenada localmente. */
    private data class StoredOneTimePreKey(
        val keyId: Int,
        val publicKey: String,
        val privateKey: String
    )

    private val prefs: SharedPreferences
    private val lock = Any()

    init {
        // Registra Tink y prepara preferencias cifradas.
        try {
            TinkConfig.register()
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("Unable to initialise Tink", e)
        }

        prefs = buildSecurePrefs(context)
    }

    /**
     * Construye las preferencias cifradas utilizando [EncryptedSharedPreferences].
     * Si ocurre corrupción en la llave maestra, borra la entrada del Keystore y la reconstruye.
     */
    private fun buildSecurePrefs(context: Context): SharedPreferences {
        fun create(): SharedPreferences {
            val masterKey = MasterKey.Builder(context, MASTER_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                // .setRequestStrongBoxBacked(false) // descomentar si en tu parque hay fallas con StrongBox
                .build()

            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        return try {
            create()
        } catch (e: Exception) {
            // Si hay corrupción criptográfica, realiza un reset controlado
            val isCorruption = e is AEADBadTagException ||
                    generateSequence(e as Throwable?) { it?.cause }.any {
                        it is AEADBadTagException ||
                                (it?.message?.contains("MAC verification failed", true) == true)
                    }
            if (!isCorruption) throw e

            // Reset controlado: borra alias del Keystore y el prefs cifrado
            try {
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (ks.containsAlias(MASTER_ALIAS)) ks.deleteEntry(MASTER_ALIAS)
            } catch (_: Exception) { /* opcional: log */ }

            context.deleteSharedPreferences(PREFS_NAME)

            create()
        }
    }
    /**
     * Garantiza la existencia de todas las llaves necesarias para el cifrado y devuelve
     * un [KeyGenerationResult] con el estado actual del bundle.
     *
     * @param minOneTimePreKeys cantidad mínima de pre-keys efímeras que deben existir.
     */
    fun ensureKeyBundle(minOneTimePreKeys: Int = DEFAULT_ONE_TIME_PRE_KEY_POOL_SIZE): KeyGenerationResult {
        // Garantiza un paquete listo de llaves activas.
        synchronized(lock) {
            val (identity, newIdentity) = ensureIdentityKeyPair()
            val (signingKey, newSigningKey) = ensureIdentitySigningKeyPair()
            val (signedPreKey, newSignedPreKey) = ensureSignedPreKey(signingKey)
            val (oneTimePreKeys, newPreKeys) = ensureOneTimePreKeys(minOneTimePreKeys)

            val bundle = KeyBundle(
                identityKeyId = DEFAULT_IDENTITY_KEY_ID,
                identityPublicKey = identity.publicKey,
                identitySignaturePublicKey = signingKey.publicKey,
                signedPreKeyId = signedPreKey.keyId,
                signedPreKey = signedPreKey.publicKey,
                signedPreKeySignature = signedPreKey.signature,
                oneTimePreKeys = oneTimePreKeys.map { OneTimePreKeyInfo(it.keyId, it.publicKey) }
            )

            return KeyGenerationResult(
                bundle = bundle,
                identityKeyUpdated = newIdentity || newSigningKey,
                signedPreKeyUpdated = newSignedPreKey,
                oneTimePreKeysUpdated = newPreKeys
            )
        }
    }
    /**
     * Fuerza la creación de una nueva Signed Pre-Key.
     * Se usa cuando se desea rotar periódicamente las llaves temporales.
     */
    fun rotateSignedPreKey(): KeyGenerationResult {
        // Fuerza un nuevo signed pre-key.
        synchronized(lock) {
            val (identity, newIdentity) = ensureIdentityKeyPair()
            val (signingKey, newSigningKey) = ensureIdentitySigningKeyPair()
            val (signedPreKey, _) = ensureSignedPreKey(signingKey, force = true)
            val (oneTimePreKeys, newPreKeys) = ensureOneTimePreKeys(DEFAULT_ONE_TIME_PRE_KEY_POOL_SIZE)

            val bundle = KeyBundle(
                identityKeyId = DEFAULT_IDENTITY_KEY_ID,
                identityPublicKey = identity.publicKey,
                identitySignaturePublicKey = signingKey.publicKey,
                signedPreKeyId = signedPreKey.keyId,
                signedPreKey = signedPreKey.publicKey,
                signedPreKeySignature = signedPreKey.signature,
                oneTimePreKeys = oneTimePreKeys.map { OneTimePreKeyInfo(it.keyId, it.publicKey) }
            )

            return KeyGenerationResult(
                bundle = bundle,
                identityKeyUpdated = newIdentity || newSigningKey,
                signedPreKeyUpdated = true,
                oneTimePreKeysUpdated = newPreKeys
            )
        }
    }
    /**
     * Obtiene el último paquete de llaves guardado localmente.
     *
     * @return [KeyBundle] con las llaves públicas, o null si no existe.
     */
    fun getCachedBundle(): KeyBundle? {
        // Obtiene el paquete guardado localmente.
        val identityPublic = prefs.getString(PREF_IDENTITY_PUBLIC, null)
        val signingPublic = prefs.getString(PREF_IDENTITY_SIGNING_PUBLIC, null)
        val signedPreKeyPublic = prefs.getString(PREF_SIGNED_PRE_KEY_PUBLIC, null)
        val signedPreKeySignature = prefs.getString(PREF_SIGNED_PRE_KEY_SIGNATURE, null)
        val signedPreKeyId = prefs.getInt(PREF_SIGNED_PRE_KEY_ID, -1)

        if (identityPublic.isNullOrBlank() || signingPublic.isNullOrBlank() ||
            signedPreKeyPublic.isNullOrBlank() || signedPreKeySignature.isNullOrBlank() ||
            signedPreKeyId == -1
        ) {
            return null
        }

        val preKeys = loadOneTimePreKeys().map { OneTimePreKeyInfo(it.keyId, it.publicKey) }

        return KeyBundle(
            identityKeyId = DEFAULT_IDENTITY_KEY_ID,
            identityPublicKey = identityPublic,
            identitySignaturePublicKey = signingPublic,
            signedPreKeyId = signedPreKeyId,
            signedPreKey = signedPreKeyPublic,
            signedPreKeySignature = signedPreKeySignature,
            oneTimePreKeys = preKeys
        )
    }

    /** Devuelve la cantidad actual de One-Time PreKeys disponibles. */

    fun getRemainingOneTimePreKeyCount(): Int = loadOneTimePreKeys().size

    /**
     * Verifica si hay menos pre-keys que el umbral indicado y las regenera si es necesario.
     *
     * @param threshold número mínimo requerido de pre-keys.
     * @return [KeyGenerationResult] si se generaron nuevas llaves, o null si no fue necesario.
     */
    fun ensureMinimumOneTimePreKeys(threshold: Int = MIN_ONE_TIME_PRE_KEY_THRESHOLD): KeyGenerationResult? {
        // Repone llaves si bajan del umbral dado.
        synchronized(lock) {
            val current = loadOneTimePreKeys()
            return if (current.size < threshold) {
                ensureKeyBundle(DEFAULT_ONE_TIME_PRE_KEY_POOL_SIZE)
            } else {
                null
            }
        }
    }
    /**
     * Marca una One-Time PreKey como usada y la elimina del almacenamiento.
     *
     * @param keyId identificador de la llave utilizada.
     */
    fun markOneTimePreKeyAsUsed(keyId: Int) {
        // Remueve la llave de un solo uso utilizada.
        synchronized(lock) {
            val preKeys = loadOneTimePreKeys().toMutableList()
            val removed = preKeys.removeAll { it.keyId == keyId }
            if (removed) {
                saveOneTimePreKeys(preKeys)
            }
        }
    }
    /** Obtiene la llave privada asociada a un ID de One-Time PreKey. */
    fun getPrivateOneTimePreKey(keyId: Int): ByteArray? {
        // Devuelve la llave privada asociada a un id.
        val match = loadOneTimePreKeys().firstOrNull { it.keyId == keyId } ?: return null
        return decode(match.privateKey)
    }
    /** Devuelve la llave privada de identidad (X25519). */
    fun getIdentityPrivateKey(): ByteArray? {
        // Lee la llave privada de identidad.
        val stored = prefs.getString(PREF_IDENTITY_PRIVATE, null) ?: return null
        return decode(stored)
    }
    /** Devuelve la llave pública de identidad (X25519). */
    fun getIdentityPublicKey(): ByteArray? {
        // Lee la llave pública de identidad.
        val stored = prefs.getString(PREF_IDENTITY_PUBLIC, null) ?: return null
        return decode(stored)
    }
    /** Devuelve la llave pública de identidad en formato Base64. */
    fun getIdentityPublicKeyBase64(): String? =
        // Devuelve la llave pública codificada.
        prefs.getString(PREF_IDENTITY_PUBLIC, null)


    /**
     * Garantiza la existencia de la llave de identidad X25519.
     * Si no existe, la genera y la guarda.
     */
    private fun ensureIdentityKeyPair(): Pair<StoredKeyPair, Boolean> {
        // Crea o reutiliza la pareja X25519 principal.
        val existingPublic = prefs.getString(PREF_IDENTITY_PUBLIC, null)
        val existingPrivate = prefs.getString(PREF_IDENTITY_PRIVATE, null)
        if (!existingPublic.isNullOrBlank() && !existingPrivate.isNullOrBlank()) {
            return StoredKeyPair(existingPublic, existingPrivate) to false
        }

        val privateKey = X25519.generatePrivateKey()
        val publicKey = X25519.publicFromPrivate(privateKey)

        val stored = StoredKeyPair(encode(publicKey), encode(privateKey))
        prefs.edit()
            .putString(PREF_IDENTITY_PUBLIC, stored.publicKey)
            .putString(PREF_IDENTITY_PRIVATE, stored.privateKey)
            .apply()

        return stored to true
    }
    /**
     * Garantiza la existencia de la llave de firma Ed25519.
     * Si no existe, la crea y la almacena cifrada.
     */
    private fun ensureIdentitySigningKeyPair(): Pair<StoredKeyPair, Boolean> {
        // Crea o reutiliza la pareja Ed25519 de firma.
        val existingPublic = prefs.getString(PREF_IDENTITY_SIGNING_PUBLIC, null)
        val existingPrivate = prefs.getString(PREF_IDENTITY_SIGNING_PRIVATE, null)
        if (!existingPublic.isNullOrBlank() && !existingPrivate.isNullOrBlank()) {
            return StoredKeyPair(existingPublic, existingPrivate) to false
        }

        val keyPair = Ed25519Sign.KeyPair.newKeyPair()
        val stored = StoredKeyPair(encode(keyPair.publicKey), encode(keyPair.privateKey))

        prefs.edit()
            .putString(PREF_IDENTITY_SIGNING_PUBLIC, stored.publicKey)
            .putString(PREF_IDENTITY_SIGNING_PRIVATE, stored.privateKey)
            .apply()

        return stored to true
    }
    /**
     * Genera o recupera la Signed PreKey actual. Si force = true, genera una nueva versión.
     */
    private fun ensureSignedPreKey(signingKey: StoredKeyPair, force: Boolean = false): Pair<StoredSignedPreKey, Boolean> {
        // Genera un signed pre-key y firma con la llave de identidad.
        val existingId = prefs.getInt(PREF_SIGNED_PRE_KEY_ID, -1)
        val existingPublic = prefs.getString(PREF_SIGNED_PRE_KEY_PUBLIC, null)
        val existingPrivate = prefs.getString(PREF_SIGNED_PRE_KEY_PRIVATE, null)
        val existingSignature = prefs.getString(PREF_SIGNED_PRE_KEY_SIGNATURE, null)

        if (!force && existingId != -1 && !existingPublic.isNullOrBlank() &&
            !existingPrivate.isNullOrBlank() && !existingSignature.isNullOrBlank()
        ) {
            val stored = StoredSignedPreKey(existingId, existingPublic, existingPrivate, existingSignature)
            return stored to false
        }

        val privateKey = X25519.generatePrivateKey()
        val publicKeyBytes = X25519.publicFromPrivate(privateKey)
        val publicKey = encode(publicKeyBytes)
        val signature = sign(signingKey.privateKey, publicKeyBytes)
        val keyId = nextPreKeyId()

        val stored = StoredSignedPreKey(keyId, publicKey, encode(privateKey), signature)

        prefs.edit()
            .putInt(PREF_SIGNED_PRE_KEY_ID, keyId)
            .putString(PREF_SIGNED_PRE_KEY_PUBLIC, stored.publicKey)
            .putString(PREF_SIGNED_PRE_KEY_PRIVATE, stored.privateKey)
            .putString(PREF_SIGNED_PRE_KEY_SIGNATURE, stored.signature)
            .apply()

        return stored to true
    }
    /**
     * Asegura que exista un número suficiente de One-Time PreKeys.
     */
    private fun ensureOneTimePreKeys(targetCount: Int): Pair<List<StoredOneTimePreKey>, Boolean> {
        // Garantiza un pool suficiente de pre-keys de un uso.
        val current = loadOneTimePreKeys().toMutableList()
        if (current.size >= targetCount) {
            return current to false
        }

        val needed = targetCount - current.size
        repeat(needed) {
            current.add(generateOneTimePreKey())
        }
        saveOneTimePreKeys(current)
        return current to true
    }
    /** Genera una nueva One-Time PreKey con identificador único. */
    private fun generateOneTimePreKey(): StoredOneTimePreKey {
        // Genera una nueva llave efímera con id único.
        val privateKey = X25519.generatePrivateKey()
        val publicKey = X25519.publicFromPrivate(privateKey)
        val keyId = nextPreKeyId()
        return StoredOneTimePreKey(keyId, encode(publicKey), encode(privateKey))
    }
    /** Carga y parsea todas las One-Time PreKeys almacenadas. */
    private fun loadOneTimePreKeys(): List<StoredOneTimePreKey> {
        // Lee y parsea las llaves guardadas.
        val raw = prefs.getString(PREF_ONE_TIME_PRE_KEYS, null) ?: return emptyList()
        val list = mutableListOf<StoredOneTimePreKey>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val keyId = obj.getInt(KEY_ID)
                val publicKey = obj.getString(KEY_PUBLIC)
                val privateKey = obj.getString(KEY_PRIVATE)
                list.add(StoredOneTimePreKey(keyId, publicKey, privateKey))
            }
        } catch (ignored: JSONException) {
            return emptyList()
        }
        return list.sortedBy { it.keyId }
    }
    /** Serializa y guarda la lista ordenada de One-Time PreKeys. */
    private fun saveOneTimePreKeys(preKeys: List<StoredOneTimePreKey>) {
        // Serializa y guarda la lista ordenada de llaves.
        val array = JSONArray()
        preKeys.sortedBy { it.keyId }.forEach { preKey ->
            val obj = JSONObject()
            obj.put(KEY_ID, preKey.keyId)
            obj.put(KEY_PUBLIC, preKey.publicKey)
            obj.put(KEY_PRIVATE, preKey.privateKey)
            array.put(obj)
        }
        prefs.edit().putString(PREF_ONE_TIME_PRE_KEYS, array.toString()).apply()
    }
    /** Incrementa el contador de identificadores de llaves. */
    private fun nextPreKeyId(): Int {
        // Incrementa el contador de identificadores.
        val next = prefs.getInt(PREF_NEXT_PRE_KEY_ID, INITIAL_PRE_KEY_ID)
        prefs.edit().putInt(PREF_NEXT_PRE_KEY_ID, next + 1).apply()
        return next
    }
    /**
     * Firma los datos con la llave privada Ed25519.
     *
     * @param privateKeyBase64 llave privada en formato Base64.
     * @param data datos a firmar.
     * @return firma en formato Base64.
     */
    private fun sign(privateKeyBase64: String, data: ByteArray): String {
        // Firma datos con la llave Ed25519 privada.
        val privateKey = decode(privateKeyBase64)
        val signer = Ed25519Sign(privateKey)
        val signature = signer.sign(data)
        return encode(signature)
    }
    /** Codifica bytes en Base64 sin saltos de línea. */
    private fun encode(data: ByteArray): String =
        // Codifica bytes a Base64 sin saltos.
        Base64.encodeToString(data, Base64.NO_WRAP)

    /** Decodifica una cadena Base64 a bytes. */
    private fun decode(value: String): ByteArray =
        // Decodifica Base64 sin saltos.
        Base64.decode(value, Base64.NO_WRAP)

    companion object {
        // Constantes de configuración y claves de preferencias.
        const val DEFAULT_ONE_TIME_PRE_KEY_POOL_SIZE = 20
        const val MIN_ONE_TIME_PRE_KEY_THRESHOLD = 5

        private const val PREFS_NAME = "com.example.texty.keys"
        private const val MASTER_ALIAS = MasterKey.DEFAULT_MASTER_KEY_ALIAS

        private const val PREF_IDENTITY_PUBLIC = "identity_public"
        private const val PREF_IDENTITY_PRIVATE = "identity_private"
        private const val PREF_IDENTITY_SIGNING_PUBLIC = "identity_signing_public"
        private const val PREF_IDENTITY_SIGNING_PRIVATE = "identity_signing_private"
        private const val PREF_SIGNED_PRE_KEY_ID = "signed_pre_key_id"
        private const val PREF_SIGNED_PRE_KEY_PUBLIC = "signed_pre_key_public"
        private const val PREF_SIGNED_PRE_KEY_PRIVATE = "signed_pre_key_private"
        private const val PREF_SIGNED_PRE_KEY_SIGNATURE = "signed_pre_key_signature"
        private const val PREF_ONE_TIME_PRE_KEYS = "one_time_pre_keys"
        private const val PREF_NEXT_PRE_KEY_ID = "next_pre_key_id"
        private const val INITIAL_PRE_KEY_ID = 1000

        private const val KEY_ID = "id"
        private const val KEY_PUBLIC = "public"
        private const val KEY_PRIVATE = "private"
    }
}
