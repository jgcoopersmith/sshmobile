package com.j0ker.sshmobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
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

                // rememberSaveable, not remember: rotation recreates the
                // activity, and plain remember would drop the user back to the
                // connection list mid-session. The SSH sockets themselves live
                // in the ViewModel and are unaffected either way.
                var screen by rememberSaveable { mutableStateOf(Screen.Home) }
                // Only the id survives — ConnectionProfile isn't Parcelable, and
                // the profile list is the source of truth anyway.
                var sftpProfileId by rememberSaveable { mutableStateOf<String?>(null) }

                BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

                when (screen) {
                    Screen.Home -> HomeScreen(
                        vm = vm,
                        onOpenSessions = { screen = Screen.Sessions },
                        onOpenSftp = { sftpProfileId = it.id; screen = Screen.Sftp },
                        onOpenSettings = { screen = Screen.Settings },
                    )
                    Screen.Sessions -> SessionScreen(vm) { screen = Screen.Home }
                    Screen.Sftp -> {
                        val profile = vm.profiles.firstOrNull { it.id == sftpProfileId }
                        if (profile != null) {
                            SftpScreen(profile) { screen = Screen.Home }
                        } else {
                            LaunchedEffect(Unit) { screen = Screen.Home }
                        }
                    }
                    Screen.Settings -> SettingsScreen(vm) { screen = Screen.Home }
                }
            }
        }
    }
}
