package com.example.texty.model

/**
 * Immutable representation of the public portion of a user's cryptographic key bundle.
 */
const val DEFAULT_IDENTITY_KEY_ID = 1

data class KeyBundle(
    val identityKeyId: Int = DEFAULT_IDENTITY_KEY_ID,
    val identityPublicKey: String,
    val identitySignaturePublicKey: String,
    val signedPreKeyId: Int,
    val signedPreKey: String,
    val signedPreKeySignature: String,
    val oneTimePreKeys: List<OneTimePreKeyInfo>
)

data class OneTimePreKeyInfo(
    val keyId: Int = 0,
    val publicKey: String = ""
)

fun User.toKeyBundle(): KeyBundle? {
    val identity = identityPublicKey
    val signatureKey = identitySignaturePublicKey
    val signedKey = signedPreKey
    val signature = signedPreKeySignature
    val signedKeyId = signedPreKeyId

    if (identity.isNullOrBlank() || signatureKey.isNullOrBlank() || signedKey.isNullOrBlank() ||
        signature.isNullOrBlank() || signedKeyId == null
    ) {
        return null
    }

    return KeyBundle(
        identityPublicKey = identity,
        identitySignaturePublicKey = signatureKey,
        signedPreKeyId = signedKeyId,
        signedPreKey = signedKey,
        signedPreKeySignature = signature,
        oneTimePreKeys = oneTimePreKeys
    )
}
