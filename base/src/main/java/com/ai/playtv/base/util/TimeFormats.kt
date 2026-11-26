package com.ai.playtv.base.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 时间戳与时长分别处理，调用方明确时区和语言；不把格式错误变成空字符串。 */
object TimeFormats {
    fun timestamp(epochMillis: Long, pattern: String, zoneId: ZoneId, locale: Locale): String =
        DateTimeFormatter.ofPattern(pattern, locale).withZone(zoneId).format(Instant.ofEpochMilli(epochMillis))

    /** 非负时长，24h 以上小时继续累计；不是一天内的时刻。 */
    fun durationSeconds(seconds: Long): String {
        require(seconds >= 0)
        val hours = seconds / 3600
        val minutes = seconds / 60 % 60
        val remainder = seconds % 60
        return if (hours > 0) String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, remainder)
            else String.format(Locale.ROOT, "%02d:%02d", minutes, remainder)
    }
}
