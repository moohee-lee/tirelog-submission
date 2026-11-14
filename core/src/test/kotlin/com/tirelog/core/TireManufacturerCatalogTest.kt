package com.tirelog.core

import com.tirelog.core.catalog.TireLifeGuides
import com.tirelog.core.catalog.TireManufacturers
import kotlin.test.*
import org.junit.Test

class TireManufacturerCatalogTest {
    @Test fun searchesKoreanEnglishAliasesAndPartialNames() {
        assertEquals("hankook", TireManufacturers.search("한국").first().id)
        assertEquals("kumho", TireManufacturers.search("금 호").single().id)
        assertEquals("nexen", TireManufacturers.search("  NEXEN tire  ").single().id)
        assertEquals("michelin", TireManufacturers.search("ｍｉｃｈｅｌｉｎ").single().id)
        assertEquals("bfgoodrich", TireManufacturers.search("B.F. Goodrich").single().id)
        assertTrue(TireManufacturers.search("lin").any { it.id == "michelin" })
        assertEquals("kleber", TireManufacturers.search("Kléber").single().id)
    }

    @Test fun emptyQueryShowsTheCatalogAndUnknownNamesHaveNoMatch() {
        assertEquals(TireManufacturers.entries, TireManufacturers.search("   "))
        assertTrue(TireManufacturers.search("한빛 타이어").isEmpty())
        assertTrue(TireManufacturers.search("---").isEmpty())
        assertNull(TireManufacturers.find(""))
        assertNull(TireManufacturers.find("한빛 타이어"))
        assertNull(TireManufacturers.find("미쉐"))
        assertEquals("michelin", TireManufacturers.find("  MICHELIN ")?.id)
    }

    @Test fun brandsKeepTheirOwnIdentityAndOnlyVerifiedLifeGuidesAreLinked() {
        assertEquals(listOf("nexen", "nexen_ev"), TireManufacturers.find("넥센타이어")?.lifeGuideIds)
        assertEquals(listOf("michelin"), TireManufacturers.find("미쉐린")?.lifeGuideIds)
        assertEquals(listOf("app"), TireManufacturers.find("BFGoodrich")?.lifeGuideIds)
        assertEquals(listOf("app"), TireManufacturers.find("Laufenn")?.lifeGuideIds)
        assertEquals(listOf("app"), TireManufacturers.find("금호")?.lifeGuideIds)
    }

    @Test fun catalogEntriesCanBeUnambiguouslySelectedAndHaveSources() {
        val entries = TireManufacturers.entries
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        entries.forEach { entry ->
            assertEquals(entry.id, TireManufacturers.find(entry.name)?.id)
            assertEquals(entry.id, TireManufacturers.find(entry.englishName)?.id)
            assertTrue(entry.sourceUrl.startsWith("https://"))
            assertTrue(entry.lifeGuideIds.isNotEmpty())
            entry.lifeGuideIds.forEach { assertEquals(it, TireLifeGuides.find(it).id) }
        }
    }
}
