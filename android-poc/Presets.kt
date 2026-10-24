package com.shj.saxble

/**
 * A preset step: a literal command line, or a pause to prompt the engineer for
 * the system Location mid-sequence. Ported from the iOS app's Presets.swift.
 */
sealed class PresetStep {
    data class Command(val line: String) : PresetStep()
    object PromptLocation : PresetStep()
}

data class Preset(val name: String, val steps: List<PresetStep>)

object Presets {
    /** 2-gas panel: O2 (1), Vacuum (3); gases 2, 4, 5, 6 disabled. */
    private val twoGas = listOf(
        PresetStep.Command("gas 1 type O2"),
        PresetStep.Command("gas 2 off"),
        PresetStep.Command("gas 3 type VAC"),
        PresetStep.Command("gas 4 off"),
        PresetStep.Command("gas 5 off"),
        PresetStep.Command("gas 6 off"),
        PresetStep.Command("gas a press_on"),
        PresetStep.Command("password MMSmms659"),
        PresetStep.PromptLocation,
        PresetStep.Command("gas a list"),
        PresetStep.Command("settings"),
    )

    /** 3-gas panel: O2 (1), Medical Air (2), Vacuum (3); gases 4, 5, 6 disabled. */
    private val threeGas = listOf(
        PresetStep.Command("gas 1 type O2"),
        PresetStep.Command("gas 2 type MA_4"),
        PresetStep.Command("gas 3 type VAC"),
        PresetStep.Command("gas 4 off"),
        PresetStep.Command("gas 5 off"),
        PresetStep.Command("gas 6 off"),
        PresetStep.Command("gas a press_on"),
        PresetStep.Command("password MMSmms659"),
        PresetStep.PromptLocation,
        PresetStep.Command("gas a list"),
        PresetStep.Command("settings"),
    )

    val all = listOf(
        Preset("2-Gas O2/Vac", twoGas),
        Preset("3-Gas O2/MA4/Vac", threeGas),
    )
}
