package com.example.bambuautoprint.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PrintTaskDao {
    @Query("SELECT * FROM print_tasks ORDER BY triggerAtMillis ASC")
    fun observeTasks(): Flow<List<PrintTask>>

    @Query("SELECT * FROM print_tasks WHERE enabled = 1")
    suspend fun enabledTasks(): List<PrintTask>

    @Query("SELECT * FROM print_tasks WHERE id = :id LIMIT 1")
    suspend fun taskById(id: Long): PrintTask?

    @Insert
    suspend fun insertTask(task: PrintTask): Long

    @Update
    suspend fun updateTask(task: PrintTask)

    @Query("DELETE FROM print_tasks WHERE id = :id")
    suspend fun deleteTaskById(id: Long)

    @Insert
    suspend fun insertLog(log: ExecutionLog)

    @Query("SELECT * FROM execution_logs ORDER BY createdAtMillis DESC LIMIT 500")
    suspend fun latestLogs(): List<ExecutionLog>

    @Query("SELECT * FROM execution_logs ORDER BY createdAtMillis DESC LIMIT 100")
    fun observeLogs(): Flow<List<ExecutionLog>>
}
