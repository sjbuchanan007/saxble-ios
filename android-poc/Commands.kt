package com.shj.saxble

/** What kind of parameter a command takes (drives the entry UI). */
enum class ParamType { NONE, NUMERIC, TEXT, ENUM }

data class EnumOption(val value: String, val label: String)

/**
 * One SAX-D command, modelled declaratively — ported 1:1 from the iOS app's
 * Commands.swift (itself ported from the Cardputer firmware's commands.cpp).
 */
data class CommandDef(
    val id: String,
    val label: String,
    val token: String,
    val needsChannel: Boolean,
    val param: ParamType,
    val paramHint: String? = null,
    val enumOptions: List<EnumOption> = emptyList(),
    val help: String = "",
    val destructive: Boolean = false,
) {
    /** Build the line to send. `channel` is "1".."6"/"a" for gas commands. */
    fun build(channel: String, value: String): String {
        var out = if (needsChannel) "gas $channel $token" else token
        if (param != ParamType.NONE && value.isNotEmpty()) out += " $value"
        return out
    }
}

data class CommandCategory(val id: String, val title: String, val commands: List<CommandDef>)

object SAXCommands {
    val channels = listOf("1", "2", "3", "4", "5", "6", "a")

    private val gasTypes = listOf(
        EnumOption("O2", "O2"), EnumOption("N2O", "N2O"), EnumOption("ENT", "ENT"),
        EnumOption("MA_4", "MA_4"), EnumOption("MA_7", "MA_7"), EnumOption("SA_7", "SA_7"),
        EnumOption("VAC", "VAC"), EnumOption("N2", "N2"), EnumOption("CO2", "CO2"),
        EnumOption("USER", "USER"),
    )
    private val units = listOf(
        EnumOption("Bar", "Bar"), EnumOption("mmHg", "mmHg"), EnumOption("PSI", "PSI"),
    )
    private val tones = listOf(
        EnumOption("0", "Tone 0"), EnumOption("1", "Tone 1"), EnumOption("2", "Tone 2"),
    )

    val gas = listOf(
        CommandDef("gas_type", "Type (defaults)", "type", true, ParamType.ENUM, enumOptions = gasTypes, help = "Load default settings for a gas type"),
        CommandDef("gas_on", "Enable", "on", true, ParamType.NONE, help = "Enable this gas channel"),
        CommandDef("gas_off", "Disable", "off", true, ParamType.NONE, help = "Disable this gas channel"),
        CommandDef("gas_list", "List settings", "list", true, ParamType.NONE, help = "Display settings for this gas"),
        CommandDef("gas_units", "Units", "units", true, ParamType.ENUM, enumOptions = units, help = "Set the pressure units"),
        CommandDef("gas_pron", "Pressure: show", "press_on", true, ParamType.NONE, help = "Display pressure"),
        CommandDef("gas_proff", "Pressure: hide", "press_off", true, ParamType.NONE, help = "Do not display pressure"),
        CommandDef("gas_pral", "Pressure: in alarm", "press_al", true, ParamType.NONE, help = "Show pressure only in alarm/warning"),
        CommandDef("gas_preng", "Pressure: on test", "press_eng", true, ParamType.NONE, help = "Show pressure only when test pressed"),
        CommandDef("gas_hi", "Hi pressure set", "hi_set", true, ParamType.NUMERIC, "bar e.g. 5.6", help = "Alarm above this pressure"),
        CommandDef("gas_lo", "Lo pressure set", "lo_set", true, ParamType.NUMERIC, "bar e.g. 3.35", help = "Alarm below this pressure"),
        CommandDef("gas_pd", "Pressure drop set", "pd_set", true, ParamType.NUMERIC, "bar e.g. 3.55", help = "Pressure-drop alarm threshold"),
        CommandDef("gas_hidiff", "Hi differential", "hi_diff", true, ParamType.NUMERIC, "bar e.g. 0.20", help = "Hi-alarm hysteresis"),
        CommandDef("gas_lodiff", "Lo differential", "lo_diff", true, ParamType.NUMERIC, "bar e.g. 0.20", help = "Lo-alarm hysteresis"),
        CommandDef("gas_pddiff", "Drop differential", "pd_diff", true, ParamType.NUMERIC, "bar e.g. 0.10", help = "Drop-alarm hysteresis"),
        CommandDef("gas_name", "Name", "name", true, ParamType.TEXT, "e.g. Oxygen", help = "Set the gas name text string"),
        CommandDef("gas_normal", "Normal text", "normal", true, ParamType.TEXT, "e.g. Normal", help = "Normal-state text string"),
        CommandDef("gas_high", "High text", "high", true, ParamType.TEXT, "use \\n for newline", help = "High-pressure text string"),
        CommandDef("gas_low", "Low text", "low", true, ParamType.TEXT, "use \\n for newline", help = "Low-pressure text string"),
        CommandDef("gas_drop", "Drop text", "drop", true, ParamType.TEXT, "use \\n for newline", help = "Pressure-drop text string"),
        CommandDef("gas_fault", "Fault text", "fault", true, ParamType.TEXT, "e.g. Signal\\nFault", help = "Fault text string"),
        CommandDef("gas_cal", "Calibrate point", "cal", true, ParamType.NUMERIC, "reading e.g. 4.40", help = "Update the reading calibration point", destructive = true),
        CommandDef("gas_atm", "Atmosphere zero", "atm", true, ParamType.NUMERIC, "e.g. 0", help = "Update the zero (atmosphere) calibration", destructive = true),
    )

    val general = listOf(
        CommandDef("gen_settings", "Settings", "settings", false, ParamType.NONE, help = "List system settings"),
        CommandDef("gen_location", "Location", "location", false, ParamType.TEXT, "use \\s for space", help = "Set the system location"),
        CommandDef("gen_engineer", "Engineer", "engineer", false, ParamType.TEXT, "engineer name/no", help = "Set the engineer identifier"),
        CommandDef("gen_password", "Change password", "password", false, ParamType.TEXT, "new password", help = "Change the login password", destructive = true),
        CommandDef("gen_modbus", "Modbus", "modbus", false, ParamType.TEXT, "addr(1-127) baud(0-3)", help = "Set Modbus address and baud rate"),
        CommandDef("gen_tone", "Tone", "tone", false, ParamType.ENUM, enumOptions = tones, help = "Change alarm tone"),
        CommandDef("gen_mute", "Mute timer", "mute", false, ParamType.NUMERIC, "min (0=off)", help = "Set mute timeout in minutes"),
        CommandDef("gen_logouttime", "Auto-logout", "logouttime", false, ParamType.NUMERIC, "minutes", help = "Set auto-logout time"),
        CommandDef("gen_screensave", "Screen saver", "screensave", false, ParamType.NUMERIC, "minutes", help = "Set screen-saver timeout (minutes)"),
        CommandDef("gen_logtime", "Log interval", "logtime", false, ParamType.NUMERIC, "minutes", help = "Set min/max log time interval"),
        CommandDef("gen_logdump", "Log dump", "logdump", false, ParamType.NONE, help = "List the log data"),
        CommandDef("gen_logout", "Logout", "logout", false, ParamType.NONE, help = "Log out of the console"),
        CommandDef("gen_help", "Help (list)", "help", false, ParamType.NONE, help = "List general commands"),
        CommandDef("gen_gashelp", "Gas help (list)", "help gas", false, ParamType.NONE, help = "List gas commands"),
        CommandDef("gen_logclear", "Log clear", "logclear", false, ParamType.NONE, help = "Clear the log data", destructive = true),
        CommandDef("gen_factory", "Factory reset", "factory", false, ParamType.NONE, help = "Reset to factory defaults", destructive = true),
        CommandDef("gen_reboot", "Reboot", "reboot", false, ParamType.NONE, help = "Reboot the encoder", destructive = true),
    )

    val gasCategory = CommandCategory("gas", "Gas Settings", gas)
    val generalCategory = CommandCategory("general", "General Settings", general)
    val categories = listOf(gasCategory, generalCategory)
}
