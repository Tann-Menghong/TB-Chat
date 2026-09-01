package com.tannmenghong.tbchat

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * A last line of defence for the UI process.
 *
 * The `:inference` process is already isolated so a native crash there arrives
 * as a recoverable service disconnect. But the UI process can still die -- a
 * Compose exception, a bad cast, an OOM while decoding a bitmap -- and when it
 * does the app "just closes" with nothing written down. That is exactly the
 * symptom users report as "the app auto-closes".
 *
 * This handler does three things and deliberately no more:
 *
 *   1. Writes the stack trace to a file under the app's private storage, so the
 *      failure is inspectable from the Settings diagnostics screen instead of
 *      being lost. Crashes are never hidden.
 *   2. Records a short summary the next launch can surface to the user, so the
 *      app acknowledges what happened rather than pretending it did not.
 *   3. Counts crashes in a short window. Three within a minute is a crash loop --
 *      usually a model or setting that kills the process on every startup -- and
 *      that fact is exposed so the app can come up in a reduced "safe mode" that
 *      skips the risky auto-load instead of dying again.
 *
 * It never swallows the crash: after recording, it delegates to the previous
 * default handler so the process still terminates as the OS expects.
 */
object CrashReporter {

    private const val PREFS = "tbchat_crash"
    private const val KEY_LAST_SUMMARY = "last_summary"
    private const val KEY_CRASH_TIMES = "crash_times"
    private const val CRASH_LOG_DIR = "crash"

    /** Three crashes inside this window on startup is treated as a crash loop. */
    private const val LOOP_WINDOW_MS = 60_000L
    private const val LOOP_THRESHOLD = 3

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true

        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Everything here is best-effort and must never throw, or we would
            // replace one crash with a more confusing one.
            runCatching { persist(appContext, thread, throwable) }
            runCatching { recordCrashTime(appContext) }
            // Do not hide the crash: let the platform handler run so the process
            // dies as normal and, in debug, the system dialog still appears.
            previous?.uncaughtException(thread, throwable)
                ?: run {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    Runtime.getRuntime().exit(2)
                }
        }
    }

    /**
     * Returns a one-line summary of the most recent crash exactly once, clearing
     * it so it is shown to the user a single time rather than on every launch.
     */
    fun consumeLastCrashSummary(context: Context): String? {
        val prefs = prefs(context)
        val summary = prefs.getString(KEY_LAST_SUMMARY, null) ?: return null
        prefs.edit().remove(KEY_LAST_SUMMARY).apply()
        return summary
    }

    /**
     * True when the process has crashed [LOOP_THRESHOLD] times inside
     * [LOOP_WINDOW_MS]. Callers should bring the app up in a reduced mode --
     * skipping model auto-load and other work that could be the cause.
     */
    fun isInCrashLoop(context: Context): Boolean {
        val now = System.currentTimeMillis()
        val recent = crashTimes(context).filter { now - it < LOOP_WINDOW_MS }
        return recent.size >= LOOP_THRESHOLD
    }

    /** Most recent crash report files, newest first, for the diagnostics screen. */
    fun crashReports(context: Context): List<File> =
        crashDir(context).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    private fun persist(context: Context, thread: Thread, throwable: Throwable) {
        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val header = buildString {
            append("TB-Chat crash report\n")
            append("time: ").append(System.currentTimeMillis()).append('\n')
            append("thread: ").append(thread.name).append('\n')
            append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("android: API ").append(Build.VERSION.SDK_INT).append('\n')
            append("abi: ").append(Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown").append("\n\n")
        }

        val dir = crashDir(context)
        // Keep only the most recent handful; this is a diagnostic, not an archive.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(9)?.forEach { it.delete() }

        File(dir, "crash-${System.currentTimeMillis()}.txt").writeText(header + stack)

        val summary = "${throwable.javaClass.simpleName}: ${throwable.message ?: "no message"}"
            .take(200)
        prefs(context).edit().putString(KEY_LAST_SUMMARY, summary).apply()
        Log.e("CrashReporter", "uncaught exception recorded", throwable)
    }

    private fun recordCrashTime(context: Context) {
        val now = System.currentTimeMillis()
        val kept = (crashTimes(context) + now)
            .filter { now - it < LOOP_WINDOW_MS }
            .takeLast(LOOP_THRESHOLD + 2)
        prefs(context).edit()
            .putString(KEY_CRASH_TIMES, kept.joinToString(","))
            .apply()
    }

    private fun crashTimes(context: Context): List<Long> =
        prefs(context).getString(KEY_CRASH_TIMES, null)
            ?.split(',')
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?: emptyList()

    private fun crashDir(context: Context): File =
        File(context.filesDir, CRASH_LOG_DIR).apply { mkdirs() }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
