package com.caizhixing.feedback.util

import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object AppTime {
    val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun now(clock: Clock): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), ZONE)

    fun format(dt: LocalDateTime): String = FORMATTER.format(dt)
}
