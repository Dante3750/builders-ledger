package com.buildersledger.ui.components

import com.buildersledger.domain.TimeFormat
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

object Fmt {
    private val whenFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")

    /** "Wed 7 Oct, 14:30" in the device's time zone. */
    fun whenText(ms: Long): String = whenFormatter.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))

    fun amount(n: Long): String = NumberFormat.getIntegerInstance().format(n)

    /** 1_250_000 -> "1.3M" */
    fun compact(n: Long): String {
        val a = abs(n.toDouble())
        return when {
            a >= 1_000_000_000 -> String.format("%.1fB", n / 1_000_000_000.0)
            a >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
            a >= 10_000 -> String.format("%.0fK", n / 1_000.0)
            else -> amount(n)
        }
    }

    fun countdown(ms: Long): String = TimeFormat.duration(ms / 1000L)
}
