package com.shj.saxble

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One-screen proof-of-concept UI: request BLE permissions, scan, tap a device to
 * connect, watch the console log, and see AUTH light up when "Welcome to Shire"
 * arrives. Branding kept neutral (SHJ) — no logo.
 */
class MainActivity : ComponentActivity() {
    private lateinit var ble: BleManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ble = BleManager(applicationContext)
        setContent { MaterialTheme { AppScreen(ble) } }
    }
}

@Composable
fun AppScreen(ble: BleManager) {
    var hasPerms by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> hasPerms = result.values.all { it } }

    LaunchedEffect(Unit) {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        launcher.launch(perms)
    }

    val phase by ble.phase.collectAsState()
    val devices by ble.devices.collectAsState()
    val logLines by ble.log.collectAsState()
    val loggedIn by ble.loggedIn.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("SAXBLE (SHJ)", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            Text(if (loggedIn) "AUTH" else "— no auth",
                color = if (loggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        }
        Spacer(Modifier.height(8.dp))

        Row {
            Button(onClick = { ble.startScan() }, enabled = hasPerms) { Text("Scan") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { ble.disconnect() }) { Text("Disconnect") }
            Spacer(Modifier.weight(1f))
            Text(phase.name, color = MaterialTheme.colorScheme.outline)
        }
        if (!hasPerms) {
            Text("Grant Bluetooth permission to scan.", color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(12.dp))
        Text("Devices", fontWeight = FontWeight.SemiBold)
        LazyColumn(Modifier.weight(1f)) {
            items(devices) { d ->
                Column(Modifier.fillMaxWidth().clickable { ble.connect(d.device) }.padding(vertical = 8.dp)) {
                    Text(d.name, fontWeight = FontWeight.Medium)
                    Text("${d.address}   ${d.rssi} dBm", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Console", fontWeight = FontWeight.SemiBold)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
        ) {
            logLines.forEach { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}
