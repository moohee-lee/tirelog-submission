package com.tirelog.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun interface TimeSource {
    fun now(): Long

    companion object {
        val System = TimeSource { java.lang.System.currentTimeMillis() }
    }
}

object TimeLabels {
    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    private val md = DateTimeFormatter.ofPattern("M/d")

    fun epochDay(millis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

    fun date(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /** "오늘 07:52", "어제 07:52", "3일 전 07:55", "12/7 07:52" */
    fun relative(millis: Long, nowMillis: Long, zone: ZoneId): String {
        val time = Instant.ofEpochMilli(millis).atZone(zone)
        val days = epochDay(nowMillis, zone) - time.toLocalDate().toEpochDay()
        val clock = time.format(hm)
        return when {
            days <= 0L -> "오늘 $clock"
            days == 1L -> "어제 $clock"
            days in 2L..6L -> "${days}일 전 $clock"
            else -> "${time.format(md)} $clock"
        }
    }

    fun monthDay(date: LocalDate): String = date.format(md)

    fun monthDay(millis: Long, zone: ZoneId): String = Instant.ofEpochMilli(millis).atZone(zone).format(md)
}
