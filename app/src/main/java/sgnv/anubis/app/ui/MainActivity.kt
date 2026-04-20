package sgnv.anubis.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import sgnv.anubis.app.ui.screens.AppListScreen
import sgnv.anubis.app.ui.screens.HomeScreen
import sgnv.anubis.app.ui.screens.RecoveryScreen
import sgnv.anubis.app.ui.screens.SettingsScreen
import sgnv.anubis.app.ui.screens.VpnClientScreen
import sgnv.anubis.app.ui.theme.AnubisTheme
import sgnv.anubis.app.update.UpdateDialog

class MainActivity : ComponentActivity() {

    private var viewModelRef: MainViewModel? = null

    /** Handles VPN permission dialog result */
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // VPN permission granted — now proceed with stealth toggle
            viewModelRef?.toggleStealth()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AnubisTheme {
                val viewModel: MainViewModel = viewModel()
                viewModelRef = viewModel
                var selectedTab by rememberSaveable { mutableIntStateOf(0) }
                var showRecovery by rememberSaveable { mutableStateOf(false) }
                val dismissRecovery: () -> Unit = { showRecovery = false }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0; dismissRecovery() },
                                icon = { Icon(Icons.Default.Home, contentDescription = null) },
                                label = { Text("Главная") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1; dismissRecovery() },
                                icon = { Icon(Icons.Default.List, contentDescription = null) },
                                label = { Text("Приложения") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2; dismissRecovery() },
                                icon = { Icon(Icons.Default.Lock, contentDescription = null) },
                                label = { Text("VPN") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 3,
                                onClick = { selectedTab = 3; dismissRecovery() },
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                label = { Text("Настройки") }
                            )
                        }
                    }
                ) { padding ->
                    if (showRecovery) {
                        RecoveryScreen(
                            viewModel = viewModel,
                            onBack = dismissRecovery,
                            modifier = Modifier.padding(padding)
                        )
                    } else when (selectedTab) {
                        0 -> HomeScreen(
                            viewModel = viewModel,
                            onRequestVpnPermission = { intent ->
                                vpnPermissionLauncher.launch(intent)
                            },
                            onOpenRecovery = { showRecovery = true },
                            modifier = Modifier.padding(padding)
                        )
                        1 -> AppListScreen(viewModel, Modifier.padding(padding))
                        2 -> VpnClientScreen(viewModel, Modifier.padding(padding))
                        3 -> SettingsScreen(
                            viewModel = viewModel,
                            onOpenRecovery = { showRecovery = true },
                            modifier = Modifier.padding(padding)
                        )
                    }

                    val updateInfo by viewModel.updateInfo.collectAsState()
                    updateInfo?.let { info ->
                        if (info.isUpdateAvailable) {
                            UpdateDialog(
                                info = info,
                                onDismiss = { viewModel.dismissUpdateDialog() },
                                onSkip = { viewModel.skipCurrentUpdate() },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModelRef?.onResume()
    }
}
