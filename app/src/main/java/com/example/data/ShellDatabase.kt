package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ShellDao {
    // Vault Accounts & Profiles
    @Query("SELECT * FROM vault_accounts ORDER BY createdAt ASC")
    fun observeAccounts(): Flow<List<VaultAccountEntity>>

    @Query("SELECT * FROM vault_accounts WHERE isActive = 1 LIMIT 1")
    fun observeActiveAccount(): Flow<VaultAccountEntity?>

    @Query("SELECT * FROM vault_accounts WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveAccount(): VaultAccountEntity?

    @Query("SELECT * FROM vault_accounts WHERE pubkeyHex = :pubkeyHex LIMIT 1")
    suspend fun getAccountByPubkey(pubkeyHex: String): VaultAccountEntity?

    @Query("SELECT COUNT(*) FROM vault_accounts")
    suspend fun countAccounts(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccount(account: VaultAccountEntity)

    @Query("UPDATE vault_accounts SET isActive = 0")
    suspend fun clearActiveAccounts()

    @Query("UPDATE vault_accounts SET isActive = 1 WHERE pubkeyHex = :pubkeyHex")
    suspend fun markAccountActive(pubkeyHex: String)

    @Transaction
    suspend fun switchActiveAccount(pubkeyHex: String) {
        clearActiveAccounts()
        markAccountActive(pubkeyHex)
    }

    // Relays
    @Query("SELECT * FROM configured_relays ORDER BY addedAt ASC")
    fun observeRelays(): Flow<List<RelayEntity>>

    @Query("SELECT * FROM configured_relays ORDER BY addedAt ASC")
    suspend fun getRelaysList(): List<RelayEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRelay(relay: RelayEntity)

    @Query("DELETE FROM configured_relays WHERE url = :url")
    suspend fun deleteRelay(url: String)

    // Notes (NIP-01 Kind 1)
    @Query("SELECT * FROM nostr_notes ORDER BY createdAtSec DESC LIMIT 100")
    fun observeNotes(): Flow<List<NoteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNote(note: NoteEntity)

    // Applets & Nsites
    @Query("SELECT * FROM installed_applets WHERE isInstalled = 1 ORDER BY pinOrder ASC, updatedAt DESC")
    fun observeInstalledApplets(): Flow<List<InstalledAppletEntity>>

    @Query("SELECT * FROM installed_applets ORDER BY isInstalled DESC, updatedAt DESC")
    fun observeAllCatalogApplets(): Flow<List<InstalledAppletEntity>>

    @Query("SELECT * FROM installed_applets WHERE appletId = :appletId LIMIT 1")
    suspend fun getAppletById(appletId: String): InstalledAppletEntity?

    @Query("SELECT COUNT(*) FROM installed_applets")
    suspend fun countApplets(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertApplet(applet: InstalledAppletEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertApplets(applets: List<InstalledAppletEntity>)

    @Query("UPDATE installed_applets SET isInstalled = :installed, updatedAt = :now WHERE appletId = :appletId")
    suspend fun setAppletInstalled(appletId: String, installed: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE installed_applets SET enableNip07 = :enabled WHERE appletId = :appletId")
    suspend fun setAppletNip07(appletId: String, enabled: Boolean)

    // Isolated NAP-STORAGE
    @Query("SELECT storageValue FROM applet_nap_storage WHERE appletId = :appletId AND storageKey = :key LIMIT 1")
    suspend fun getAppletStorageValue(appletId: String, key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAppletStorageValue(entry: AppletStorageEntity)

    @Query("DELETE FROM applet_nap_storage WHERE appletId = :appletId AND storageKey = :key")
    suspend fun removeAppletStorageValue(appletId: String, key: String)

    // Signer Policies & Audit
    @Query("SELECT * FROM signer_policies WHERE policyKey = :policyKey LIMIT 1")
    suspend fun getSignerPolicy(policyKey: String): SignerPolicyEntity?

    @Query("SELECT * FROM signer_policies ORDER BY createdAt DESC")
    fun observeSignerPolicies(): Flow<List<SignerPolicyEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSignerPolicy(policy: SignerPolicyEntity)

    @Query("DELETE FROM signer_policies WHERE policyKey = :policyKey")
    suspend fun deleteSignerPolicy(policyKey: String)

    @Query("SELECT * FROM signer_audit_log ORDER BY timestamp DESC LIMIT 80")
    fun observeAuditLog(): Flow<List<SignerAuditEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuditEntry(entry: SignerAuditEntity)
}

@Database(
    entities = [
        VaultAccountEntity::class,
        RelayEntity::class,
        NoteEntity::class,
        InstalledAppletEntity::class,
        AppletStorageEntity::class,
        SignerPolicyEntity::class,
        SignerAuditEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class ShellDatabase : RoomDatabase() {
    abstract fun shellDao(): ShellDao

    companion object {
        @Volatile
        private var INSTANCE: ShellDatabase? = null

        fun getInstance(context: Context): ShellDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ShellDatabase::class.java,
                    "sovereign_shell.db"
                ).fallbackToDestructiveMigration(true).build().also { INSTANCE = it }
            }
        }
    }
}
