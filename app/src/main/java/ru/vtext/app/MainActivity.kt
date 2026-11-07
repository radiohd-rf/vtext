package ru.vtext.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ru.vtext.app.player.PlaybackService
import ru.vtext.app.ui.book.BookScreen
import ru.vtext.app.ui.library.LibraryScreen
import ru.vtext.app.ui.settings.SettingsScreen
import ru.vtext.app.ui.theme.VtextTheme

class MainActivity : ComponentActivity() {

    private var pendingBookId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        pendingBookId = intent?.getStringExtra(PlaybackService.EXTRA_BOOK_ID)
        setContent {
            VtextTheme {
                VtextNavHost(
                    pendingBookId = pendingBookId,
                    onBookConsumed = { pendingBookId = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingBookId = intent.getStringExtra(PlaybackService.EXTRA_BOOK_ID)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 100
    }
}

@Composable
private fun VtextNavHost(pendingBookId: String?, onBookConsumed: () -> Unit) {
    val navController = rememberNavController()

    LaunchedEffect(pendingBookId) {
        val bookId = pendingBookId ?: return@LaunchedEffect
        navController.navigate("book/$bookId")
        onBookConsumed()
    }

    NavHost(navController = navController, startDestination = "library") {
        composable("library") {
            LibraryScreen(
                onOpenBook = { bookId -> navController.navigate("book/$bookId") },
                onSettings = { navController.navigate("settings") },
            )
        }
        composable(
            route = "book/{bookId}",
            arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
        ) { entry ->
            val bookId = entry.arguments?.getString("bookId").orEmpty()
            BookScreen(bookId = bookId, onBack = { navController.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
