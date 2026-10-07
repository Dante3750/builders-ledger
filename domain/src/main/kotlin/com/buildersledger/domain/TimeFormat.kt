package com.buildersledger.domain

object TimeFormat {

    /** 93784 -> "1d 2h", 7500 -> "2h 05m", 750 -> "12m 30s", 45 -> "45s". */
    fun duration(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0L)
        val d = s / 86_400L
        val h = (s % 86_400L) / 3_600L
        val m = (s % 3_600L) / 60L
        val sec = s % 60L
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m.toString().padStart(2, '0')}m"
            m > 0 -> "${m}m ${sec.toString().padStart(2, '0')}s"
            else -> "${sec}s"
        }
    }

    /** 1_250_000 -> "1.25M", 45_000 -> "45k", 900 -> "900". Short form for tight screens and messages. */
    fun compact(value: Long): String {
        val v = kotlin.math.abs(value)
        val sign = if (value < 0) "-" else ""
        fun trim(x: Double): String {
            val t = (kotlin.math.round(x * 100.0) / 100.0).toString()
            return if (t.endsWith(".0")) t.dropLast(2) else t
        }
        return when {
            v >= 1_000_000_000L -> sign + trim(v / 1_000_000_000.0) + "B"
            v >= 1_000_000L -> sign + trim(v / 1_000_000.0) + "M"
            v >= 10_000L -> sign + trim(v / 1_000.0) + "k"
            else -> "$value"
        }
    }

    private val TOKEN = Regex("""(\d+)\s*([dhms])""", RegexOption.IGNORE_CASE)

    /**
     * Parses what players read off the game screen: "2d 3h", "14h 30m", "3d4h5m6s", "45m".
     * Returns total seconds, or null when the text is empty or contains anything unexpected.
     */
    fun parse(text: String): Long? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val matches = TOKEN.findAll(t).toList()
        if (matches.isEmpty()) return null
        val leftover = TOKEN.replace(t, "").replace(",", "").trim()
        if (leftover.isNotEmpty()) return null
        var total = 0L
        for (m in matches) {
            val n = m.groupValues[1].toLongOrNull() ?: return null
            val unit = when (m.groupValues[2].lowercase()) {
                "d" -> 86_400L
                "h" -> 3_600L
                "m" -> 60L
                else -> 1L
            }
            total += n * unit
        }
        return total
    }
}
