package com.example.bambuautoprint

import com.example.bambuautoprint.automation.CatalogNodeParser
import com.example.bambuautoprint.data.PrintTask
import com.example.bambuautoprint.scheduling.TaskScheduler
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ImportAndScheduleTest {
    @Test fun printerNeedsDeviceEvidence() {
        assertNull(CatalogNodeParser.candidateTitle("PRINTER", listOf("我的收藏", "设置")))
        assertEquals("哈吉米工厂", CatalogNodeParser.candidateTitle("PRINTER", listOf("哈吉米工厂", "P1S", "在线")))
    }
    @Test fun navigationAndPrintButtonsAreNotModels() {
        assertNull(CatalogNodeParser.candidateTitle("MODEL", listOf("收藏", "打印", "确认打印", "123", "PLA")))
        assertEquals("17秒打印挑战", CatalogNodeParser.candidateTitle("MODEL", listOf("17秒打印挑战", "1.2k", "PLA")))
    }
    private fun time(value: String) = LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun task(mode: String) = PrintTask(name = "test", triggerAtMillis = time("2026-10-11T08:00:00"), repeatMode = mode,
        printerIdentifier = "P1S", modelName = "test", weekdaysMask = 1)
    @Test fun pastOneTimeTaskDoesNotReplay() { assertNull(TaskScheduler.nextOccurrence(task("ONCE"), time("2026-10-11T08:00:01"))) }
    @Test fun dailyAtTriggerMovesToTomorrow() { assertEquals(time("2026-10-12T08:00:00"), TaskScheduler.nextOccurrence(task("DAILY"), time("2026-10-11T08:00:00"))) }
    @Test fun selectedWeekdaySchedulesMonday() { assertEquals(time("2026-10-12T08:00:00"), TaskScheduler.nextOccurrence(task("CUSTOM"), time("2026-10-11T09:00:00"))) }
}
