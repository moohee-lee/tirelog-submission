package com.tirelog.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.room.Room
import com.tirelog.core.engine.NewSetInput
import com.tirelog.core.engine.TireLogEngine
import com.tirelog.core.model.TireSetType
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test

class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TirelogDatabase::class.java,
    )

    @Test fun allFourSeasonNamesRoundTripAndLegacyAllSeasonIsPreserved() = runBlocking {
        val name = "tirelog-four-seasons"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, TirelogDatabase::class.java, name).build()
        val store = RoomTireLogStore(db)
        for (type in TireSetType.entries) store.saveSet(com.tirelog.core.model.TireSet(type = type,
            size = null, dot = null, createdAtMillis = 0))
        db.close()
        val reopened = Room.databaseBuilder(context, TirelogDatabase::class.java, name).build()
        try { assertEquals(TireSetType.entries.toSet(), RoomTireLogStore(reopened).sets().map { it.type }.toSet()) }
        finally { reopened.close(); context.deleteDatabase(name) }
    }

    @Test fun versionSixPreservesLegacyEventsAndRotationSnapshotsAcrossReopen() = runBlocking {
        val name = "tirelog-v6-history"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(name)
        helper.createDatabase(name, 5).use {
            it.execSQL("INSERT INTO event VALUES (1,'ROTATION',100,12000,7,'8,9,10,11','앞뒤 교환 · A 앞왼쪽→뒤왼쪽')")
            it.execSQL("INSERT INTO event VALUES (2,'TIRE_REPLACEMENT',200,NULL,7,'12','앞왼쪽 타이어 A → E')")
        }
        helper.runMigrationsAndValidate(name, 6, true, TirelogDatabase.MIGRATION_5_6).close()
        val db = Room.databaseBuilder(context, TirelogDatabase::class.java, name).build()
        val store = RoomTireLogStore(db)
        val old = store.events().first()
        assertEquals("앞뒤 교환 · A 앞왼쪽→뒤왼쪽", old.detail)
        assertEquals(listOf(8L, 9L, 10L, 11L), old.tireIds)
        assertEquals(null, old.rotation)
        assertTrue(old.previousTireIds.isEmpty())
        val pattern = com.tirelog.core.model.RotationPattern.REARWARD_CROSS
        val record = com.tirelog.core.model.RotationRecord(pattern, com.tirelog.core.model.WheelPosition.entries.mapIndexed { index, from ->
            com.tirelog.core.model.TireMove(20L + index, "타이어 \"$index\"", from, pattern.destinationOf(from))
        })
        store.addEvent(com.tirelog.core.model.TireEvent(type = com.tirelog.core.model.EventType.ROTATION,
            atMillis = 300, odoKm = null, setId = 7, tireIds = record.moves.map { it.tireId }, rotation = record))
        store.addEvent(com.tirelog.core.model.TireEvent(type = com.tirelog.core.model.EventType.TIRE_REPLACEMENT,
            atMillis = 400, odoKm = 15000.0, setId = 7, tireIds = listOf(30), previousTireIds = listOf(20)))
        db.close()
        val reopened = Room.databaseBuilder(context, TirelogDatabase::class.java, name).build()
        try {
            val persisted = RoomTireLogStore(reopened).events()
            assertEquals(4, persisted.size)
            assertEquals(old, persisted.first())
            assertEquals(record, persisted[2].rotation)
            assertEquals(listOf(20L), persisted.last().previousTireIds)
            assertEquals(listOf(30L), persisted.last().tireIds)
        } finally { reopened.close(); context.deleteDatabase(name) }
    }

    @Test fun engineHistoryAndPhysicalTiresSurviveDatabaseReopen() = runBlocking {
        val name = "tirelog-history-roundtrip"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(name)
        val zone = ZoneId.of("Asia/Seoul")
        val now = LocalDate.of(2026, 11, 6).atStartOfDay(zone).toInstant().toEpochMilli()
        val db = Room.databaseBuilder(context, TirelogDatabase::class.java, name).build()
        val engine = TireLogEngine(RoomTireLogStore(db), zone)
        engine.completeOnboarding(TireLogEngine.OnboardingInput(null, TireSetType.SUMMER, null, null), now, 10000.0)
        engine.recordColdReading(com.tirelog.core.model.WheelPosition.entries.associateWith { 250.0 }, 10.0, 10000.0, now + 1)
        engine.checkIn(com.tirelog.core.engine.CheckInRequest(rotation = com.tirelog.core.model.RotationPattern.FORWARD_CROSS), now + 2, 11000.0)
        val history = engine.rotationHistory()
        val oldTire = engine.mountedTires().getValue(com.tirelog.core.model.WheelPosition.FRONT_LEFT)
        engine.checkIn(com.tirelog.core.engine.CheckInRequest(replacements = mapOf(com.tirelog.core.model.WheelPosition.FRONT_LEFT to null)), now + 3, 11500.0)
        db.close()
        val reopened = Room.databaseBuilder(context, TirelogDatabase::class.java, name).build()
        try {
            val store = RoomTireLogStore(reopened)
            val restored = TireLogEngine(store, zone).rotationHistory()
            assertEquals(history.entries.single().moves, restored.entries.single().moves)
            assertEquals(false, restored.entries.single().sameTires)
            assertTrue(store.measurementsSince(now).any { it.tireId == oldTire.id })
            assertTrue(store.tires().any { it.id == oldTire.id && !it.active })
            assertEquals(listOf(oldTire.id), store.events().last().previousTireIds)
        } finally { reopened.close(); context.deleteDatabase(name) }
    }

    @Test fun versionFivePreservesIndividualSizeAndMissingMileageAcrossReopen() = runBlocking {
        val name = "tirelog-v5-migration"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(name)
        helper.createDatabase(name, 4).use {
            it.execSQL("INSERT INTO tire_set (id,type,size,dot,createdAt,retired,initialKm,kmAtLastRotation,recFrontKpa,recRearKpa) VALUES (7,'SUMMER','235/55R19',NULL,100,0,0,0,NULL,NULL)")
            it.execSQL("INSERT INTO tire (id,setId,label,position,dot,active,profile_type,profile_initialKm,profile_initialKmKnown,profile_inspectionKm,profile_inspectionMonths,profile_lifeGuideId) VALUES (8,7,'A','FRONT_LEFT',NULL,1,'SUMMER',0,1,40000,60,'app')")
            it.execSQL("INSERT INTO mount VALUES (1,7,10000,100,NULL,200)")
        }
        helper.runMigrationsAndValidate(name, 6, true, TirelogDatabase.MIGRATION_4_5, TirelogDatabase.MIGRATION_5_6).close()
        val db = Room.databaseBuilder(context, TirelogDatabase::class.java, name).build()
        val store = RoomTireLogStore(db)
        assertEquals("235/55R19", store.tires().single().profile!!.size)
        assertEquals(200L, store.mounts().single().odoGapAtMillis)
        store.saveTire(store.tires().single().copy(profile = store.tires().single().profile!!.copy(size = "245/45R19")))
        store.saveSettings(com.tirelog.core.model.AppSettings(pressurePositions = setOf(com.tirelog.core.model.WheelPosition.FRONT_LEFT),
            vehicleGuideId = "hyundai_gn7_2026_kr", vehicleTireSize = "245/45R19"))
        db.close()
        val reopened = Room.databaseBuilder(context, TirelogDatabase::class.java, name).build()
        try {
            val persisted = RoomTireLogStore(reopened)
            assertEquals("245/45R19", persisted.tires().single().profile!!.size)
            assertEquals(200L, persisted.mounts().single().odoGapAtMillis)
            assertEquals("hyundai_gn7_2026_kr", persisted.settings().vehicleGuideId)
            assertEquals(setOf(com.tirelog.core.model.WheelPosition.FRONT_LEFT), persisted.settings().pressurePositions)
        } finally { reopened.close(); context.deleteDatabase(name) }
    }

    @Test
    fun registrationMigrationKeepsLegacyTiresAndRoundTripsIndividualProfiles() = runBlocking {
        val name = "tirelog-registration-migration"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(name)
        helper.createDatabase(name, 3).use {
            it.execSQL("INSERT INTO tire_set (id,type,size,dot,createdAt,retired,initialKm,kmAtLastRotation,recFrontKpa,recRearKpa) VALUES (7,'SUMMER',NULL,'0123',100,0,37000,30000,NULL,NULL)")
            it.execSQL("INSERT INTO tire VALUES (8,7,'A','FRONT_LEFT','0123',1)")
        }
        helper.runMigrationsAndValidate(name, 6, true, TirelogDatabase.MIGRATION_3_4, TirelogDatabase.MIGRATION_4_5, TirelogDatabase.MIGRATION_5_6).close()
        val db = Room.databaseBuilder(context, TirelogDatabase::class.java, name).build()
        val store = RoomTireLogStore(db)
        assertTrue(store.sets().single().initialKmKnown)
        assertEquals(null, store.tires().single().profile)
        store.saveTire(store.tires().single().copy(profile = com.tirelog.core.model.TireProfile(
            manufacturer = "개별 제조사", tireModel = "개별 모델", initialKmKnown = false, inspectionKm = 32000,
        ), active = false, removedAtMillis = 200, removedOdoKm = 10000.0))
        db.close()
        val reopened = Room.databaseBuilder(context, TirelogDatabase::class.java, name).build()
        try {
            val tire = RoomTireLogStore(reopened).tires().single()
            assertEquals("개별 모델", tire.profile!!.tireModel)
            assertEquals(false, tire.profile!!.initialKmKnown)
            assertEquals(32000, tire.profile!!.inspectionKm)
            assertEquals(200L, tire.removedAtMillis)
            assertEquals(10000.0, tire.removedOdoKm)
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun lifeMigrationPreservesRecordsWithoutInventingFirstUse() {
        val name = "tirelog-life-migration"
        helper.createDatabase(name, 2).use {
            it.execSQL("INSERT INTO tire_set VALUES (7, 'SUMMER', NULL, '0123', 100, 0, 37000.0, 30000.0, NULL, NULL)")
            it.execSQL("INSERT INTO mount VALUES (1, 7, 10000.0, 100, NULL, NULL)")
        }
        helper.runMigrationsAndValidate(name, 3, true, TirelogDatabase.MIGRATION_2_3).use {
            it.query("SELECT initialKm, kmAtLastRotation, firstUsedEpochDay, newUntilMounted, inspectionKm, inspectionMonths, lifeGuideId, tireModel FROM tire_set WHERE id = 7").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(37000.0, c.getDouble(0), 0.0)
                assertEquals(30000.0, c.getDouble(1), 0.0)
                assertTrue(c.isNull(2))
                assertEquals(0, c.getInt(3))
                assertEquals(40000, c.getInt(4))
                assertEquals(60, c.getInt(5))
                assertEquals("app", c.getString(6))
                assertTrue(c.isNull(7))
            }
        }
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name)
    }

    @Test
    fun lifeSettingsSurviveDatabaseReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "tirelog-life-roundtrip"
        context.deleteDatabase(name)
        val zone = ZoneId.of("Asia/Seoul")
        val date = LocalDate.of(2026, 10, 31)
        val now = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val db = Room.databaseBuilder(context, TirelogDatabase::class.java, name).build()
        val engine = TireLogEngine(RoomTireLogStore(db), zone)
        val id = engine.addSet(NewSetInput(TireSetType.SUMMER, null, null, initialKm = 32000.0,
            firstUsedEpochDay = date.minusYears(3).toEpochDay(), lifeGuideId = "nexen_ev", inspectionKm = 35000, tireModel = "사용자 제품"), now)
        db.close()
        val reopened = Room.databaseBuilder(context, TirelogDatabase::class.java, name).build()
        try {
            val set = RoomTireLogStore(reopened).sets().single { it.id == id }
            assertEquals(35000, set.inspectionKm)
            assertEquals(48, set.inspectionMonths)
            assertEquals("nexen_ev", set.lifeGuideId)
            assertEquals("사용자 제품", set.tireModel)
            assertEquals(date.minusYears(3).toEpochDay(), set.firstUsedEpochDay)
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun migrationPreservesRecordedMileageAndAllowsUnknownStart() {
        val name = "tirelog-review-migration"
        helper.createDatabase(name, 1).use {
            it.execSQL("INSERT INTO mount VALUES (1, 7, 10000.0, 100, 10500.0, 200)")
        }
        helper.runMigrationsAndValidate(name, 2, true, TirelogDatabase.MIGRATION_1_2).use {
            it.query("SELECT mountOdo, unmountOdo FROM mount WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals(10000.0, cursor.getDouble(0), 0.0)
                assertEquals(10500.0, cursor.getDouble(1), 0.0)
            }
            it.execSQL("INSERT INTO mount VALUES (2, 8, NULL, 300, NULL, NULL)")
        }
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name)
    }
}
