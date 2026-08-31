package com.tannmenghong.tbchat

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tannmenghong.tbchat.feature.chat.ChatScreen
import com.tannmenghong.tbchat.feature.downloads.DownloadsScreen
import com.tannmenghong.tbchat.feature.home.HomeScreen
import com.tannmenghong.tbchat.feature.models.ModelsScreen
import com.tannmenghong.tbchat.feature.settings.SettingsScreen

private enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    HOME("home", "Home", Icons.Default.Home),
    CHAT("chat", "Chat", Icons.Default.Chat),
    MODELS("models", "Models", Icons.Default.Storage),
    DOWNLOADS("downloads", "Downloads", Icons.Default.Download),
    SETTINGS("settings", "Settings", Icons.Default.Settings)
}

@Composable
fun TbChatApp() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        // startsWith so the chat route keeps its tab selected
                        // when it carries a conversation id.
                        selected = currentRoute?.startsWith(destination.route) == true,
                        onClick = { navController.navigateToTab(destination.route) },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.HOME.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Destination.HOME.route) {
                HomeScreen(
                    onOpenChat = { conversationId ->
                        navController.navigate(
                            if (conversationId == null) {
                                Destination.CHAT.route
                            } else {
                                "${Destination.CHAT.route}?id=$conversationId"
                            }
                        )
                    },
                    onOpenModels = { navController.navigateToTab(Destination.MODELS.route) }
                )
            }

            composable(Destination.CHAT.route) {
                ChatScreen(
                    conversationId = null,
                    onBrowseModels = { navController.navigateToTab(Destination.MODELS.route) }
                )
            }

            composable("${Destination.CHAT.route}?id={id}") { entry ->
                ChatScreen(
                    conversationId = entry.arguments?.getString("id"),
                    onBrowseModels = { navController.navigateToTab(Destination.MODELS.route) }
                )
            }

            composable(Destination.MODELS.route) { ModelsScreen() }
            composable(Destination.DOWNLOADS.route) { DownloadsScreen() }
            composable(Destination.SETTINGS.route) { SettingsScreen() }
        }
    }
}

/**
 * Tab navigation that does not grow the back stack. Without this, tapping
 * between tabs five times means five presses of Back to leave the app.
 */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
