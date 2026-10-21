package com.sonnyapp.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 日期工具：DLNA 的 date 字段（ISO）与相册的 epoch 毫秒共用一套分组/展示逻辑。 */
object Dates {

    /** "2026-03-27" / "2026-03-27T12:34:56" -> "2026-03-27"；空返回哨兵值。 */
    fun dayKey(isoDate: String): String {
        val d = isoDate.substringBefore('T')
        return if (d.length >= 10) d else "0000-00-00"
    }

    /** epoch 毫秒 -> "2026-03-27"（本地时区）。 */
    fun dayKey(millis: Long): String {
        if (millis <= 0) return "0000-00-00"
        return DAY_FMT.format(Date(millis))
    }

    /** "2026-03-27" -> "2026年3月27日"。 */
    fun prettyDay(day: String): String {
        if (day == "0000-00-00") return "未知日期"
        val p = day.split('-')
        if (p.size != 3) return day
        val m = p[1].toIntOrNull() ?: return day
        val d = p[2].toIntOrNull() ?: return day
        return "${p[0]}年${m}月${d}日"
    }

    private val DAY_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
}
