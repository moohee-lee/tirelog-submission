package com.tirelog.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SettingsEntity::class, DriveStateEntity::class, TireSetEntity::class, TireEntity::class,
        MountEntity::class, EventEntity::class, MeasurementEntity::class, BaselineEntity::class,
        TrendStateEntity::class, DayResultEntity::class, TireDayStatusEntity::class, AlertStateEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class TirelogDatabase : RoomDatabase() {
    abstract fun dao(): TirelogDao

    companion object {
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE event ADD COLUMN rotationJson TEXT")
                db.execSQL("ALTER TABLE event ADD COLUMN previousTireIds TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tire ADD COLUMN profile_size TEXT")
                // 기존 개별 정보에서도 지금까지 표시하던 세트 규격을 보존한다.
                db.execSQL("UPDATE tire SET profile_size = (SELECT size FROM tire_set WHERE tire_set.id = tire.setId) WHERE profile_type IS NOT NULL")
                db.execSQL("ALTER TABLE mount ADD COLUMN odoGapAtMillis INTEGER")
                db.execSQL("ALTER TABLE settings ADD COLUMN pressurePositions TEXT")
                db.execSQL("ALTER TABLE settings ADD COLUMN vehicleGuideId TEXT")
                db.execSQL("ALTER TABLE settings ADD COLUMN vehicleTireSize TEXT")
                db.execSQL("UPDATE mount SET odoGapAtMillis = COALESCE(unmountAt, mountAt) WHERE mountOdo IS NULL OR (unmountAt IS NOT NULL AND unmountOdo IS NULL) OR unmountOdo < mountOdo")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tire_set ADD COLUMN manufacturer TEXT")
                db.execSQL("ALTER TABLE tire_set ADD COLUMN initialKmKnown INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE tire ADD COLUMN retired INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tire ADD COLUMN removedAtMillis INTEGER")
                db.execSQL("ALTER TABLE tire ADD COLUMN removedOdoKm REAL")
                db.execSQL("ALTER TABLE tire ADD COLUMN colorIndex INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE tire SET colorIndex = CASE position WHEN 'FRONT_RIGHT' THEN 1 WHEN 'REAR_LEFT' THEN 2 WHEN 'REAR_RIGHT' THEN 3 ELSE 0 END")
                // Nullable embedded profile: legacy tires continue to inherit their set's information.
                listOf("manufacturer TEXT", "tireModel TEXT", "type TEXT", "initialKm REAL",
                    "initialKmKnown INTEGER", "firstUsedEpochDay INTEGER", "inspectionKm INTEGER",
                    "inspectionMonths INTEGER", "lifeGuideId TEXT").forEach {
                    db.execSQL("ALTER TABLE tire ADD COLUMN profile_$it")
                }
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 기존 세트의 실제 첫 사용일을 앱 등록일로 추정하지 않는다.
                db.execSQL("ALTER TABLE tire_set ADD COLUMN firstUsedEpochDay INTEGER")
                db.execSQL("ALTER TABLE tire_set ADD COLUMN newUntilMounted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tire_set ADD COLUMN inspectionKm INTEGER NOT NULL DEFAULT 40000")
                db.execSQL("ALTER TABLE tire_set ADD COLUMN inspectionMonths INTEGER NOT NULL DEFAULT 60")
                db.execSQL("ALTER TABLE tire_set ADD COLUMN lifeGuideId TEXT NOT NULL DEFAULT 'app'")
                db.execSQL("ALTER TABLE tire_set ADD COLUMN tireModel TEXT")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE mount_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        setId INTEGER NOT NULL, mountOdo REAL, mountAt INTEGER NOT NULL,
                        unmountOdo REAL, unmountAt INTEGER
                    )""",
                )
                db.execSQL("INSERT INTO mount_new SELECT id, setId, mountOdo, mountAt, unmountOdo, unmountAt FROM mount")
                db.execSQL("DROP TABLE mount")
                db.execSQL("ALTER TABLE mount_new RENAME TO mount")
            }
        }

        fun open(context: Context, demo: Boolean): TirelogDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                TirelogDatabase::class.java,
                if (demo) "tirelog-demo.db" else "tirelog.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build()
    }
}
