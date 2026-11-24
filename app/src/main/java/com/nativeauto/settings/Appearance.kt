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

/**
 * 12a (Rider-Feedback R4): how the display's refresh rate follows what's on screen. In every mode
 * a move (cards sliding, a drag, the map's camera) gets the fastest rate at once; they differ in
 * what happens when nothing moves. Nothing is ever redrawn just because the rate is high.
 */
enum class RefreshMode(val label: String) {
    /** Idle: the system's own choice (it saves power and still speeds up touch scrolling). */
    AUTO("Auto"),
    /** Idle: the lowest rate the display offers, quickly. */
    POWER_SAVER("Power saver"),
    /** The fastest rate the whole time the app is open. */
    SMOOTH("Smooth"),
}

/** 4.6.2 (rider): when the greeting sheet shows. [EVERY_OPEN]: see `GreetingViewModel.AWAY_MS`. */
enum class GreetingMode(val label: String) {
    ONCE_A_DAY("Once a day"),
    EVERY_OPEN("Every open"),
    OFF("Off"),
}

/** The stored choice, else what the 4.3c switch said (off stays off; on or never touched = once a day). */
fun greetingModeFrom(stored: String?, dailySwitch: Boolean?): GreetingMode =
    GreetingMode.entries.firstOrNull { it.name == stored } ?: if (dailySwitch == false) GreetingMode.OFF else GreetingMode.ONCE_A_DAY

/** 12a: how long a card is held before it lifts to be dragged (R3). */
val DRAG_HOLD_CHOICES_MS = listOf(1_000L, 2_000L, 3_000L)
const val DEFAULT_DRAG_HOLD_MS = 1_000L

/**
 * 11c: the one value a normal-size Garage card shows big (Rider-Feedback §6 "Where it lives"),
 * chosen in the maximized card. Range by default.
 */
/** 12b (R2): the log face's distance total. A week starts on Monday; a month is the calendar month. */
enum class TotalPeriod(val label: String, val lead: String) {
    DAY("Day", "Today"),
    WEEK("Week", "This week"),
    MONTH("Month", "This month"),
}

enum class GarageValue(val label: String) {
    RANGE("Range"),
    SINCE_REFUEL("Since fill-up"),
    ODOMETER("Odometer"),
    NEXT_SERVICE("Next service"),
    KM_PER_L("km/L"),
}

/**
 * 14b: which way the dashboard faces. [LANDSCAPE] (default, and what every earlier install had) and [PORTRAIT] are fixed:
 * the sensor and the system's rotation lock are ignored. [AUTO] follows the phone's own auto-rotate switch (lock on =
 * the app stays as it is) and, when unlocked, turns only after a steady, generous tilt ([OrientationPolicy]).
 */
enum class OrientationMode(val label: String) {
    LANDSCAPE("Landscape"),
    PORTRAIT("Portrait"),
    AUTO("Auto"),
}

/** 4.3b: card headers show the pane's name (default) or its icon (the pane picker's). */
enum class CardTitleStyle(val label: String) {
    TEXT("Text"),
    ICON("Icon"),
}

/** 13a: how the Garage card draws the tank: the five-bar gauge (default) or a fixed pump icon. */
enum class TankIcon(val label: String) {
    GAUGE("Gauge"),
    PLAIN("Plain"),
}
