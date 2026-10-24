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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** SHJ brand palette (matches the iOS app). */
private val ShjBlue = Color(0xFF00A0DC)
private val ShjBlueDark = Color(0xFF0071A8)
private val ShjGray = Color(0xFF9C9EA1)

private val ShjColors = lightColorScheme(
    primary = ShjBlue,
    onPrimary = Color.White,
    secondary = ShjBlueDark,
    tertiary = ShjGray,
)

class MainActivity : ComponentActivity() {
    private lateinit var ble: BleManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ble = BleManager(applicationContext)
        setContent { MaterialTheme(colorScheme = ShjColors) { AppScreen(ble) } }
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
    val showAll by ble.showAll.collectAsState()
    val fallback by ble.showingAllFallback.collectAsState()
    var cmd by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SAXBLE (SHJ)", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = ShjBlue)
            Spacer(Modifier.weight(1f))
            Text(
                if (loggedIn) "AUTH" else "— no auth",
                color = if (loggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            )
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Devices", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("Show all", fontSize = 12.sp)
            Spacer(Modifier.width(4.dp))
            Switch(checked = showAll, onCheckedChange = { ble.setShowAll(it) })
        }
        if (fallback) {
            Text(
                "No likely encoders found — showing all devices.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.error
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            items(devices) { d ->
                Column(
                    Modifier.fillMaxWidth().clickable { ble.connect(d.device) }.padding(vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(d.name, fontWeight = FontWeight.Medium)
                        if (d.likely) {
                            Spacer(Modifier.width(6.dp))
                            Text("likely encoder", fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(
                        "${d.address}   ${d.rssi} dBm",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Console", fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = cmd,
                onValueChange = { cmd = it },
                singleLine = true,
                label = { Text("raw command (e.g. location TEST1)") },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { if (cmd.isNotBlank()) { ble.send(cmd.trim()); cmd = "" } },
                enabled = phase == BleManager.Phase.CONNECTED
            ) { Text("Send") }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
        ) {
            logLines.forEach { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}
