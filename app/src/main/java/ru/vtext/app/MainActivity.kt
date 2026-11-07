package ru.vtext.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ru.vtext.app.ui.book.BookScreen
import ru.vtext.app.ui.library.LibraryScreen
import ru.vtext.app.ui.theme.VtextTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VtextTheme {
                VtextNavHost()
            }
        }
    }
}

@Composable
private fun VtextNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "library") {
        composable("library") {
            LibraryScreen(onOpenBook = { bookId -> navController.navigate("book/$bookId") })
        }
        composable(
            route = "book/{bookId}",
            arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
        ) { entry ->
            val bookId = entry.arguments?.getString("bookId").orEmpty()
            BookScreen(bookId = bookId, onBack = { navController.popBackStack() })
        }
    }
}
