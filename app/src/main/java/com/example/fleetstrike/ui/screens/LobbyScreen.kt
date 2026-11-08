package com.example.fleetstrike.ui.screens

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.fleetstrike.net.Bluetooth
import com.example.fleetstrike.net.BtPeer
import com.example.fleetstrike.ui.AppViewModel
import com.example.fleetstrike.ui.LobbyState
import com.example.fleetstrike.ui.Transport
import com.example.fleetstrike.ui.theme.Sea

/** Hosting a game, or finding one to join, over Wi-Fi or Bluetooth. */
@Composable
fun LobbyScreen(vm: AppViewModel, transport: Transport, hosting: Boolean) {
    val context = LocalContext.current
    val lobby = vm.lobby
    BackHandler(onBack = vm::cancelLobby)

    // Bluetooth needs run-time permission and the radio switched on; this walks through both, then carries on.
    val next = remember { mutableStateOf<(() -> Unit)?>(null) }
    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val then = next.value
        next.value = null
        if (granted.values.all { it }) then?.invoke()
        else vm.lobbyError("Fleet Strike needs the Nearby devices permission to play over Bluetooth. Tap Try again and choose Allow.")
    }
    val askEnable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val then = next.value
        next.value = null
        if (Bluetooth.isEnabled(context)) then?.invoke() else vm.lobbyError("Bluetooth is switched off. Switch it on and tap Try again.")
    }
    val askVisible = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    fun withBluetooth(perms: Array<String>, then: () -> Unit) {
        lobby.error = null
        when {
            !Bluetooth.isAvailable(context) -> vm.lobbyError("This tablet doesn't have Bluetooth. Play over Wi-Fi instead.")
            perms.isNotEmpty() && !Bluetooth.hasPermissions(context, perms) -> {
                next.value = { withBluetooth(perms, then) }
                askPermissions.launch(perms)
            }
            !Bluetooth.isEnabled(context) -> {
                next.value = { withBluetooth(perms, then) }
                try {
                    askEnable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } catch (_: Exception) {
                    vm.lobbyError("Switch Bluetooth on in Settings, then tap Try again.")
                }
            }
            else -> then()
        }
    }

    fun makeVisible() {
        try {
            askVisible.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
        } catch (_: Exception) {
        }
    }

    val startBluetooth: () -> Unit = {
        if (hosting) withBluetooth(Bluetooth.hostPermissions()) {
            vm.hostBluetooth()
            makeVisible()
        } else withBluetooth(Bluetooth.joinPermissions()) { vm.searchBluetooth() }
    }

    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (transport == Transport.BLUETOOTH && !started) {
            started = true
            startBluetooth()
        }
    }

    SeaBackground {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                (if (hosting) "Host" else "Join") + " a game over ${transport.title}",
                color = Sea.Text,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                lobby.error?.let { ErrorPanel(it, onRetry = if (transport == Transport.BLUETOOTH) startBluetooth else null) }
                lobby.connectingTo?.let { Waiting("Connecting to $it…") }
                when {
                    hosting -> HostPanel(transport, lobby, onMakeVisible = ::makeVisible)
                    transport == Transport.WIFI -> WifiJoinPanel(vm, lobby)
                    else -> BluetoothJoinPanel(vm, lobby, onScan = { withBluetooth(Bluetooth.joinPermissions()) { vm.scanBluetooth() } })
                }
                QuietButton("Back", vm::cancelLobby, Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun HostPanel(transport: Transport, lobby: LobbyState, onMakeVisible: () -> Unit) {
    Panel(Modifier.fillMaxWidth(), accent = Sea.Amber) {
        Waiting(lobby.status.ifEmpty { "Getting ready…" })
        val address = lobby.hostAddress
        if (transport == Transport.WIFI) {
            Hint("On the other tablet, open Fleet Strike, tap Join game under Wi-Fi and pick this game from the list.")
            if (address != null) {
                Text("If it doesn't show up there, type this address on the other tablet:", color = Sea.TextDim, fontSize = 14.sp)
                Text(address, color = Sea.Amber, fontSize = 30.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        } else {
            Hint("On the other tablet, open Fleet Strike, tap Join game under Bluetooth and pick this tablet:")
            if (address != null) Text(address, color = Sea.Amber, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Hint("If it isn't listed there, make this tablet visible again (it stays visible for 5 minutes).")
            QuietButton("Make this tablet visible", onMakeVisible)
        }
    }
}

@Composable
private fun WifiJoinPanel(vm: AppViewModel, lobby: LobbyState) {
    Panel(Modifier.fillMaxWidth(), accent = Sea.Sonar) {
        PanelTitle("Games on this Wi-Fi")
        if (lobby.wifiGames.isEmpty()) {
            Waiting(lobby.status)
            Hint("Ask the other player to tap Host game under Wi-Fi. Their game appears here within a few seconds.")
        }
        for (game in lobby.wifiGames) {
            PeerRow(game.name, game.ip, enabled = lobby.connectingTo == null) { vm.joinWifi(game.ip, game.port, game.name) }
        }
    }
    Panel(Modifier.fillMaxWidth()) {
        PanelTitle("Not listed? Type the host's address")
        var address by rememberSaveable { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ':' } },
                label = { Text("e.g. 192.168.1.20") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { vm.joinWifiByAddress(address) }),
                modifier = Modifier.weight(1f),
            )
            BigButton("Join", { vm.joinWifiByAddress(address) }, enabled = address.isNotBlank() && lobby.connectingTo == null)
        }
        lobby.hostAddress?.let { Hint("This tablet is $it") }
    }
}

@Composable
private fun BluetoothJoinPanel(vm: AppViewModel, lobby: LobbyState, onScan: () -> Unit) {
    Panel(Modifier.fillMaxWidth(), accent = Sea.Sonar) {
        PanelTitle("Paired tablets")
        if (lobby.paired.isEmpty()) Hint("None yet. Tablets you've paired in Bluetooth settings appear here.")
        for (peer in lobby.paired) BluetoothRow(vm, lobby, peer)
    }
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelTitle("Nearby")
            Spacer(Modifier.weight(1f))
            if (lobby.scanning) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = Sea.Sonar)
            else QuietButton("Scan again", onScan)
        }
        if (lobby.nearby.isEmpty()) {
            Hint(
                if (lobby.scanning) "Searching… The hosting tablet must have tapped Host game under Bluetooth."
                else "Nothing found. Check the other tablet is hosting and visible, then scan again."
            )
        }
        for (peer in lobby.nearby) BluetoothRow(vm, lobby, peer)
    }
}

@Composable
private fun BluetoothRow(vm: AppViewModel, lobby: LobbyState, peer: BtPeer) {
    PeerRow(peer.name, peer.address, enabled = lobby.connectingTo == null) { vm.joinBluetooth(peer) }
}

@Composable
private fun PeerRow(title: String, detail: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Sea.PanelHigh, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Sea.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Sea.TextDim, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
        Text("Join ›", color = if (enabled) Sea.Amber else Sea.TextDim, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Waiting(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp, color = Sea.Amber)
        Text(text, color = Sea.Text, fontSize = 18.sp)
    }
}

@Composable
private fun ErrorPanel(message: String, onRetry: (() -> Unit)?) {
    Panel(Modifier.fillMaxWidth(), accent = Sea.Danger) {
        Text(message, color = Sea.Danger, fontSize = 16.sp)
        if (onRetry != null) QuietButton("Try again", onRetry)
    }
}
