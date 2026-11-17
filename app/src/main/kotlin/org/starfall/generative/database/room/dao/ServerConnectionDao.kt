package org.starfall.generative.database.room.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import org.starfall.generative.database.room.entities.ServerConnectionEntity

@Dao
interface ServerConnectionDao {
    @Query("SELECT * FROM server_connections ORDER BY name COLLATE NOCASE, id")
    suspend fun all(): List<ServerConnectionEntity>
    @Query("SELECT * FROM server_connections WHERE id = :id")
    suspend fun get(id: String): ServerConnectionEntity?
    @Upsert suspend fun put(value: ServerConnectionEntity)
    @Query("DELETE FROM server_connections WHERE id = :id") suspend fun delete(id: String)
    @Query("UPDATE server_connections SET selected = (id = :id)") suspend fun select(id: String)
}
