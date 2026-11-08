package com.nativeauto.settings

/** Theme setting (8c). OLED dark is the default: a dashboard is mostly used at night. */
enum class ThemeMode(val label: String) {
    DARK("Dark"),
    LIGHT("Light"),
    SYSTEM("System"),
}

/** The clock pane's face (8c), chosen in the pane itself. */
enum class ClockFace(val label: String) {
    DIGITAL("Digital"),
    ANALOG("Analog"),
}

/** Reads a stored enum name, falling back when it's missing or from a removed entry. */
internal inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
