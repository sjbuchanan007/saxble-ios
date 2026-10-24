import Foundation

/// A preset step: either a literal command line, or a pause to prompt the
/// engineer for the system Location mid-sequence (ported from presets.cpp).
enum PresetStep: Hashable {
    case command(String)
    case promptLocation
}

struct Preset: Identifiable, Hashable {
    let id = UUID()
    let name: String
    let steps: [PresetStep]
}

enum Presets {
    /// 2-gas panel: O2 (1), Vacuum (3); gases 2, 4, 5, 6 disabled.
    private static let twoGas: [PresetStep] = [
        .command("gas 1 type O2"),
        .command("gas 2 off"),
        .command("gas 3 type VAC"),
        .command("gas 4 off"),
        .command("gas 5 off"),
        .command("gas 6 off"),
        .command("gas a press_on"),
        .command("password MMSmms659"),
        .promptLocation,
        .command("gas a list"),
        .command("settings"),
    ]

    /// 3-gas panel: O2 (1), Medical Air (2), Vacuum (3); gases 4, 5, 6 disabled.
    private static let threeGas: [PresetStep] = [
        .command("gas 1 type O2"),
        .command("gas 2 type MA_4"),
        .command("gas 3 type VAC"),
        .command("gas 4 off"),
        .command("gas 5 off"),
        .command("gas 6 off"),
        .command("gas a press_on"),
        .command("password MMSmms659"),
        .promptLocation,
        .command("gas a list"),
        .command("settings"),
    ]

    /// 6-gas panel: O2 / Medical Air / Vacuum repeated (1-3 and 4-6).
    private static let sixGasDual: [PresetStep] = [
        .command("gas 1 type O2"),
        .command("gas 2 type MA_4"),
        .command("gas 3 type VAC"),
        .command("gas 4 type O2"),
        .command("gas 5 type MA_4"),
        .command("gas 6 type VAC"),
        .command("gas a press_on"),
        .command("password MMSmms659"),
        .promptLocation,
        .command("gas a list"),
        .command("settings"),
    ]

    /// 6-gas panel: O2 (1), N2O (2), ENT (3), Medical Air (4), Surgical Air (5),
    /// Vacuum (6).
    private static let sixGasFull: [PresetStep] = [
        .command("gas 1 type O2"),
        .command("gas 2 type N2O"),
        .command("gas 3 type ENT"),
        .command("gas 4 type MA_4"),
        .command("gas 5 type SA_7"),
        .command("gas 6 type VAC"),
        .command("gas a press_on"),
        .command("password MMSmms659"),
        .promptLocation,
        .command("gas a list"),
        .command("settings"),
    ]

    static let all: [Preset] = [
        Preset(name: "2-Gas O2/Vac", steps: twoGas),
        Preset(name: "3-Gas O2/MA4/Vac", steps: threeGas),
        Preset(name: "6-Gas O2/A4/Vac ×2", steps: sixGasDual),
        Preset(name: "6-Gas O2/N2O/ENT/MA4/SA7/Vac", steps: sixGasFull),
    ]
}
