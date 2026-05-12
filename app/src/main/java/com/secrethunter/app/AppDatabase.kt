package com.secrethunter.app

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface SecretDao {
    @Query(
        """
        SELECT * FROM secrets ORDER BY
            CASE severity
                WHEN 'CRITICAL' THEN 1
                WHEN 'HIGH' THEN 2
                WHEN 'MEDIUM' THEN 3
                WHEN 'LOW' THEN 4
                ELSE 5
            END ASC,
            foundAtMillis DESC
        """,
    )
    fun observeAllSorted(): Flow<List<Secret>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<Secret>)

    @Query("DELETE FROM secrets")
    suspend fun deleteAll()
}

@Database(entities = [Secret::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun secretDao(): SecretDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "secrethunter.db",
                ).build().also { INSTANCE = it }
            }
        }
    }
}
