package sgnv.anubis.app.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collectLatest
import sgnv.anubis.app.ui.screens.AppListScreen
import sgnv.anubis.app.ui.screens.HomeScreen
import sgnv.anubis.app.ui.screens.LogScreen
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
        setTheme(sgnv.anubis.app.R.style.Theme_Anubis)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AnubisTheme {
                val viewModel: MainViewModel = viewModel()
                viewModelRef = viewModel
                val context = LocalContext.current
                var selectedTab by rememberSaveable { mutableIntStateOf(0) }
                var showRecovery by rememberSaveable { mutableStateOf(false) }
                var showJournal by rememberSaveable { mutableStateOf(false) }
                val dismissRecovery: () -> Unit = { showRecovery = false }
                val dismissJournal: () -> Unit = { showJournal = false }
                val selectTab: (Int) -> Unit = { tab ->
                    selectedTab = tab
                    dismissRecovery()
                    dismissJournal()
                }

                LaunchedEffect(viewModel) {
                    viewModel.toastMessages.collectLatest { messageText ->
                        Toast.makeText(context, messageText, Toast.LENGTH_SHORT).show()
                    }
                }

                BackHandler(enabled = showRecovery || showJournal) {
                    when {
                        showJournal -> dismissJournal()
                        showRecovery -> dismissRecovery()
                    }
                }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectTab(0) },
                                icon = { Icon(Icons.Default.Home, contentDescription = null) },
                                label = { Text("Главная") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectTab(1) },
                                icon = { Icon(Icons.Default.List, contentDescription = null) },
                                label = { Text("Приложения") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectTab(2) },
                                icon = { Icon(Icons.Default.Lock, contentDescription = null) },
                                label = { Text("VPN") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 3,
                                onClick = { selectTab(3) },
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
                    } else if (showJournal) {
                        LogScreen(
                            onBack = dismissJournal,
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
                            onOpenJournal = { showJournal = true },
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
