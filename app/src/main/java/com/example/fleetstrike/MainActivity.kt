package com.example.fleetstrike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.fleetstrike.ui.AppViewModel
import com.example.fleetstrike.ui.Screen
import com.example.fleetstrike.ui.screens.HomeScreen
import com.example.fleetstrike.ui.screens.LobbyScreen
import com.example.fleetstrike.ui.screens.MatchScreen
import com.example.fleetstrike.ui.theme.FleetStrikeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FleetStrikeTheme {
                val vm: AppViewModel = viewModel()
                LaunchedEffect(vm.rotation) { requestedOrientation = vm.rotation.requested }
                when (val screen = vm.screen) {
                    Screen.Home -> HomeScreen(vm)
                    is Screen.Lobby -> LobbyScreen(vm, screen.transport, screen.hosting)
                    is Screen.Match -> MatchScreen(vm, screen)
                }
            }
        }
    }
}
