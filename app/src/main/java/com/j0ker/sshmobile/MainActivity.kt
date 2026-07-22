package com.j0ker.sshmobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.j0ker.sshmobile.data.ConnectionProfile
import com.j0ker.sshmobile.ui.HomeScreen
import com.j0ker.sshmobile.ui.SessionScreen
import com.j0ker.sshmobile.ui.SessionViewModel
import com.j0ker.sshmobile.ui.SettingsScreen
import com.j0ker.sshmobile.ui.SftpScreen
import com.j0ker.sshmobile.ui.SshMobileTheme

private enum class Screen { Home, Sessions, Sftp, Settings }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SshMobileTheme {
                val vm: SessionViewModel = viewModel()
                var screen by remember { mutableStateOf(Screen.Home) }
                var sftpProfile by remember { mutableStateOf<ConnectionProfile?>(null) }

                BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

                when (screen) {
                    Screen.Home -> HomeScreen(
                        vm = vm,
                        onOpenSessions = { screen = Screen.Sessions },
                        onOpenSftp = { sftpProfile = it; screen = Screen.Sftp },
                        onOpenSettings = { screen = Screen.Settings },
                    )
                    Screen.Sessions -> SessionScreen(vm) { screen = Screen.Home }
                    Screen.Sftp -> sftpProfile?.let { profile ->
                        SftpScreen(profile) { screen = Screen.Home }
                    } ?: run { screen = Screen.Home }
                    Screen.Settings -> SettingsScreen(vm) { screen = Screen.Home }
                }
            }
        }
    }
}
