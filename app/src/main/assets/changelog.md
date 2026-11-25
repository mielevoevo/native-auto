# What's new in Native Auto

## 4.7.3 (39) · 2026-11-26
- Portrait: in the slim speed card beside a maximized card, today's distance is no longer cut off. The figures now sit beside the buttons.

## 4.7.2 (38) · 2026-11-25
- Back up before you install this one: the database changes, so you can't go back to an older version without losing the app's data. Your bikes, trips and fill-ups carry over.
- Trips now save the altitude at every point, also when a trip is recovered after the app was closed by the phone. The range doesn't use it yet; a later version learns from it.
- Backups now include fuel level entries and the dash km/L. Older backups still import.
- Odometer: Recorded on another phone. When someone rides your bike with the app on another phone, turn it on when you enter the odometer. The distance counts as normal riding, so the tank still teaches km/L.
- Importing another phone's backup also updates a service that was done later there.

## 4.7.1 (37) · 2026-11-25
- Fixed: dropping a card onto the call card during a call threw that card away and left an empty call card behind, even after restarting. Layouts damaged this way are repaired when the app opens; if the wrong card came back, hold it and pick another.
- The call card can be dragged by its top strip. The card it stands in for moves with it and comes back there after the call.
- The call card skips texts like "Incoming call" when looking for the caller's name, and also reads the name from the phone's own dialer. Caller name (Settings, Display) hides it.

## 4.7 (36) · 2026-11-24
- Back up before you install this one: the database changes, so you can't go back to an older version without losing the app's data. Your bikes, trips and fill-ups carry over.
- Garage: more bikes to pick from: Vario 110, 125, 150 and 160, BeAT, PCX 150 and 160, beside both NMAX. A preset only fills in the tank, reserve and gauge; Other bike keeps what you type.
- Fuel gauge bars are set per bike, 4 to 12 (Garage, Edit). Both NMAX keep 6 and 8; on the others count the bars on your dash. Set fuel level shows that many.
- Fine-tuning (Garage, Edit): the starting km/L and reserve, and the odometer vs GPS, if you know your own figure. Use learned goes back to what the app learned from your corrections. A change counts from then on, so the odometer doesn't jump.
- Garage in portrait: the bike page is back. Since 4.0 the bike cards filled the whole screen and hid it. The odometer keeps to one line.
- Forms in the Garage keep their title row with Save in view while you scroll (landscape).
- Each trip now records crawling: how long and how far you rode under 15 km/h, and the climb meanwhile. The trip's page in the history shows it with the stops. The range doesn't use it yet.

## 4.6.4 (35) · 2026-11-24
- Portrait cards have more space above their titles, the same on every card: small cards had the least. Nothing inside the cards got smaller.
- Four stacked cards: the navigation card no longer cuts off its arrival line under a long road name.
- The greeting is larger in portrait: a bigger range and tank, the two figures across the width, and Done along the bottom. Landscape is unchanged.
- Settings: Orientation is now on Display, above the theme. Trip automation is now Trip behavior. A page that doesn't fit fades at the bottom instead of ending mid-row.
- Route color is gone: the route line always follows the accent color. If you had picked a separate route color, it is no longer used.
- The clock shows the date on four stacked cards too.
- All text is in American English. Service items you already named keep their names.

## 4.6.3 (34) · 2026-11-24
- Portrait cards share one set of sizes, so the same kind of text is the same size on every card. Larger: the Garage value, the distance to the turn, track and road names, the trip figures, card titles and icons, album art and Play.
- A half-width Garage card puts the tank above the range, both bigger. A half-width log face gives the trip's time its own line.
- The faint card icon is smaller and sits cut off at the card's right edge, clear of the media and clock buttons.

## 4.6.2 (33) · 2026-11-24
- Settings have four tabs: Trip and Garage, Display, Map and layout, Others. Audio, Backup, Navigation and Privacy and data are gone; everything in them moved.
- Trip and Garage: auto-start, resume, pause and end are together in Trip automation; the distance total and the figures are in Trip figures.
- Display: speed unit and the Call card switch (was Call controls) moved here. Colours is now Accent colour.
- Map and layout: voice guidance, auto layout and the new Route colour row, beside orientation, map width and the hold time.
- Others: greeting, What's new, backup, call permission, app permissions, notification access and Reset app.
- Call permission says whether it is allowed. If Android no longer asks, the button opens the app's page in Android's settings.
- Greeting: Once a day, Every open or Off. Every open skips a quick return (under a minute) and waits during a trip or navigation.

## 4.6.1 (32) · 2026-11-24
- The speed card opens on the log face while you are standing still, ready for Start, and slides to the speed once you ride (10 km/h for 3 seconds). It doesn't slide back at lights or in traffic. Pausing or ending a trip shows the log face again. If you swipe by hand, that face stays until you open the app again or the trip changes.
- Low-fuel warning: choose how many km are left when it turns red, per bike (Garage, Edit: 20, 30, 40, 50 or your own). 30 km by default; it used to be the last fifth of the tank, about 50 km on an NMAX.
- Today's distance is now a tile with the trip figures, so it is no longer cut off on a half-height card. Small log faces label both lines: Trip and Today.
- Four stacked portrait cards have the same space above their titles as the other cards.
- Settings no longer jump around when you scroll past the end of a page. Close them with Done, Back or a tap outside.
- The range no longer follows how you ride for now: it ignored stops and idling, so a traffic jam could have read as easy riding. Trips keep recording what it needs, and it comes back with jams counted.

## 4.6 (31) · 2026-11-24
- The fuel range now learns how you ride. Each trip notes how hard it was ridden (speed, speeding up, climbs), from the GPS fixes it already gets: no extra battery. Once four full-to-full fill-ups have shown how that changes your km/L, the range follows your last ~20 km of riding, at most 20% either way. Until then nothing changes.
- When riding moves the range by 5% or more, the Garage says so under it ("Riding hard: −8%").
- Backups carry the new trip figures; older backups still import.

## 4.5 (30) · 2026-11-24
- Daily greeting: the first time you open the app each day, a short sheet says good morning (or afternoon, or evening) with your bike's range left and tank, the odometer and this week's riding, plus a service if one is due. Tap anywhere to close it; it also goes away by itself once a trip is running. Never during a trip or navigation, and not on the day you set the app up. Turn it off in Settings, Trip and Garage.
- Settings, Display: the theme choice sits beside its label in landscape, so the page fits without scrolling.

## 4.4 (29) · 2026-11-24
- Card titles can be icons: Settings, Display, Card titles, Text or Icon. Icons are the ones in the hold menu, and they leave more room in the header for the clock and battery.
- The media card's previous and next buttons now sit on round plates like play / pause, so you can see where to press.
- Faint card icons (Settings, Display, off by default): a large, dim icon of the card behind big media, clock, navigation and Garage cards. Never on the map, the speed card or a call.

## 4.3 (28) · 2026-11-24
- Settings keep one height whichever tab is open, with more room around the title and tabs. A tab that doesn't fit fades out instead of being cut off.
- Every setting is now one row: title and description on the left, the button on the right. Reset trip, Reset app, Back up and Import follow it too.
- Holding a card: the menu no longer repeats Fullscreen and Maximize where the card has its own buttons. Cards without them (portrait slim rows) still get them in the menu.
- Trip summary: the dash km/L is a tile next to the figures in every layout, so it's easy to hit in Rows too. In Rows the summary now starts right under the card title.
- Low fuel: the red pump now sits in the top bar next to the battery, instead of floating on the speed card.
- Fullscreen navigation: the arrow on the left, distance and road beside it, arrival and End below, like the smaller card.
- Directions say "Slight left / right" instead of "Bear left / right", on screen and spoken, and "Then turn left" is no longer capitalised mid-sentence.

## 4.2.1 (27) · 2026-11-16
- Auto-resume: a paused trip (paused by you or by auto-pause) carries on by itself once you ride steadily again, while the app is open. Settings, Trip and Garage, Trip options: Off, 5, 10 or 20 seconds (10 by default). A short "Trip resumed" note shows when it happens.
- Auto-start now counts riding from 10 km/h (was 15). Underground or indoors it still waits for a good GPS fix, so it starts shortly after you are out in the open.
- Landscape media card: swipe sideways to change track, as on the portrait cards. The skip buttons stay.

## 4.2 (26) · 2026-11-15
- First-run setup: a new install starts with seven short pages. Welcome, Appearance (orientation, theme, units), Location and notifications, Calls, Your bike, Riding, and a summary. Each switch asks Android for its permission right there, and nothing is asked before you tap. Updating from an older version never shows it; everything stays in Settings (and Settings, Privacy and data).

## 4.1.1 (25) · 2026-11-15
- New installs start with the cyan accent. If you picked another colour in Settings, Display, Colours, it stays.
- Settings now open on Trip and Garage, with the Garage button and Reset trip (its note under it) on the left; Trip history, Trip options and Auto-start on the right. Auto-start is a single on/off switch with "Start after" under it.
- Settings, Privacy and data (new): open the system screens for app permissions and notification access, or Reset app (tap twice) to delete everything and start over.
- Settings, Audio: a switch to turn call controls off.
- Portrait: with three or four cards, a maximized card takes the space of two cards and the others shrink to slim rows; with two cards use Fullscreen. The card titles sit a little lower.
- Portrait layout picker: one row per layout, picture on the left and name beside it.
- An empty Garage (new install or portrait) shows a single Add bike button. The model is chosen in the form it opens.

## 4.1 (24) · 2026-11-14
- New app icon: the Native Auto mark (white shape with two cyan stripes) on black. It also has a single-colour version, so Android 13+ themed icons work.

## 4.0 (23) · 2026-11-14
- Portrait: mount the phone upright. Settings, Layout, Orientation: Landscape (as before), Portrait, or Auto, which follows the phone's rotation lock and turns only after a steady tilt. Everyone starts in Landscape.
- Portrait layouts: one card, two, three, "big card + two side by side", or four stacked. Turning the phone keeps the same cards in the same order, and the map and your trip carry on without a reload.
- Small portrait cards: the speed unit always shows; media has art, title and one play button, and you swipe sideways to change track; navigation sits on one centre line (arrow, road, then distance, arrival and time left).
- The small map shows just the recentre button; while guiding it has a small End (tap twice). A fullscreen map has "Where to?" on top and Mute, End, traffic and recentre along the bottom.
- Settings, history, Garage and the forms (refuel, bike, service) now fit a tall screen: bottom sheets with Save kept at the bottom.
- A call over a fullscreen card shows as a small pill at the top with Answer / Decline (or End while you talk).
- "Filled to the top" is now called Full refill.

## 3.5.1 (22) · 2026-11-14
- Calls: when a call rings or is on, a Call card takes the least important card's place (the media card first) and gives it back when the call ends. It never takes over a fullscreen card.
- Answer, Decline, Mute, End and the audio output show only where your phone lets the app do them. It works through the call notification, so it covers the phone app on any brand and calls from WhatsApp, Teams, Zoom and similar.
- Settings, Audio: "Call controls" lets you allow the phone permission, so plain phone calls can be answered and ended from the card too.

## 3.4.3 (21) · 2026-11-14
- Settings are sorted into categories along the top: Display, Layout, Navigation, Trip and Garage, Audio and Backup.
- Colours (Settings, Display): choose the accent from presets or type your own hex code. New installs start green; yours stays cyan until you change it. The route line on the map can follow the accent or have its own colour.
- Backup and restore are in Settings too, not only in the trip history.
- Auto-start has its own place under Trip and Garage; the hold-to-move time is under Layout.

## 3.4.2 (20) · 2026-11-14
- Auto-start (Settings, Trip options): off, odometer only, or start the trip by itself after 30 seconds at 15 km/h. It only works while the app is open and starts fresh each time you open the app.
- When a trip starts by itself the speed card slides in "Trip started", with a short buzz. Swipe it away, or it goes after 3 seconds.
- The small map while navigating is simpler: the route, one slim turn chip, mute and End in its header, and a big follow button.
- Holding a card now also offers Fullscreen and Maximize in its menu.
- Fixed: the max speed no longer lags a moment behind the speed after a start or resume.

## 3.4.1 (19) · 2026-11-14
- What's new: this list. After an update it shows what changed, once. It is also under Settings.
- The Next turn card is now called Navigation, and its title is never hidden by the clock.
- Fewer buttons on the card headers: the swap arrows only where they make sense, the rest by holding the card.
- Fullscreen no longer offers maximize.
- Dropping a card on another one is easier: the card it covers most is the one it swaps with.
- Holding a card has two steps: lifted to move it, then a second ring and a double buzz before you let go to choose what it shows.
- The speed card always opens on the speed, and the dash km/L question can be answered on every card size.
- Garage: Add bike is a card at the end of the list. Pick a gauge or a plain fuel-pump tank icon.
- Refuel: one switch, Filled to the top. Litres can go up to the tank size; a bigger fill than the estimate counts as a full tank.

## 3.3 (18) · 2026-11-09
- True black cards and hold-and-drag to swap cards; the refresh rate follows what is moving.
- Fuel range is cautious (rounded down), you can set the fuel level from the gauge bars, and the dash km/L can be entered after a ride.
- Speed card total by day, week or month.
- Fuel prices can be edited; Pertamax Green added; bike models Gen 1 and Gen 2.

## 3.2.1 (15) · 2026-11-09
- Fixed the release build crashing when it started.

## 3.2 (14) · 2026-11-09
- The Garage card: tank gauge, range and next service, and low fuel and service reminders.
- Services with intervals by distance or date; the Garage is part of the backup.
- The Geist font everywhere.

## 3.1 (13) · 2026-11-09
- Refuel log with km/L and range, costs hidden until you ask for them.
- Add kilometres you rode without the app. A ride lost when the app was killed is recovered.

## 3.0 (12) · 2026-11-09
- Bikes and odometer: several bikes, the odometer estimated from your trips and corrected by your readings.

## 2.8 (11) · 2026-11-09
- Share a place from Google Maps into the app to start a route to it.

## 2.7 (10) · 2026-11-09
- Better place search with TomTom when a key is set.

## 2.6 (9) · 2026-11-09
- Trip history grouped by day, GPX export, and a backup file you can restore.

## 2.5 (8) · 2026-11-09
- Every trip keeps its full track. More trip figures to choose from.

## 2.4 (7) · 2026-11-09
- The speed card has a trip log face; a summary after each trip; closing asks to save.

## 2.3 (6) · 2026-11-09
- Trips start when you press Start. Auto-pause and auto-end for forgotten trips.

## 2.2 (5) · 2026-11-09
- Smoother animations and a faster start.

## 2.1 (4) · 2026-11-09
- Dark and light themes, a clock card with digital and analog faces, and a clock in the corner.

## 2.0 (3) · 2026-11-08
- Layouts: Duo, Side by side, Rows and Quad, with Auto layout while you navigate.
