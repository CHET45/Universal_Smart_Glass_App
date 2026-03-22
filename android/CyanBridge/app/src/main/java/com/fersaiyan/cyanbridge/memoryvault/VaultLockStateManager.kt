package com.fersaiyan.cyanbridge.memoryvault

import android.content.Context
import com.fersaiyan.cyanbridge.data.local.entity.VaultLockStateEntity
import com.fersaiyan.cyanbridge.memoryvault.crypto.VaultKeyManager
import com.fersaiyan.cyanbridge.ui.MyApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

object VaultLockStateManager {
    private const val PREFS = "memory_vault_lock_state"
    private const val KEY_LOCKED = "locked"
    private const val KEY_LOCKED_AT = "locked_at"
    private const val KEY_LAST_UNLOCKED_AT = "last_unlocked_at"

    fun isLocked(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_LOCKED, false)
    }

    fun requiresPassphrase(context: Context): Boolean = VaultKeyManager.requiresPassphrase(context)

    fun lock(context: Context) {
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putBoolean(KEY_LOCKED, true)
            .putLong(KEY_LOCKED_AT, now)
            .apply()
        VaultKeyManager.lock()
        persist(context)
    }

    fun unlockWithDevice(context: Context): Boolean {
        if (VaultKeyManager.requiresPassphrase(context)) return false
        val ok = VaultKeyManager.unlockWithDevice(context)
        if (ok) {
            val now = System.currentTimeMillis()
            prefs(context).edit()
                .putBoolean(KEY_LOCKED, false)
                .putLong(KEY_LAST_UNLOCKED_AT, now)
                .apply()
            persist(context)
        }
        return ok
    }

    fun unlockWithPassphrase(context: Context, passphrase: CharArray): Boolean {
        val ok = VaultKeyManager.unlockWithPassphrase(context, passphrase)
        if (ok) {
            val now = System.currentTimeMillis()
            prefs(context).edit()
                .putBoolean(KEY_LOCKED, false)
                .putLong(KEY_LAST_UNLOCKED_AT, now)
                .apply()
            persist(context)
        }
        return ok
    }

    fun setPassphrase(context: Context, passphrase: CharArray): Boolean {
        val ok = VaultKeyManager.setPassphrase(context, passphrase)
        if (ok) {
            prefs(context).edit().putBoolean(KEY_LOCKED, true).apply()
            VaultKeyManager.lock()
            persist(context)
        }
        return ok
    }

    fun clearPassphrase(context: Context) {
        VaultKeyManager.clearPassphrase(context)
        persist(context)
    }

    fun ensureUnlockedForRead(context: Context): Boolean {
        if (isLocked(context)) return false
        return VaultKeyManager.getUnlockedMasterKey(context) != null
    }

    fun clearAll(context: Context) {
        prefs(context).edit().clear().apply()
        VaultKeyManager.clearAll(context)
        runCatching {
            runBlocking(Dispatchers.IO) {
                MyApplication.database.memoryVaultDao().deleteAllLockState()
            }
        }
    }

    private fun persist(context: Context) {
        runCatching {
            runBlocking(Dispatchers.IO) {
                val p = prefs(context)
                MyApplication.database.memoryVaultDao().upsertLockState(
                    VaultLockStateEntity(
                        isLocked = p.getBoolean(KEY_LOCKED, false),
                        requiresPassphrase = VaultKeyManager.requiresPassphrase(context),
                        lockedAt = p.getLong(KEY_LOCKED_AT, 0L),
                        lastUnlockedAt = p.getLong(KEY_LAST_UNLOCKED_AT, 0L),
                    )
                )
            }
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
