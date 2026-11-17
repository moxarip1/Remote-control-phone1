package com.example.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PairedServerDao {
    @Query("SELECT * FROM paired_servers ORDER BY lastConnectedEpochMs DESC")
    fun observeAll(): Flow<List<PairedServerEntity>>

    @Query("SELECT * FROM paired_servers ORDER BY lastConnectedEpochMs DESC")
    suspend fun getAll(): List<PairedServerEntity>

    @Query("SELECT * FROM paired_servers WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getById(deviceId: String): PairedServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PairedServerEntity)

    @Query("UPDATE paired_servers SET deviceName = :newName WHERE deviceId = :deviceId")
    suspend fun rename(deviceId: String, newName: String)

    @Query(
        "UPDATE paired_servers SET isOnline = :online, lastRoute = :route, " +
            "lastConnectedEpochMs = :lastConnectedMs, usedBytes = :usedBytes, " +
            "totalBytes = :totalBytes, fileCount = :fileCount WHERE deviceId = :deviceId"
    )
    suspend fun updateStatus(
        deviceId: String,
        online: Boolean,
        route: String,
        lastConnectedMs: Long,
        usedBytes: Long,
        totalBytes: Long,
        fileCount: Int
    )

    @Query(
        "UPDATE paired_servers SET encryptedAccessToken = :accessToken, " +
            "encryptedRefreshToken = :refreshToken, encryptedSessionSecret = :sessionSecret, " +
            "tokenExpiresAtEpochMs = :expiresAtMs WHERE deviceId = :deviceId"
    )
    suspend fun updateTokens(
        deviceId: String,
        accessToken: String,
        refreshToken: String,
        sessionSecret: String,
        expiresAtMs: Long
    )

    @Query("DELETE FROM paired_servers WHERE deviceId = :deviceId")
    suspend fun deleteById(deviceId: String)
}

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TransferEntity?

    @Query("SELECT * FROM transfers WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED', 'VERIFYING')")
    suspend fun getPendingOrActive(): List<TransferEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TransferEntity)

    @Query(
        "UPDATE transfers SET bytesTransferred = :bytes, speedBytesPerSec = :speed, " +
            "etaSeconds = :eta, status = :status, errorMessage = :errorMessage, " +
            "updatedAtEpochMs = :updatedAt WHERE id = :id"
    )
    suspend fun updateProgress(
        id: String,
        bytes: Long,
        speed: Long,
        eta: Long,
        status: String,
        errorMessage: String?,
        updatedAt: Long
    )

    @Query("DELETE FROM transfers WHERE status IN ('COMPLETED', 'CANCELLED', 'FAILED')")
    suspend fun clearHistory()

    @Query("DELETE FROM transfers WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface RecentFileDao {
    @Query("SELECT * FROM recent_files WHERE deviceId = :deviceId ORDER BY accessedAtEpochMs DESC LIMIT 15")
    fun observeRecentForDevice(deviceId: String): Flow<List<RecentFileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RecentFileEntity)

    @Query("DELETE FROM recent_files WHERE deviceId = :deviceId")
    suspend fun clearForDevice(deviceId: String)
}

@Dao
interface ServerActivityDao {
    @Query("SELECT * FROM server_activity ORDER BY timestampEpochMs DESC LIMIT 50")
    fun observeRecent(): Flow<List<ServerActivityEntity>>

    @Query("SELECT COUNT(*) FROM server_activity WHERE eventType = 'TRANSFER' AND timestampEpochMs >= :sinceMs")
    fun observeTodayTransferCount(sinceMs: Long): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ServerActivityEntity)

    @Query("DELETE FROM server_activity")
    suspend fun clear()
}
