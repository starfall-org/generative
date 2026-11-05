package org.starfall.generative.database.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.starfall.generative.database.room.entities.OAuthTokenRecordEntity

@Dao
interface OAuthTokenDao {
    @Query("SELECT encryptedPayload FROM oauth_token_records WHERE namespace = :namespace AND recordId = :recordId")
    suspend fun get(namespace: String, recordId: String): String?

    @Query("SELECT * FROM oauth_token_records WHERE namespace = :namespace")
    suspend fun all(namespace: String): List<OAuthTokenRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(record: OAuthTokenRecordEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMigrated(record: OAuthTokenRecordEntity): Long

    @Query("DELETE FROM oauth_token_records WHERE namespace = :namespace AND recordId = :recordId")
    suspend fun remove(namespace: String, recordId: String)

    @Query("DELETE FROM oauth_token_records WHERE namespace = :namespace")
    suspend fun clear(namespace: String)
}
