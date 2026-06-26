import Foundation

/// What kind of parameter a command takes (drives the entry UI).
enum ParamType {
    case none      // send as-is
    case numeric   // number entry
    case text      // free text
    case enumPick  // pick-list (enumOptions)
}

struct EnumOption: Identifiable, Hashable {
    let value: String   // sent to the encoder
    let label: String   // shown to the user
    var id: String { value }
}

/// One SAX-D command, modelled declaratively (ported 1:1 from the Cardputer
/// firmware's commands.cpp so behaviour matches exactly).
struct CommandDef: Identifiable, Hashable {
    let id: String
    let label: String
    let token: String
    let needsChannel: Bool
    let param: ParamType
    let paramHint: String?
    let enumOptions: [EnumOption]
    let help: String
    let destructive: Bool

    init(_ id: String, _ label: String, _ token: String, _ needsChannel: Bool,
         _ param: ParamType, _ paramHint: String? = nil,
         _ enumOptions: [EnumOption] = [], _ help: String = "",
         destructive: Bool = false) {
        self.id = id; self.label = label; self.token = token
        self.needsChannel = needsChannel; self.param = param
        self.paramHint = paramHint; self.enumOptions = enumOptions
        self.help = help; self.destructive = destructive
    }

    /// Build the line to send. `channel` is "1"..."6"/"a" for gas commands.
    func build(channel: String, param: String) -> String {
        var out = needsChannel ? "gas \(channel) \(token)" : token
        if self.param != .none, !param.isEmpty { out += " \(param)" }
        return out
    }
}

struct CommandCategory: Identifiable {
    let id: String
    let title: String
    let commands: [CommandDef]
}

enum SAXCommands {
    static let channels = ["1", "2", "3", "4", "5", "6", "a"]

    private static let gasTypes = [
        EnumOption(value: "O2", label: "O2"),   EnumOption(value: "N2O", label: "N2O"),
        EnumOption(value: "ENT", label: "ENT"), EnumOption(value: "MA_4", label: "MA_4"),
        EnumOption(value: "MA_7", label: "MA_7"),EnumOption(value: "SA_7", label: "SA_7"),
        EnumOption(value: "VAC", label: "VAC"), EnumOption(value: "N2", label: "N2"),
        EnumOption(value: "CO2", label: "CO2"), EnumOption(value: "USER", label: "USER"),
    ]
    private static let units = [
        EnumOption(value: "Bar", label: "Bar"),
        EnumOption(value: "mmHg", label: "mmHg"),
        EnumOption(value: "PSI", label: "PSI"),
    ]
    private static let tones = [
        EnumOption(value: "0", label: "Tone 0"),
        EnumOption(value: "1", label: "Tone 1"),
        EnumOption(value: "2", label: "Tone 2"),
    ]

    static let gas: [CommandDef] = [
        CommandDef("gas_name", "Name", "name", true, .text, "e.g. Oxygen", [], "Set the gas name text string"),
        CommandDef("gas_normal", "Normal text", "normal", true, .text, "e.g. Normal", [], "Normal-state text string"),
        CommandDef("gas_high", "High text", "high", true, .text, "use \\n for newline", [], "High-pressure text string"),
        CommandDef("gas_drop", "Drop text", "drop", true, .text, "use \\n for newline", [], "Pressure-drop text string"),
        CommandDef("gas_low", "Low text", "low", true, .text, "use \\n for newline", [], "Low-pressure text string"),
        CommandDef("gas_fault", "Fault text", "fault", true, .text, "e.g. Signal\\nFault", [], "Fault text string"),
        CommandDef("gas_hi", "Hi pressure set", "hi_set", true, .numeric, "bar e.g. 5.6", [], "Alarm above this pressure"),
        CommandDef("gas_pd", "Pressure drop set", "pd_set", true, .numeric, "bar e.g. 3.55", [], "Pressure-drop alarm threshold"),
        CommandDef("gas_lo", "Lo pressure set", "lo_set", true, .numeric, "bar e.g. 3.35", [], "Alarm below this pressure"),
        CommandDef("gas_hidiff", "Hi differential", "hi_diff", true, .numeric, "bar e.g. 0.20", [], "Hi-alarm hysteresis"),
        CommandDef("gas_pddiff", "Drop differential", "pd_diff", true, .numeric, "bar e.g. 0.10", [], "Drop-alarm hysteresis"),
        CommandDef("gas_lodiff", "Lo differential", "lo_diff", true, .numeric, "bar e.g. 0.20", [], "Lo-alarm hysteresis"),
        CommandDef("gas_list", "List settings", "list", true, .none, nil, [], "Display settings for this gas"),
        CommandDef("gas_on", "Enable", "on", true, .none, nil, [], "Enable this gas channel"),
        CommandDef("gas_off", "Disable", "off", true, .none, nil, [], "Disable this gas channel"),
        CommandDef("gas_pron", "Pressure: show", "press_on", true, .none, nil, [], "Display pressure"),
        CommandDef("gas_proff", "Pressure: hide", "press_off", true, .none, nil, [], "Do not display pressure"),
        CommandDef("gas_pral", "Pressure: in alarm", "press_al", true, .none, nil, [], "Show pressure only in alarm/warning"),
        CommandDef("gas_preng", "Pressure: on test", "press_eng", true, .none, nil, [], "Show pressure only when test pressed"),
        CommandDef("gas_type", "Type (defaults)", "type", true, .enumPick, nil, gasTypes, "Load default settings for a gas type"),
        CommandDef("gas_units", "Units", "units", true, .enumPick, nil, units, "Set the pressure units"),
        CommandDef("gas_cal", "Calibrate point", "cal", true, .numeric, "reading e.g. 4.40", [], "Update the reading calibration point", destructive: true),
        CommandDef("gas_atm", "Atmosphere zero", "atm", true, .numeric, "e.g. 0", [], "Update the zero (atmosphere) calibration", destructive: true),
    ]

    static let general: [CommandDef] = [
        CommandDef("gen_tone", "Tone", "tone", false, .enumPick, nil, tones, "Change alarm tone"),
        CommandDef("gen_mute", "Mute timer", "mute", false, .numeric, "min (0=off)", [], "Set mute timeout in minutes"),
        CommandDef("gen_logout", "Logout", "logout", false, .none, nil, [], "Log out of the console"),
        CommandDef("gen_logouttime", "Auto-logout", "logouttime", false, .numeric, "minutes", [], "Set auto-logout time"),
        CommandDef("gen_password", "Change password", "password", false, .text, "new password", [], "Change the login password", destructive: true),
        CommandDef("gen_modbus", "Modbus", "modbus", false, .text, "addr(1-127) baud(0-3)", [], "Set Modbus address and baud rate"),
        CommandDef("gen_location", "Location", "location", false, .text, "use \\s for space", [], "Set the system location"),
        CommandDef("gen_engineer", "Engineer", "engineer", false, .text, "engineer name/no", [], "Set the engineer identifier"),
        CommandDef("gen_screensave", "Screen saver", "screensave", false, .numeric, "minutes", [], "Set screen-saver timeout (minutes)"),
        CommandDef("gen_settings", "Settings", "settings", false, .none, nil, [], "List system settings"),
        CommandDef("gen_logtime", "Log interval", "logtime", false, .numeric, "minutes", [], "Set min/max log time interval"),
        CommandDef("gen_logdump", "Log dump", "logdump", false, .none, nil, [], "List the log data"),
        CommandDef("gen_logclear", "Log clear", "logclear", false, .none, nil, [], "Clear the log data", destructive: true),
        CommandDef("gen_factory", "Factory reset", "factory", false, .none, nil, [], "Reset to factory defaults", destructive: true),
        CommandDef("gen_reboot", "Reboot", "reboot", false, .none, nil, [], "Reboot the encoder", destructive: true),
        CommandDef("gen_help", "Help (list)", "help", false, .none, nil, [], "List general commands"),
        CommandDef("gen_gashelp", "Gas help (list)", "help gas", false, .none, nil, [], "List gas commands"),
    ]

    static let gasCategory = CommandCategory(id: "gas", title: "Gas Settings", commands: gas)
    static let generalCategory = CommandCategory(id: "general", title: "General Settings", commands: general)

    static let categories: [CommandCategory] = [gasCategory, generalCategory]
}
