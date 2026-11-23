package ru.vtext.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.ui.platform.LocalContext
import ru.vtext.app.player.PlaybackService
import ru.vtext.app.ui.audio.AudioPlayerScreen
import ru.vtext.app.ui.audio.AudioPlayerViewModel
import ru.vtext.app.ui.book.BookScreen
import ru.vtext.app.ui.library.LibraryScreen
import ru.vtext.app.ui.pdf.PdfViewerScreen
import ru.vtext.app.ui.reader.ReaderScreen
import ru.vtext.app.ui.settings.SettingsScreen
import ru.vtext.app.ui.update.UpdatePrompt
import ru.vtext.app.ui.theme.VtextTheme

class MainActivity : ComponentActivity() {

    private var pendingBookId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Контент под системными панелями: фон страницы продолжается в статус-бар.
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        pendingBookId = intent?.getStringExtra(PlaybackService.EXTRA_BOOK_ID)
        val appearance = (application as VtextApp).container.appearance
        setContent {
            val dynamicColor by appearance.dynamicColor.collectAsStateWithLifecycle()
            VtextTheme(dynamicColor = dynamicColor) {
                VtextNavHost(
                    pendingBookId = pendingBookId,
                    onBookConsumed = { pendingBookId = null },
                )
                UpdatePrompt()
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
                onOpenBook = { bookId -> navController.navigate("reader/$bookId") },
                onOpenAudio = { bookId -> navController.navigate("audio/$bookId") },
                onSettings = { navController.navigate("settings") },
            )
        }
        composable(
            route = "reader/{bookId}",
            arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
        ) { entry ->
            val bookId = entry.arguments?.getString("bookId").orEmpty()
            ReaderScreen(
                bookId = bookId,
                onBack = { navController.popBackStack() },
                onOpenPdf = { page ->
                    navController.navigate("pdf/$bookId?page=$page")
                },
                onListen = { chapter, segment ->
                    navController.navigate("book/$bookId?chapter=$chapter&segment=$segment")
                },
            )
        }
        composable(
            route = "pdf/{bookId}?page={page}",
            arguments = listOf(
                navArgument("bookId") { type = NavType.StringType },
                navArgument("page") { type = NavType.IntType; defaultValue = -1 },
            ),
        ) { entry ->
            val bookId = entry.arguments?.getString("bookId").orEmpty()
            val page = entry.arguments?.getInt("page") ?: -1
            PdfViewerScreen(
                bookId = bookId,
                startPage = page,
                onBack = { navController.popBackStack() },
                onText = { navController.popBackStack() },
            )
        }
        composable(
            route = "book/{bookId}?chapter={chapter}&segment={segment}",
            arguments = listOf(
                navArgument("bookId") { type = NavType.StringType },
                navArgument("chapter") { type = NavType.IntType; defaultValue = -1 },
                navArgument("segment") { type = NavType.IntType; defaultValue = -1 },
            ),
        ) { entry ->
            val bookId = entry.arguments?.getString("bookId").orEmpty()
            val chapter = entry.arguments?.getInt("chapter") ?: -1
            val segment = entry.arguments?.getInt("segment") ?: -1
            BookScreen(
                bookId = bookId,
                startChapter = chapter,
                startSegment = segment,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "audio/{bookId}",
            arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
        ) { entry ->
            val bookId = entry.arguments?.getString("bookId").orEmpty()
            val context = LocalContext.current
            val container = (context.applicationContext as VtextApp).container
            val audioViewModel: AudioPlayerViewModel = viewModel(
                factory = AudioPlayerViewModel.factory(container.audioPlayback),
            )
            AudioPlayerScreen(
                bookId = bookId,
                viewModel = audioViewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
