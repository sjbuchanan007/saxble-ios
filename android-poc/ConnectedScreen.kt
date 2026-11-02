package com.shj.saxble

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Destructive = Color(0xFFD9822B)

@Composable
fun ConnectedScreen(ble: BleManager) {
    var tab by remember { mutableStateOf(0) }
    val warning by ble.passwordWarning.collectAsState()
    val needLocation by ble.needLocation.collectAsState()
    val status by ble.status.collectAsState()

    warning?.let {
        AlertDialog(
            onDismissRequest = { ble.clearPasswordWarning() },
            title = { Text("Password warning") },
            text = { Text(it) },
            confirmButton = { Button(onClick = { ble.clearPasswordWarning() }) { Text("OK") } },
        )
    }
    if (needLocation) LocationDialog(ble)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(ShjBlue).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("SAXBLE (SHJ)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            Text("AUTH", color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(12.dp))
            OutlinedButton(
                onClick = { ble.disconnectAndReport() },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) { Text("Disconnect") }
        }
        if (status.isNotEmpty() && status != "Logged in") {
            Text(status, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = ShjBlueDark)
        }

        TabRow(selectedTabIndex = tab) {
            listOf("Gas", "General", "Presets", "Console").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> CommandGrid(ble, SAXCommands.gasCategory)
                1 -> CommandGrid(ble, SAXCommands.generalCategory)
                2 -> PresetsTab(ble)
                else -> ConsoleTab(ble)
            }
        }
    }
}

@Composable
private fun CommandGrid(ble: BleManager, category: CommandCategory) {
    var selected by remember { mutableStateOf<CommandDef?>(null) }
    selected?.let { CommandDialog(ble, it) { selected = null } }

    LazyVerticalGrid(columns = GridCells.Fixed(2), contentPadding = PaddingValues(12.dp)) {
        gridItems(category.commands) { cmd ->
            Card(
                Modifier.padding(6.dp).fillMaxWidth().height(96.dp).clickable { selected = cmd }
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(cmd.label, fontWeight = FontWeight.SemiBold)
                    Text(cmd.token, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.weight(1f))
                    if (cmd.destructive) Text("destructive", fontSize = 10.sp, color = Destructive)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommandDialog(ble: BleManager, cmd: CommandDef, onClose: () -> Unit) {
    var channel by remember { mutableStateOf("1") }
    var text by remember { mutableStateOf("") }
    var enumValue by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }

    fun fire() {
        val value = when (cmd.param) {
            ParamType.ENUM -> enumValue
            ParamType.NONE -> ""
            else -> text.trim()
        }
        if (cmd.destructive) ble.expectConfirm()
        val line = cmd.build(channel, value)
        if (cmd.id == "gen_password") ble.sendSlow(line) else ble.send(line)
        onClose()
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Confirm ${cmd.label}?") },
            text = { Text("Destructive command. Any \"Y or N\" prompt is auto-answered Y.") },
            confirmButton = { Button(onClick = { confirm = false; fire() }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(cmd.label) },
        text = {
            Column {
                if (cmd.help.isNotEmpty()) {
                    Text(cmd.help, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                }
                if (cmd.needsChannel) {
                    Text("Channel", fontSize = 12.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SAXCommands.channels.forEach { c ->
                            FilterChip(
                                selected = channel == c,
                                onClick = { channel = c },
                                label = { Text(if (c == "a") "All" else c) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                when (cmd.param) {
                    ParamType.NUMERIC -> OutlinedTextField(
                        value = text, onValueChange = { text = it }, singleLine = true,
                        label = { Text(cmd.paramHint ?: "Value") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    ParamType.TEXT -> OutlinedTextField(
                        value = text, onValueChange = { text = it }, singleLine = true,
                        label = { Text(cmd.paramHint ?: "Text") },
                    )
                    ParamType.ENUM -> {
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { expanded = true }) {
                                Text(if (enumValue.isEmpty()) "Select…" else enumValue)
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                cmd.enumOptions.forEach { o ->
                                    DropdownMenuItem(text = { Text(o.label) },
                                        onClick = { enumValue = o.value; expanded = false })
                                }
                            }
                        }
                    }
                    ParamType.NONE -> {}
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (cmd.destructive) confirm = true else fire() },
                colors = if (cmd.destructive)
                    ButtonDefaults.buttonColors(containerColor = Destructive) else ButtonDefaults.buttonColors()
            ) { Text(if (cmd.destructive) "Send…" else "Send") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun PresetsTab(ble: BleManager) {
    val running by ble.presetRunning.collectAsState()
    val pstatus by ble.presetStatus.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Presets.all.forEach { preset ->
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(preset.name, fontWeight = FontWeight.SemiBold)
                        Text("${preset.steps.size} steps", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.outline)
                    }
                    Button(onClick = { ble.runPreset(preset) }, enabled = !running) { Text("Run") }
                }
            }
        }
        if (pstatus.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(pstatus, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun ConsoleTab(ble: BleManager) {
    val logLines by ble.log.collectAsState()
    var cmd by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = cmd, onValueChange = { cmd = it }, singleLine = true,
                label = { Text("raw command") }, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { if (cmd.isNotBlank()) { ble.send(cmd.trim()); cmd = "" } }) { Text("Send") }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(logLines) { line ->
                Text(line.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun LocationDialog(ble: BleManager) {
    var loc by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { ble.submitLocation("") },
        title = { Text("System location") },
        text = {
            Column {
                Text("Spaces are converted to \\s for the encoder.")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = loc, onValueChange = { loc = it }, singleLine = true,
                    label = { Text("e.g. Ward 4 Theatre 2") })
            }
        },
        confirmButton = { Button(onClick = { ble.submitLocation(loc) }) { Text("Send") } },
        dismissButton = { TextButton(onClick = { ble.submitLocation("") }) { Text("Skip") } },
    )
}
