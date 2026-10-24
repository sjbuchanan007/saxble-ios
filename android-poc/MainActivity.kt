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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// SHJ brand palette (shared across the app).
val ShjBlue = Color(0xFF00A0DC)
val ShjBlueDark = Color(0xFF0071A8)
val ShjGray = Color(0xFF9C9EA1)

private val ShjColors = lightColorScheme(
    primary = ShjBlue, onPrimary = Color.White, secondary = ShjBlueDark, tertiary = ShjGray,
)

class MainActivity : ComponentActivity() {
    private lateinit var ble: BleManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ble = BleManager(applicationContext)
        setContent { MaterialTheme(colorScheme = ShjColors) { AppRoot(ble) } }
    }
}

/** Only show the commands once actually logged in; otherwise the landing screen. */
@Composable
fun AppRoot(ble: BleManager) {
    val loggedIn by ble.loggedIn.collectAsState()
    if (loggedIn) ConnectedScreen(ble) else ScanScreen(ble)
}

@Composable
fun ScanScreen(ble: BleManager) {
    var hasPerms by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> hasPerms = result.values.all { it } }
    LaunchedEffect(Unit) {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        launcher.launch(perms)
    }

    val phase by ble.phase.collectAsState()
    val devices by ble.devices.collectAsState()
    val showAll by ble.showAll.collectAsState()
    val fallback by ble.showingAllFallback.collectAsState()
    val status by ble.status.collectAsState()
    val needsPassword by ble.needsPassword.collectAsState()

    if (needsPassword) PasswordDialog(
        onSubmit = { ble.submitPassword(it) },
        onCancel = { ble.disconnect() }
    )

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("SAXBLE (SHJ)", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = ShjBlue)
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { ble.startScan() }, enabled = hasPerms) { Text("Scan") }
            Spacer(Modifier.weight(1f))
            Text(phase.name, color = MaterialTheme.colorScheme.outline)
        }
        if (status.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(status, color = ShjBlueDark, fontWeight = FontWeight.Medium)
        }
        if (!hasPerms) Text("Grant Bluetooth permission to scan.", color = MaterialTheme.colorScheme.error)

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Devices", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("Show all", fontSize = 12.sp)
            Spacer(Modifier.width(4.dp))
            Switch(checked = showAll, onCheckedChange = { ble.setShowAll(it) })
        }
        if (fallback) Text(
            "No likely encoders found — showing all devices.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.error
        )

        LazyColumn(Modifier.weight(1f)) {
            items(devices) { d ->
                Column(
                    Modifier.fillMaxWidth().clickable { ble.connect(d.device) }.padding(vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(d.name, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                        if (d.likely) {
                            Spacer(Modifier.width(6.dp))
                            Text("likely encoder", fontSize = 10.sp, color = ShjBlue)
                        }
                    }
                    Text("${d.address}   ${d.rssi} dBm", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
private fun PasswordDialog(onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Encoder password") },
        text = {
            Column {
                Text("The default passwords didn't work. Enter this encoder's password.")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = pw, onValueChange = { pw = it }, singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = { if (pw.isNotBlank()) onSubmit(pw.trim()) }) { Text("Log in") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Disconnect") } }
    )
}
