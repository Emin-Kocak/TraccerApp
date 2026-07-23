package com.example.traccerapp.data

import android.content.Context
import androidx.room.Database
import com.example.traccerapp.BuildConfig
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v1 → v2: telefonun kilit açma (unlock) olaylarını saymak için yeni tablo. Mevcut veriyi korur. */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `unlock_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestampMs` INTEGER NOT NULL)"
        )
    }
}

/** v2 → v3: telefonu ele alıp bırakma arasındaki kullanım oturumlarını (pickup→hangup) saklamak için yeni tablo. */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `phone_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL)"
        )
    }
}

/** v3 → v4: uygulama başına "girişte süre sor" (oturum bazlı izin) bayrağı. Mevcut veriyi korur. */
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `app_limits` ADD COLUMN `isSessionPromptEnabled` INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(entities = [UsageLog::class, AppLimit::class, UnlockEvent::class, PhoneSession::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appUsageDao(): AppUsageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val builder = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "traccer_database_v2"  // Yeni isim = temiz başlangıç
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                // Destructive fallback SADECE debug'da: release'te migration unutulursa
                // kullanıcı verisini sessizce silmek yerine yüksek sesle çöksün (madde 25).
                if (BuildConfig.DEBUG) {
                    builder.fallbackToDestructiveMigration()
                }
                val instance = builder.build()
                INSTANCE = instance
                instance
            }
        }
    }
}