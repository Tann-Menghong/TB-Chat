package com.tannmenghong.tbchat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.tannmenghong.tbchat.core.designsystem.TbChatTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Registered unconditionally (contracts must be registered before the
    // activity is STARTED); the result is ignored because download progress is a
    // convenience, not a requirement -- the transfer runs either way.
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* ignored */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // On Android 13+ POST_NOTIFICATIONS is a runtime permission. Declaring it
        // in the manifest is not enough: without the grant, the download and
        // inference foreground-service notifications are silently suppressed, so
        // the user sees no sign a download is running. Asking once fixes that.
        maybeRequestNotificationPermission()

        // A close is never silent: if the process died last time, say so and
        // point at the saved report rather than pretending nothing happened.
        CrashReporter.consumeLastCrashSummary(this)?.let { summary ->
            Toast.makeText(
                this,
                "TB-Chat closed unexpectedly last time. A report was saved (Settings ▸ Diagnostics).",
                Toast.LENGTH_LONG
            ).show()
            android.util.Log.w("MainActivity", "recovered from previous crash: $summary")
        }

        setContent {
            TbChatTheme {
                TbChatApp()
            }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
