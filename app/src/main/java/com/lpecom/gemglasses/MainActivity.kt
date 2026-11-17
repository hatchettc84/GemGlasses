package com.lpecom.gemglasses

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lpecom.gemglasses.ui.HomeScreen
import com.lpecom.gemglasses.ui.SettingsScreen
import com.lpecom.gemglasses.ui.TranscriptScreen
import com.lpecom.gemglasses.ui.theme.GemGlassesTheme
import com.lpecom.gemglasses.bobai.ui.BobAiRoot
import com.lpecom.gemglasses.bobai.ui.BobAiTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // The Meta DAT SDK finds the glasses through bonded Bluetooth devices,
    // which needs BLUETOOTH_CONNECT at runtime (API 31+). Mock builds skip it.
    private val bluetoothRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i("MainActivity", "BLUETOOTH_CONNECT granted=$granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.USE_REAL_GLASSES &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothRequest.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
        enableEdgeToEdge()
        setContent {
            // BobAI is the launcher UI. The original GemGlasses (Gemini Live) screens
            // are unchanged and reachable from BobAI Settings → Live voice.
            BobAiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    BobAiRoot(legacyContent = { GemGlassesTheme { GemGlassesRoot() } })
                }
            }
        }
    }
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Sessão", Icons.Filled.Home),
    TRANSCRIPT("transcript", "Transcrição", Icons.AutoMirrored.Filled.List),
    SETTINGS("settings", "Ajustes", Icons.Filled.Settings),
}

@Composable
internal fun GemGlassesRoot() {
    val navController = rememberNavController()
    Scaffold(
        bottomBar = {
            val backStack by navController.currentBackStackEntryAsState()
            val current = backStack?.destination
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = current?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Tab.HOME.route,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Tab.HOME.route) { HomeScreen(Modifier.padding(padding)) }
            composable(Tab.TRANSCRIPT.route) { TranscriptScreen(Modifier.padding(padding)) }
            composable(Tab.SETTINGS.route) { SettingsScreen(Modifier.padding(padding)) }
        }
    }
}
