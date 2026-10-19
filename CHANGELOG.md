# Changelog

The newest version is at the top.

## 1.3.0

### Month
- The month fills the page, as in Google Calendar: tall days with every event named, timed ones
  included, and `•••` when a day holds more. The button in the header switches to the list of the
  month's days and back.

### Sync
- Pull to refresh asks Google again 4, 10 and 20 seconds after the pull, so an edit made a moment
  ago in Google Calendar arrives once it has reached Google, instead of a minute or more later.
- The app also asks Google to sync every time it is opened and every 20 seconds while it is on
  screen (it used to ask at most once a minute).

### Widget
- The day pop-up fades and shrinks away when closed instead of vanishing at once.

## 1.2.0

### Month
- Long-press a day for its agenda in a pop-up over the page, with **Open day** and **Add event**.
  Tap an event in it to edit it. In birthday mode it lists that day's birthdays.
- Tap the month's name to come back to this month after browsing.
- The greyed days from the months either side open when tapped, like every other day.
- TalkBack can now reach each day of the grid (date, today, how many events), with tap and
  long-press actions.

### Widget
- Tap a day for its agenda in a pop-up over the home screen; **Open day** goes on to Today.
  (A launcher keeps long-press on widgets for moving and resizing, so the tap does it.)
- Only all-day and multi-day events are named; timed events are coloured dots, since a widget
  day is too short to hold their names — no more names cut down to "Churc…".
- Tap the month's name to come back to this month after browsing.

## 1.1.0

### Week (new tab, Sunday to Saturday)
- A timetable: a column per day, the full day 0000–2359, each event a block as tall as it lasts.
- Blocks show as much as fits — the whole title on a long block; the place, then the end time, give
  way on a short one. Times read `1300 / - / 1400`, everything centred, long words hyphenated.
- Events at the same time sit side by side in their day's column.
- The dates stay pinned at the top while the page scrolls; each week opens on its first event.
- Tap a block for its details and notes (Edit from there); tap empty space to add an event at that
  day and hour.
- All-day and multi-day events and birthdays are listed under the timetable. The header button
  folds the timetable down to its dates.

### Today
- An event running over several hours is one bar, with its title centred in it.
- *Day at a glance* stays pinned at the top while the rest scrolls.
- Tapping an hour shows "Slot: 0900 - 1000" with each event's notes in full, and no calendar email.
- Pull down to refresh: asks your accounts to sync; the spinner shows for at least a second.
- **+ → Event** adds to the day you are looking at, not always to today.

### Month
- Much smoother swiping between months; neighbouring months are ready before you reach them.
- Only all-day and multi-day events are named on the grid; timed events are coloured dots.
- The calendar card floats over the list and folds into its header button.
- Tapping a day opens that date on Today.

### Widget
- The month widget has a new look: dark plum card, month name, ‹ › arrows, a `+` pill, a ruled grid,
  and `•••` for days with more than fits. Changing month is instant.
- Both widgets update whenever your calendar changes.

### Fixes
- Pull to refresh and saving an event now really ask Google to sync, for every account, with no
  account to pick first.
- Changing the time of one occurrence of a repeating event ("This event") works — it used to fail
  with "Exceptions can't overwrite dtend".
- An all-day event no longer also shows on the day after it ends.
- The *Automatic* colour circle shows the colour the event will actually get, not always blue.
- Today's top card shows the event happening now, how long is left and what comes next — it
  used to say "The rest of the day is yours" in the middle of an event.
- A repeat's end date can be set to any day from the series' first — before, editing a later
  occurrence quietly moved an earlier end date to that occurrence. Choosing "On…" now opens the
  calendar straight away.

### Everywhere
- Changes you make are sent to Google straight away, and opening the app fetches new ones.
- Settings → *Sync with Google* shows whether each account is set to sync and how many changes
  are waiting to upload, with *Sync now* and a shortcut to the phone's sync settings.
- Event colours are Google Calendar's own, and a colour you pick here is the same colour in Google
  Calendar on every device. The picker offers the 11 that other apps can set; any of Google's 24
  picked in Google Calendar shows here and is kept when you edit the event. Events you coloured
  earlier in this app show Google's colour for them.
- Light theme only; events with no colour get automatic colours from Google's own set, different
  for each event in a day.
- New app icon.
- The event, task and birthday forms keep the field you are typing in above the keyboard.
- The Assistant tab and the Gemini API key are removed.
