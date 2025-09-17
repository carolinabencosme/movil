package com.example.texty.repository

import android.content.Context
import com.example.texty.crypto.KeyManager
import com.example.texty.model.KeyBundle
import com.example.texty.model.User
import com.example.texty.model.toKeyBundle
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class KeyRepository private constructor(
    context: Context,
    private val firestore: FirebaseFirestore = Firebase.firestore
) {

    private val keyManager = KeyManager(context.applicationContext)
    private val keyCache = MutableStateFlow<Map<String, KeyBundle>>(emptyMap())

    companion object {
        @Volatile
        private var instance: KeyRepository? = null

        fun getInstance(context: Context): KeyRepository {
            return instance ?: synchronized(this) {
                instance ?: KeyRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    fun observeCache(): StateFlow<Map<String, KeyBundle>> = keyCache.asStateFlow()

    fun getCachedBundle(uid: String): KeyBundle? = keyCache.value[uid]

    fun cacheBundle(uid: String, bundle: KeyBundle) {
        keyCache.update { it + (uid to bundle) }
    }

    suspend fun ensureLocalKeys(uid: String): KeyBundle {
        val result = withContext(Dispatchers.IO) {
            keyManager.ensureKeyBundle()
        }
        publishIfNeeded(uid, result)
        cacheBundle(uid, result.bundle)
        return result.bundle
    }

    suspend fun refreshOneTimePreKeysIfNeeded(uid: String): KeyBundle? {
        val currentCount = keyManager.getRemainingOneTimePreKeyCount()
        return if (currentCount < KeyManager.MIN_ONE_TIME_PRE_KEY_THRESHOLD) {
            val result = withContext(Dispatchers.IO) {
                keyManager.ensureKeyBundle()
            }
            publishIfNeeded(uid, result)
            cacheBundle(uid, result.bundle)
            result.bundle
        } else {
            null
        }
    }

    suspend fun fetchBundle(uid: String): KeyBundle? {
        keyCache.value[uid]?.let { return it }
        val snapshot = firestore.collection(USERS_COLLECTION).document(uid).get().await()
        val user = snapshot.toObject(User::class.java)
        val bundle = user?.toKeyBundle()
        if (bundle != null) {
            cacheBundle(uid, bundle)
        }
        return bundle
    }

    fun markOneTimePreKeyAsUsed(keyId: Int) {
        keyManager.markOneTimePreKeyAsUsed(keyId)
    }

    suspend fun getLocalBundle(): KeyBundle? = withContext(Dispatchers.IO) {
        keyManager.getCachedBundle()
    }

    private suspend fun publishIfNeeded(uid: String, result: KeyManager.KeyGenerationResult) {
        val docRef = firestore.collection(USERS_COLLECTION).document(uid)
        val snapshot = docRef.get().await()
        val user = snapshot.toObject(User::class.java)

        val shouldUpload = user == null ||
            user.identityPublicKey.isNullOrBlank() ||
            user.identitySignaturePublicKey.isNullOrBlank() ||
            user.signedPreKey.isNullOrBlank() ||
            user.signedPreKeySignature.isNullOrBlank() ||
            user.signedPreKeyId == null ||
            user.oneTimePreKeys.isEmpty() ||
            result.identityKeyUpdated ||
            result.signedPreKeyUpdated ||
            result.oneTimePreKeysUpdated

        if (shouldUpload) {
            val data = mapOf(
                "identityPublicKey" to result.bundle.identityPublicKey,
                "identitySignaturePublicKey" to result.bundle.identitySignaturePublicKey,
                "signedPreKeyId" to result.bundle.signedPreKeyId,
                "signedPreKey" to result.bundle.signedPreKey,
                "signedPreKeySignature" to result.bundle.signedPreKeySignature,
                "oneTimePreKeys" to result.bundle.oneTimePreKeys
            )
            docRef.set(data, SetOptions.merge()).await()
        }
    }

    private companion object {
        private const val USERS_COLLECTION = "users"
    }
}
