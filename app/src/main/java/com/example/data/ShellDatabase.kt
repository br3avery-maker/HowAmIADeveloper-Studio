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

    @Query("DELETE FROM vault_accounts WHERE pubkeyHex = :pubkeyHex")
    suspend fun deleteAccount(pubkeyHex: String)

    @Query("UPDATE vault_accounts SET isActive = 0")
    suspend fun clearActiveAccounts()

    @Query("UPDATE vault_accounts SET isActive = 1 WHERE pubkeyHex = :pubkeyHex")
    suspend fun markAccountActive(pubkeyHex: String)

    @Transaction
    suspend fun switchActiveAccount(pubkeyHex: String) {
        clearActiveAccounts()
        markAccountActive(pubkeyHex)
    }

    // Relays & Relay Health
    @Query("SELECT * FROM configured_relays ORDER BY addedAt ASC")
    fun observeRelays(): Flow<List<RelayEntity>>

    @Query("SELECT * FROM configured_relays ORDER BY addedAt ASC")
    suspend fun getRelaysList(): List<RelayEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRelay(relay: RelayEntity)

    @Query("DELETE FROM configured_relays WHERE url = :url")
    suspend fun deleteRelay(url: String)

    @Query("SELECT * FROM relay_health")
    fun observeRelayHealth(): Flow<List<RelayHealthEntity>>

    @Query("SELECT * FROM relay_health")
    suspend fun getAllRelayHealth(): List<RelayHealthEntity>

    @Query("SELECT * FROM relay_health WHERE url = :url LIMIT 1")
    suspend fun getRelayHealth(url: String): RelayHealthEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRelayHealth(health: RelayHealthEntity)

    // Blossom Servers & Blossom Health
    @Query("SELECT * FROM configured_blossom_servers ORDER BY isBackupMirror ASC, addedAt ASC")
    fun observeBlossomServers(): Flow<List<ConfiguredBlossomServerEntity>>

    @Query("SELECT * FROM configured_blossom_servers ORDER BY isBackupMirror ASC, addedAt ASC")
    suspend fun getBlossomServersList(): List<ConfiguredBlossomServerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBlossomServer(server: ConfiguredBlossomServerEntity)

    @Query("DELETE FROM configured_blossom_servers WHERE serverUrl = :serverUrl")
    suspend fun deleteBlossomServer(serverUrl: String)

    @Query("SELECT * FROM blossom_server_health")
    fun observeBlossomHealth(): Flow<List<BlossomServerHealthEntity>>

    @Query("SELECT * FROM blossom_server_health")
    suspend fun getAllBlossomHealth(): List<BlossomServerHealthEntity>

    @Query("SELECT * FROM blossom_server_health WHERE serverUrl = :serverUrl LIMIT 1")
    suspend fun getBlossomHealth(serverUrl: String): BlossomServerHealthEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBlossomHealth(health: BlossomServerHealthEntity)

    // Notes (NIP-01 Kind 1)
    @Query("SELECT * FROM nostr_notes ORDER BY createdAtSec DESC LIMIT 100")
    fun observeNotes(): Flow<List<NoteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNote(note: NoteEntity)

    // Nsites (NIP-5A Catalog & Installed Library)
    @Query("SELECT * FROM installed_applets WHERE isInstalled = 1 AND isNsite = 1 ORDER BY pinOrder ASC, updatedAt DESC")
    fun observeInstalledApplets(): Flow<List<InstalledAppletEntity>>

    @Query("SELECT * FROM installed_applets WHERE isNsite = 1 ORDER BY isInstalled DESC, updatedAt DESC")
    fun observeAllCatalogApplets(): Flow<List<InstalledAppletEntity>>

    @Query("SELECT * FROM installed_applets WHERE isNsite = 1")
    suspend fun getAllCatalogAppletsList(): List<InstalledAppletEntity>

    @Query("SELECT * FROM installed_applets WHERE appletId = :appletId LIMIT 1")
    suspend fun getAppletById(appletId: String): InstalledAppletEntity?

    @Query("SELECT COUNT(*) FROM installed_applets WHERE isNsite = 1")
    suspend fun countApplets(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertApplet(applet: InstalledAppletEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertApplets(applets: List<InstalledAppletEntity>)

    @Query("DELETE FROM installed_applets WHERE appletId IN (:appletIds) AND isInstalled = 0")
    suspend fun deleteUninstalledApplets(appletIds: List<String>)

    @Query("DELETE FROM installed_applets WHERE appletId IN (:appletIds)")
    suspend fun deleteAppletsByIds(appletIds: List<String>)

    @Query("DELETE FROM installed_applets WHERE isNsite = 0")
    suspend fun purgeNonNsiteApplets(): Int

    @Query("UPDATE installed_applets SET isInstalled = :installed, updatedAt = :now WHERE appletId = :appletId")
    suspend fun setAppletInstalled(appletId: String, installed: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE installed_applets SET enableNip07 = :enabled WHERE appletId = :appletId")
    suspend fun setAppletNip07(appletId: String, enabled: Boolean)

    @Query("UPDATE installed_applets SET cachedComplete = :cached WHERE appletId = :appletId")
    suspend fun setAppletCachedComplete(appletId: String, cached: Boolean)

    // Nsite Version History
    @Query("SELECT * FROM nsite_versions WHERE coordinate = :coordinate ORDER BY createdAtSec DESC")
    fun observeVersionsForCoordinate(coordinate: String): Flow<List<NsiteVersionEntity>>

    @Query("SELECT * FROM nsite_versions WHERE coordinate = :coordinate ORDER BY createdAtSec DESC")
    suspend fun getVersionsForCoordinate(coordinate: String): List<NsiteVersionEntity>

    @Query("SELECT * FROM nsite_versions WHERE eventId = :eventId LIMIT 1")
    suspend fun getVersionByEventId(eventId: String): NsiteVersionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNsiteVersion(version: NsiteVersionEntity)

    // Local Multi-File Nsite Projects & Project Files
    @Query("SELECT * FROM nsite_projects ORDER BY updatedAt DESC")
    fun observeAllProjects(): Flow<List<NsiteProjectEntity>>

    @Query("SELECT * FROM nsite_projects ORDER BY updatedAt DESC")
    suspend fun getAllProjectsList(): List<NsiteProjectEntity>

    @Query("SELECT * FROM nsite_projects WHERE projectId = :projectId LIMIT 1")
    suspend fun getProjectById(projectId: String): NsiteProjectEntity?

    @Query("SELECT * FROM nsite_projects WHERE projectId = :projectId LIMIT 1")
    fun observeProjectById(projectId: String): Flow<NsiteProjectEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProject(project: NsiteProjectEntity)

    @Query("DELETE FROM nsite_projects WHERE projectId = :projectId")
    suspend fun deleteProject(projectId: String)

    @Query("SELECT * FROM nsite_project_files WHERE projectId = :projectId ORDER BY relativePath ASC")
    fun observeProjectFiles(projectId: String): Flow<List<NsiteProjectFileEntity>>

    @Query("SELECT * FROM nsite_project_files WHERE projectId = :projectId ORDER BY relativePath ASC")
    suspend fun getProjectFilesList(projectId: String): List<NsiteProjectFileEntity>

    @Query("SELECT * FROM nsite_project_files WHERE projectId = :projectId AND relativePath = :relativePath LIMIT 1")
    suspend fun getProjectFile(projectId: String, relativePath: String): NsiteProjectFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProjectFile(file: NsiteProjectFileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProjectFiles(files: List<NsiteProjectFileEntity>)

    @Query("DELETE FROM nsite_project_files WHERE projectId = :projectId AND relativePath = :relativePath")
    suspend fun deleteProjectFile(projectId: String, relativePath: String)

    @Query("DELETE FROM nsite_project_files WHERE projectId = :projectId")
    suspend fun deleteAllProjectFiles(projectId: String)

    // Isolated Scoped Storage
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

    // NIP-51 Curated Bookmarks & Lists
    @Query("SELECT * FROM curated_bookmarks ORDER BY createdAt DESC")
    fun observeCuratedBookmarks(): Flow<List<CuratedBookmarkEntity>>

    @Query("SELECT * FROM curated_bookmarks WHERE curatorPubkey = :curatorPubkey")
    suspend fun getBookmarksByCurator(curatorPubkey: String): List<CuratedBookmarkEntity>

    @Query("SELECT * FROM curated_bookmarks WHERE coordinate = :coordinate AND curatorPubkey = :curatorPubkey LIMIT 1")
    suspend fun getBookmark(coordinate: String, curatorPubkey: String): CuratedBookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCuratedBookmark(bookmark: CuratedBookmarkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCuratedBookmarks(bookmarks: List<CuratedBookmarkEntity>)

    @Query("DELETE FROM curated_bookmarks WHERE coordinate = :coordinate AND curatorPubkey = :curatorPubkey")
    suspend fun deleteCuratedBookmark(coordinate: String, curatorPubkey: String)

    // Publisher Profiles (Kind 0 Metadata)
    @Query("SELECT * FROM publisher_profiles")
    fun observePublisherProfiles(): Flow<List<PublisherProfileEntity>>

    @Query("SELECT * FROM publisher_profiles WHERE pubkeyHex = :pubkeyHex LIMIT 1")
    suspend fun getPublisherProfile(pubkeyHex: String): PublisherProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPublisherProfile(profile: PublisherProfileEntity)

    @Query("UPDATE nostr_notes SET authorName = :authorName, authorAvatar = CASE WHEN :authorAvatar != '' THEN :authorAvatar ELSE authorAvatar END WHERE pubkeyHex = :pubkeyHex AND isLocalUser = 0")
    suspend fun updateCachedNotesAuthorProfile(pubkeyHex: String, authorName: String, authorAvatar: String)
}

@Database(
    entities = [
        VaultAccountEntity::class,
        RelayEntity::class,
        RelayHealthEntity::class,
        ConfiguredBlossomServerEntity::class,
        BlossomServerHealthEntity::class,
        NoteEntity::class,
        InstalledAppletEntity::class,
        NsiteVersionEntity::class,
        NsiteProjectEntity::class,
        NsiteProjectFileEntity::class,
        AppletStorageEntity::class,
        SignerPolicyEntity::class,
        SignerAuditEntity::class,
        CuratedBookmarkEntity::class,
        PublisherProfileEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class ShellDatabase : RoomDatabase() {
    abstract fun shellDao(): ShellDao

    companion object {
        @Volatile
        private var INSTANCE: ShellDatabase? = null

        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `curated_bookmarks` (
                        `coordinate` TEXT NOT NULL,
                        `curatorPubkey` TEXT NOT NULL,
                        `curatorNpub` TEXT NOT NULL,
                        `listSlug` TEXT NOT NULL,
                        `listTitle` TEXT NOT NULL,
                        `isLocalCurator` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`coordinate`, `curatorPubkey`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `publisher_profiles` (
                        `pubkeyHex` TEXT NOT NULL,
                        `npub` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `username` TEXT NOT NULL,
                        `nip05` TEXT NOT NULL,
                        `pictureUrl` TEXT NOT NULL,
                        `bannerUrl` TEXT NOT NULL,
                        `about` TEXT NOT NULL,
                        `website` TEXT NOT NULL,
                        `lud16` TEXT NOT NULL,
                        `eventCreatedAtSec` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`pubkeyHex`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `vault_accounts` ADD COLUMN `signerType` TEXT NOT NULL DEFAULT 'LOCAL_KEYSTORE'")
                db.execSQL("ALTER TABLE `vault_accounts` ADD COLUMN `signerPackage` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `vault_accounts` ADD COLUMN `bunkerUri` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `vault_accounts` ADD COLUMN `bunkerClientPubkeyHex` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `installed_applets` ADD COLUMN `latestEventId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `installed_applets` ADD COLUMN `cachedComplete` INTEGER NOT NULL DEFAULT 0")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `relay_health` (
                        `url` TEXT NOT NULL,
                        `supportedNipsCsv` TEXT NOT NULL,
                        `softwareInfo` TEXT NOT NULL,
                        `authRequired` INTEGER NOT NULL,
                        `paymentRequired` INTEGER NOT NULL,
                        `maxMessageBytes` INTEGER NOT NULL,
                        `avgLatencyMs` INTEGER NOT NULL,
                        `successCount` INTEGER NOT NULL,
                        `failureCount` INTEGER NOT NULL,
                        `consecutiveFailures` INTEGER NOT NULL,
                        `verifiedPublishCount` INTEGER NOT NULL,
                        `lastSuccessAt` INTEGER NOT NULL,
                        `lastFailureAt` INTEGER NOT NULL,
                        `lastErrorReason` TEXT NOT NULL,
                        `lastProbedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`url`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `configured_blossom_servers` (
                        `serverUrl` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `isBackupMirror` INTEGER NOT NULL,
                        `addedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`serverUrl`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `blossom_server_health` (
                        `serverUrl` TEXT NOT NULL,
                        `requiresAuth` INTEGER NOT NULL,
                        `maxUploadBytes` INTEGER NOT NULL,
                        `supportsMirror` INTEGER NOT NULL,
                        `avgLatencyMs` INTEGER NOT NULL,
                        `uploadSuccessCount` INTEGER NOT NULL,
                        `uploadFailureCount` INTEGER NOT NULL,
                        `verifiedRetrievalCount` INTEGER NOT NULL,
                        `hashMismatchCount` INTEGER NOT NULL,
                        `consecutiveFailures` INTEGER NOT NULL,
                        `lastSuccessAt` INTEGER NOT NULL,
                        `lastFailureAt` INTEGER NOT NULL,
                        `lastStatusMessage` TEXT NOT NULL,
                        `lastProbedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`serverUrl`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `nsite_versions` (
                        `eventId` TEXT NOT NULL,
                        `coordinate` TEXT NOT NULL,
                        `kind` INTEGER NOT NULL,
                        `authorPubkey` TEXT NOT NULL,
                        `authorNpub` TEXT NOT NULL,
                        `dTag` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `indexSha256` TEXT NOT NULL,
                        `fileCount` INTEGER NOT NULL,
                        `blossomServersCsv` TEXT NOT NULL,
                        `manifestPathsJson` TEXT NOT NULL,
                        `rawEventJson` TEXT NOT NULL,
                        `signatureVerified` INTEGER NOT NULL,
                        `createdAtSec` INTEGER NOT NULL,
                        `discoveredAt` INTEGER NOT NULL,
                        PRIMARY KEY(`eventId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `nsite_projects` (
                        `projectId` TEXT NOT NULL,
                        `ownerPubkeyHex` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `dTag` TEXT NOT NULL,
                        `targetKind` INTEGER NOT NULL,
                        `description` TEXT NOT NULL,
                        `entryPath` TEXT NOT NULL,
                        `sourceType` TEXT NOT NULL,
                        `forkSourceCoordinate` TEXT NOT NULL,
                        `forkSourceEventId` TEXT NOT NULL,
                        `forkSourcePubkey` TEXT NOT NULL,
                        `forkSourceNpub` TEXT NOT NULL,
                        `forkSourceDTag` TEXT NOT NULL,
                        `forkSourceVersionSec` INTEGER NOT NULL,
                        `incompleteImportWarning` TEXT NOT NULL,
                        `versionCounter` INTEGER NOT NULL,
                        `lastPublishedEventId` TEXT NOT NULL,
                        `lastPublishedUrl` TEXT NOT NULL,
                        `lastPublishedAt` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`projectId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `nsite_project_files` (
                        `projectId` TEXT NOT NULL,
                        `relativePath` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `isBinary` INTEGER NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `sha256` TEXT NOT NULL,
                        `textContent` TEXT NOT NULL,
                        `diskBlobPath` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`projectId`, `relativePath`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): ShellDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ShellDatabase::class.java,
                    "sovereign_shell.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
