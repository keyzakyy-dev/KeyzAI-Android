package com.keyzai.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.keyzai.app.AppContainer
import com.keyzai.app.ui.chat.ChatScreen
import com.keyzai.app.ui.home.HomeScreen
import com.keyzai.app.ui.login.LoginScreen
import com.keyzai.app.ui.settings.SettingsScreen

@Composable
fun AppNav(container: AppContainer) {
    val navController = rememberNavController()
    val startDest = if (container.session.isLoggedIn()) "home" else "login"

    // Sesi berakhir (401 dari API mana pun) -> kembali ke login, buang backstack
    LaunchedEffect(Unit) {
        container.authExpired.collect {
            navController.navigate("login") {
                popUpTo(navController.graph.startDestinationId) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    NavHost(navController = navController, startDestination = startDest) {
        composable("login") {
            LoginScreen(
                container = container,
                onLoggedIn = {
                    navController.navigate("home") {
                        popUpTo("login") { inclusive = true }
                    }
                },
            )
        }
        composable("home") {
            HomeScreen(
                container = container,
                onOpenChat = { id -> navController.navigate("chat/$id") },
                onOpenSettings = { navController.navigate("settings") },
            )
        }
        composable("chat/{convId}") { entry ->
            val convId = entry.arguments?.getString("convId") ?: return@composable
            // Buang draft kosong saat keluar dari chat baru tanpa pesan
            ChatScreen(
                container = container,
                convId = convId,
                onBack = {
                    container.repo.dropIfEmpty(convId)
                    navController.popBackStack()
                },
                onNewChat = { id ->
                    container.repo.dropIfEmpty(convId)
                    navController.navigate("chat/$id") {
                        popUpTo("home") { inclusive = false }
                    }
                },
                onOpenSettings = { navController.navigate("settings") },
            )
        }
        composable("settings") {
            SettingsScreen(
                container = container,
                onBack = { navController.popBackStack() },
                onLoggedOut = {
                    navController.navigate("login") {
                        popUpTo(navController.graph.startDestinationId) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
    }
}
