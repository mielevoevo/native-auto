package com.nativeauto.changelog

/** One release in `assets/changelog.md`: `## <name> (<code>) · <date>` and its bullets. */
data class ChangelogEntry(
    val versionName: String,
    val versionCode: Int,
    val date: String,
    val bullets: List<String>,
)

private val HEADER = Regex("""^##\s+(\S+)\s+\((\d+)\)\s*[·\-]\s*(.*)$""")

/**
 * Reads the changelog text. A header that doesn't match, or a bullet before the first header, is
 * skipped: a typo in the file must never break the app. Newest first (by version code).
 */
fun parseChangelog(text: String): List<ChangelogEntry> {
    val entries = mutableListOf<ChangelogEntry>()
    var name = ""
    var code = -1
    var date = ""
    var bullets = mutableListOf<String>()
    fun flush() {
        if (code >= 0) entries += ChangelogEntry(name, code, date, bullets.toList())
        code = -1
        bullets = mutableListOf()
    }
    text.lineSequence().map { it.trimEnd() }.forEach { line ->
        when {
            line.startsWith("## ") -> {
                flush()
                HEADER.matchEntire(line)?.let {
                    name = it.groupValues[1]
                    code = it.groupValues[2].toInt()
                    date = it.groupValues[3].trim()
                }
            }
            line.startsWith("- ") && code >= 0 -> bullets += line.removePrefix("- ").trim()
        }
    }
    flush()
    return entries.sortedByDescending { it.versionCode }
}

/** The sheet after an update shows at most this many versions. */
const val MAX_SHEET_ENTRIES = 3

/**
 * What the sheet after an update shows: entries newer than [lastSeen] and not newer than the
 * running build, newest first, at most [max]. Nothing while [deferred] (a trip or navigation).
 */
fun entriesToShow(
    all: List<ChangelogEntry>,
    lastSeen: Int,
    current: Int,
    deferred: Boolean,
    max: Int = MAX_SHEET_ENTRIES,
): List<ChangelogEntry> =
    if (deferred) emptyList() else all.filter { it.versionCode in (lastSeen + 1)..current }.take(max)

/**
 * The last version code the rider has seen the changelog for. A fresh install counts as having
 * seen the running build (the welcome of Phase 16 covers it); an update from a build that had no
 * changelog is [BEFORE_CHANGELOG], so the update's entries show once.
 */
fun resolveLastSeen(stored: Int?, freshInstall: Boolean, current: Int): Int = when {
    stored != null -> stored
    freshInstall -> current
    else -> BEFORE_CHANGELOG
}

/** v3.3 (versionCode 18) was the last build without a changelog. */
const val BEFORE_CHANGELOG = 18
