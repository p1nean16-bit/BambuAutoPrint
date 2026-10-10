package com.example.bambuautoprint.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PrintTask::class, ExecutionLog::class], version = 2, exportSchema = false)
abstract class PrintDatabase : RoomDatabase() {
    abstract fun taskDao(): PrintTaskDao
}

object AppGraph {
    // 升级保留旧计划和日志，不使用清空数据库的破坏式迁移。
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE print_tasks ADD COLUMN adaptiveSpeedEnabled INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE print_tasks ADD COLUMN slowFromLayer INTEGER NOT NULL DEFAULT 151")
            db.execSQL("ALTER TABLE print_tasks ADD COLUMN beforeSpeedMode TEXT NOT NULL DEFAULT '标准'")
            db.execSQL("ALTER TABLE print_tasks ADD COLUMN afterSpeedMode TEXT NOT NULL DEFAULT '静音'")
            db.execSQL("ALTER TABLE print_tasks ADD COLUMN speedTimeoutMinutes INTEGER NOT NULL DEFAULT 480")
        }
    }
    @Volatile private var instance: PrintDatabase? = null

    fun database(context: Context): PrintDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
            context.applicationContext,
            PrintDatabase::class.java,
            "bambu_print_scheduler.db"
        ).addMigrations(MIGRATION_1_2).build().also { instance = it }
    }
}
