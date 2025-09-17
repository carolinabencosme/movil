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
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class KeyManager(context: Context) {

    data class KeyGenerationResult(
        val bundle: KeyBundle,
        val identityKeyUpdated: Boolean,
        val signedPreKeyUpdated: Boolean,
        val oneTimePreKeysUpdated: Boolean
    )

    private data class StoredKeyPair(val publicKey: String, val privateKey: String)

    private data class StoredSignedPreKey(
        val keyId: Int,
        val publicKey: String,
        val privateKey: String,
        val signature: String
    )

    private data class StoredOneTimePreKey(
        val keyId: Int,
        val publicKey: String,
        val privateKey: String
    )

    private val prefs: SharedPreferences
    private val lock = Any()

    init {
        try {
            TinkConfig.register()
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("Unable to initialise Tink", e)
        }

        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun ensureKeyBundle(minOneTimePreKeys: Int = DEFAULT_ONE_TIME_PRE_KEY_POOL_SIZE): KeyGenerationResult {
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

    fun rotateSignedPreKey(): KeyGenerationResult {
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

    fun getCachedBundle(): KeyBundle? {
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

    fun getRemainingOneTimePreKeyCount(): Int = loadOneTimePreKeys().size

    fun ensureMinimumOneTimePreKeys(threshold: Int = MIN_ONE_TIME_PRE_KEY_THRESHOLD): KeyGenerationResult? {
        synchronized(lock) {
            val current = loadOneTimePreKeys()
            return if (current.size < threshold) {
                ensureKeyBundle(DEFAULT_ONE_TIME_PRE_KEY_POOL_SIZE)
            } else {
                null
            }
        }
    }

    fun markOneTimePreKeyAsUsed(keyId: Int) {
        synchronized(lock) {
            val preKeys = loadOneTimePreKeys().toMutableList()
            val removed = preKeys.removeAll { it.keyId == keyId }
            if (removed) {
                saveOneTimePreKeys(preKeys)
            }
        }
    }

    fun getPrivateOneTimePreKey(keyId: Int): ByteArray? {
        val match = loadOneTimePreKeys().firstOrNull { it.keyId == keyId } ?: return null
        return decode(match.privateKey)
    }

    fun getIdentityPrivateKey(): ByteArray? {
        val stored = prefs.getString(PREF_IDENTITY_PRIVATE, null) ?: return null
        return decode(stored)
    }

    fun getIdentityPublicKey(): ByteArray? {
        val stored = prefs.getString(PREF_IDENTITY_PUBLIC, null) ?: return null
        return decode(stored)
    }

    fun getIdentityPublicKeyBase64(): String? =
        prefs.getString(PREF_IDENTITY_PUBLIC, null)

    private fun ensureIdentityKeyPair(): Pair<StoredKeyPair, Boolean> {
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

    private fun ensureIdentitySigningKeyPair(): Pair<StoredKeyPair, Boolean> {
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

    private fun ensureSignedPreKey(signingKey: StoredKeyPair, force: Boolean = false): Pair<StoredSignedPreKey, Boolean> {
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

    private fun ensureOneTimePreKeys(targetCount: Int): Pair<List<StoredOneTimePreKey>, Boolean> {
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

    private fun generateOneTimePreKey(): StoredOneTimePreKey {
        val privateKey = X25519.generatePrivateKey()
        val publicKey = X25519.publicFromPrivate(privateKey)
        val keyId = nextPreKeyId()
        return StoredOneTimePreKey(keyId, encode(publicKey), encode(privateKey))
    }

    private fun loadOneTimePreKeys(): List<StoredOneTimePreKey> {
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

    private fun saveOneTimePreKeys(preKeys: List<StoredOneTimePreKey>) {
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

    private fun nextPreKeyId(): Int {
        val next = prefs.getInt(PREF_NEXT_PRE_KEY_ID, INITIAL_PRE_KEY_ID)
        prefs.edit().putInt(PREF_NEXT_PRE_KEY_ID, next + 1).apply()
        return next
    }

    private fun sign(privateKeyBase64: String, data: ByteArray): String {
        val privateKey = decode(privateKeyBase64)
        val signer = Ed25519Sign(privateKey)
        val signature = signer.sign(data)
        return encode(signature)
    }

    private fun encode(data: ByteArray): String =
        Base64.encodeToString(data, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)

    companion object {
        const val DEFAULT_ONE_TIME_PRE_KEY_POOL_SIZE = 20
        const val MIN_ONE_TIME_PRE_KEY_THRESHOLD = 5

        private const val PREFS_NAME = "com.example.texty.keys"
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
