package com.fersaiyan.cyanbridge.memoryvault

import com.fersaiyan.cyanbridge.data.local.entity.SyncPayloadManifestEntity
import com.fersaiyan.cyanbridge.data.local.entity.SyncPreparationQueueEntity
import com.fersaiyan.cyanbridge.memoryvault.contracts.EncryptedMemoryBlobManifest
import com.fersaiyan.cyanbridge.memoryvault.contracts.MemorySyncRecord
import com.fersaiyan.cyanbridge.memoryvault.contracts.WrappedKeyManifest
import com.fersaiyan.cyanbridge.ui.MyApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

object MemorySyncPreparationService {
    private const val ACTION_UPSERT = "upsert"
    private const val STATUS_PENDING_BACKEND = "pending_backend"
    private const val STATUS_CANCELLED_POLICY = "cancelled_policy"

    data class PreparedSyncPayload(
        val blobManifest: EncryptedMemoryBlobManifest,
        val keyManifest: WrappedKeyManifest,
    )

    suspend fun enqueueUpsert(
        memoryRef: String,
        payload: PreparedSyncPayload,
    ) = withContext(Dispatchers.IO) {
        val dao = MyApplication.database.memoryVaultDao()
        val record = MemorySyncRecord(
            memoryRef = memoryRef,
            actionType = ACTION_UPSERT,
            policyVersion = 1,
            keyManifest = payload.keyManifest,
            blobManifest = payload.blobManifest,
        )
        val body = JSONObject()
            .put("memory_ref", record.memoryRef)
            .put("action_type", record.actionType)
            .put("policy_version", record.policyVersion)
            .put(
                "key_manifest",
                JSONObject()
                    .put("key_ref", record.keyManifest.keyRef)
                    .put("wrapping_version", record.keyManifest.wrappingVersion)
                    .put("wrapped_key_nonce_b64", record.keyManifest.wrappedKeyNonceB64)
                    .put("wrapped_key_ciphertext_b64", record.keyManifest.wrappedKeyCiphertextB64)
            )
            .put(
                "blob_manifest",
                JSONObject()
                    .put("memory_ref", record.blobManifest.memoryRef)
                    .put("crypto_version", record.blobManifest.cryptoVersion)
                    .put("nonce_b64", record.blobManifest.nonceB64)
                    .put("ciphertext_b64", record.blobManifest.ciphertextB64)
                    .put("aad", record.blobManifest.aad)
            )
            .toString()

        val checksum = sha256(body)
        val manifestId = dao.insertPayloadManifest(
            SyncPayloadManifestEntity(
                memoryRef = memoryRef,
                schemaVersion = 1,
                payloadJson = body,
                checksumSha256 = checksum,
                createdAt = System.currentTimeMillis(),
            )
        )

        dao.insertSyncQueue(
            SyncPreparationQueueEntity(
                memoryRef = memoryRef,
                actionType = ACTION_UPSERT,
                status = STATUS_PENDING_BACKEND,
                payloadManifestId = manifestId,
                lastError = "Sync backend is not configured yet",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun cancelQueuedForMemoryRef(memoryRef: String, reason: String) = withContext(Dispatchers.IO) {
        MyApplication.database.memoryVaultDao().cancelQueueForMemoryRef(
            memoryRef = memoryRef,
            newStatus = STATUS_CANCELLED_POLICY,
            reason = reason,
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun cancelAllQueued(reason: String) = withContext(Dispatchers.IO) {
        MyApplication.database.memoryVaultDao().cancelAllQueued(
            newStatus = STATUS_CANCELLED_POLICY,
            reason = reason,
            updatedAt = System.currentTimeMillis(),
        )
    }

    fun buildPreparedSyncPayload(
        memoryRef: String,
        cryptoVersion: Int,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: String?,
        keyRef: String,
        wrappingVersion: Int,
        wrappedKeyNonce: ByteArray,
        wrappedKeyCiphertext: ByteArray,
    ): PreparedSyncPayload {
        return PreparedSyncPayload(
            blobManifest = EncryptedMemoryBlobManifest(
                memoryRef = memoryRef,
                cryptoVersion = cryptoVersion,
                nonceB64 = Base64.getEncoder().encodeToString(nonce),
                ciphertextB64 = Base64.getEncoder().encodeToString(ciphertext),
                aad = aad,
            ),
            keyManifest = WrappedKeyManifest(
                keyRef = keyRef,
                wrappingVersion = wrappingVersion,
                wrappedKeyNonceB64 = Base64.getEncoder().encodeToString(wrappedKeyNonce),
                wrappedKeyCiphertextB64 = Base64.getEncoder().encodeToString(wrappedKeyCiphertext),
            )
        )
    }

    private fun sha256(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(hash.size * 2)
        for (b in hash) sb.append("%02x".format(b))
        return sb.toString()
    }
}
