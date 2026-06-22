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
    ]

    static let all: [Preset] = [
        Preset(name: "2-Gas O2/Vac", steps: twoGas),
        Preset(name: "3-Gas O2/MA4/Vac", steps: threeGas),
    ]
}
