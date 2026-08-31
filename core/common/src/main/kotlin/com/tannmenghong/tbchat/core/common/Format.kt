package com.tannmenghong.tbchat.core.common

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * Every number the user sees passes through here. Model sizes, speeds and
 * memory figures appear constantly in this app, so they get one consistent
 * treatment rather than a dozen call-site format strings.
 */
object Format {

    /** 2_497_280_256 -> "2.5 GB". Decimal units, matching what Hugging Face shows. */
    fun bytes(value: Long): String {
        if (value < 1000) return "$value B"
        val exp = (ln(value.toDouble()) / ln(1000.0)).toInt().coerceAtMost(4)
        val unit = "KMGTP"[exp - 1]
        val scaled = value / 1000.0.pow(exp.toDouble())
        return if (scaled >= 100) String.format(Locale.US, "%.0f %sB", scaled, unit)
        else String.format(Locale.US, "%.1f %sB", scaled, unit)
    }

    fun bytesPerSecond(value: Long): String = if (value <= 0) "--" else "${bytes(value)}/s"

    /** Durations are shown coarsely on purpose: "about 3 min" beats a false "3 m 07 s". */
    fun duration(seconds: Long): String = when {
        seconds < 0 -> "--"
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> {
            val m = seconds / 60
            val s = seconds % 60
            if (s == 0L) "${m}m" else "${m}m ${s}s"
        }

        else -> {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            if (m == 0L) "${h}h" else "${h}h ${m}m"
        }
    }

    fun tokensPerSecond(value: Double?): String =
        if (value == null || value <= 0) "--" else String.format(Locale.US, "%.1f tok/s", value)

    /** 4_022_000_000 -> "4.0B". The number people actually use to talk about a model. */
    fun parameters(count: Long): String {
        if (count <= 0) return "unknown"
        return when {
            count >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", count / 1e9)
            count >= 1_000_000 -> String.format(Locale.US, "%.0fM", count / 1e6)
            else -> "$count"
        }
    }

    fun contextLength(tokens: Int): String = when {
        tokens >= 1024 && tokens % 1024 == 0 -> "${tokens / 1024}K"
        else -> "$tokens"
    }

    fun relativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val delta = abs(now - timestamp) / 1000
        return when {
            delta < 60 -> "just now"
            delta < 3600 -> "${delta / 60} min ago"
            delta < 86_400 -> "${delta / 3600} h ago"
            delta < 604_800 -> "${delta / 86_400} d ago"
            else -> "${delta / 604_800} w ago"
        }
    }

    fun percent(fraction: Float): String = "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%"
}
