# Native Auto design checklist

The review step for every UI change (from Phase 4.3 on). Adapted by hand from two public rulebooks, **antislop**
(miqdadbadjuber/anti-slop: tiered rules + a PASS/FAIL report) and **impeccable** (pbakaus/impeccable: a scored critique,
"polish / clarify / harden" passes). Neither is installed; only the ideas are taken, rewritten for a glanceable,
gloved, motorcycle dashboard in Jetpack Compose.

How to use: before a sub-phase is committed, go through **Gates** (all must pass), then **Consistency**, then score the
changed screens with the **Critique**. Write the result in that sub-phase's entry in `DECISIONS.md` (a few lines).
Never a blocking question during an unattended run: a fail is fixed, or logged with its reason.

## 1. Gates (must pass)

- **G1 · Glance.** The main value on each card reads in under a second at arm's length: the biggest thing on the card
  is the thing the rider needs. Nothing else competes in size or colour.
- **G2 · Gloves.** Every touch target ≥ 56 dp in landscape (≥ 48 dp only in portrait Mini rows), ≥ 8 dp apart. The
  visible shape shows the whole touch area (no icon floating in an invisible 56 dp box).
- **G3 · Contrast.** Text ≥ 4.5:1 against its real background (≥ 3:1 for ≥ 24 sp and for icons). Check both themes and the
  chosen accent (`contrastRatio`).
- **G4 · Honest state.** Every number says what it is and how sure it is (estimate, cautious range, no data). No fake
  precision, no placeholder value that looks real.
- **G5 · Works.** Built and run on the emulator; every changed control was tapped in both orientations. A screen that
  can't be reached isn't done.
- **G6 · Riding safety.** Nothing new animates, pops up or asks for input while a trip runs, unless it is the thing the
  rider asked for (call, turn). Dismissal is always one tap or swipe.

## 2. Consistency locks

- **C1 · Tokens only.** Colours from `AppTheme.colors`, type from the theme styles (Geist; bare `TextStyle` sets
  `fontFamily = Geist`). No literal colours or sizes in screens.
- **C2 · One accent per card.** Amber and red are status only. The accent marks the single most important
  live element, never decoration.
- **C3 · Spacing scale.** 4 / 8 / 12 / 16 / 24 / 32 dp only. Card padding 16 dp, between rows 12 dp, between
  sections 24 dp. Sheets and dialogs use the same insets on every page.
- **C4 · One row pattern** for settings and dialogs: title (`titleMedium`) + description (`bodyMedium`, `textDim`) on the
  left, the control on the right, vertically centred, same minimum height. Destructive actions in the same pattern,
  armed by two taps.
- **C5 · Same thing, same look.** A button that does the same job looks the same everywhere (shape, plate, size class).
  A card's forms (Compact / Wide / Expanded / Fullscreen) are one arrangement scaled, not different layouts.
- **C6 · Sheets.** One height per sheet type (it does not jump when tabs change); title, tabs and content on a fixed
  rhythm; nothing cut off mid-word (fade or scroll instead).
- **C7 · Icons.** One icon set (Material Symbols, rounded), one stroke weight, sizes 24 / 32 / 48 dp.

## 3. Anti-slop list (things that make it feel generated)

- Text stacked in one centred column when the card is wide. Use the width.
- Several things competing for "most important" (two accents, two big numbers).
- Labels that repeat what the icon or the value already says. Decorative dividers, shadows or gradients with no job.
- Empty space that isn't a choice: content sunk to the bottom, a header taller than its content.
- Generic copy: "Oops!", "Something went wrong", exclamation marks, title-case mid-sentence ("Then Turn left"),
  odd words for the context ("bear left"). Write like a riding friend: short, plain, specific.
- Controls offered twice in the same place (e.g. Fullscreen on the card and again in the picker).
- Inconsistent capitalisation (sentence case everywhere), units missing or in a different style.

## 4. Copy rules

- Sentence case. No exclamation marks. Numbers as digits, unit after a thin gap ("18 km").
- Buttons say the action ("Back up", "Reset trip"), not "OK" / "Submit".
- A description says what happens and its consequence in one line ("Discards this trip without saving").
- Errors say what to do next.

## 5. Critique (score each changed screen 0–3)

| | 0 | 3 |
|---|---|---|
| Hierarchy | everything the same weight | one clear focus, then the rest |
| Glanceability | needs reading | read in a glance on the bike |
| Consistency | own rules | follows C1–C7 |
| Touch | small or ambiguous targets | big, clearly bounded |
| Copy | generic or wordy | short, plain, specific |
| Calm | busy, decorative | only what earns its place |

Below 2 on any line → fix it before the commit, or log why in `DECISIONS.md`.

## 6. Passes (from impeccable's vocabulary)

- **Clarify:** remove one thing from each screen that doesn't earn its place.
- **Polish:** alignment to one edge per block, spacing on the scale, optical centring of icons.
- **Harden:** long names, large font scale, both themes, both orientations, empty and no-data states.
