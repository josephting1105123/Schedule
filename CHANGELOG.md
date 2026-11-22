# Changelog

The newest version is at the top.

## 1.4.13

- Tasks can repeat. Open a task and set Repeats to daily, weekly, monthly or yearly, with an interval and an optional end, using the same picker as events.
- Ticking a repeating task moves it to its next date instead of finishing it. If it was overdue, it moves to the first date on or after today, so one tick catches it up. Once the series has ended, ticking finishes it as before.
- The repeat syncs to Jarvis through the Tasks calendar (needs Jarvis 0.0.60 or later; an older Jarvis drops the rule when it edits the task).

## 1.4.12

- Changing one occurrence of a repeating event ("This event") works again for events that have a colour, including changing the colour itself. The calendar used to refuse it with "The calendar rejected the change."

## 1.4.10

- Changing a repeating event with "This and following events" no longer leaves the old times next to the new ones. When the edit moved the start to an earlier date, the old series kept running up to the occurrence you opened, so those days showed the event twice. The old series now ends before the earlier of the two days.
- A repeating event with a fixed number of times keeps the right number after "This and following events", instead of repeating forever.

## 1.4.2

- After you change an event, Schedule asks Google to upload only the account that has the change, instead of syncing every account starting with the default one. For this to work on a Google account, Schedule needs your permission once: tap **Let Schedule sync it directly** on the account in Settings → Sync with Google, or pick it when adding the account.
- The sync card shows how long each account's last upload took, and how long a change has been waiting. Over 30 seconds is marked in amber.
- Sync no longer piles up while the app is open. Schedule used to ask every Google account on the phone to sync every 20 seconds, and the phone runs those one at a time, so a change could wait minutes for its turn. It now asks only the accounts you added, and only when they aren't already syncing.

## 1.4.0

- Clock times follow your phone's 12- or 24-hour setting: Today, the hour sheet, the event and task editors and the Today widget.
- Deleting or editing a birthday while the calendar can't be reached now tells you so, instead of the birthday quietly coming back or the edit reverting.
- A damaged tasks, birthdays, colours or settings file no longer crashes the app. That part starts fresh, and tasks and birthdays refill from Google.
- Backups no longer carry phone-specific calendar links (birthdays, colours, accounts) to a new phone, where they could point at the wrong event.
- TalkBack reads the task's name on its checkbox, and a task's weekday follows your phone's language.
- Today and Week do less work on every redraw.
- The task sync logs failed calendar writes and waits longer between retries of one that keeps failing.
- The widgets refresh once every half hour instead of twice.
- All of the app's text now lives in resource files, ready for translation.

## 1.3.12

### Sync
- Changes you make now keep trying to reach Google after you leave the app. If they're still waiting, the phone asks again in the background (when it's online) until they're uploaded, and checks every 15 minutes in case anything is left behind.
- Each change asks your Google accounts to sync straight away, naming the account directly where Android allows it.

### Look
- The "+" on the month widget sits in the middle of its button, and the button no longer touches the widget's edge.

## 1.3.11

### Privacy
- Schedule no longer asks for internet access. It only ever used it for the assistant, which was
  already switched off, so nothing you can see changes. The app now has no way to send anything
  off your phone.
- The unused assistant code is gone, along with any Gemini key or model setting an older version
  saved.

## 1.3.10

### Look
- Drop-down menus (Show task, Calendar, Repeats, and the others) are white cards with rounded
  corners instead of the pale purple boxes. The one already chosen is highlighted in blue with a
  tick, and the menu opens under the value you tapped rather than over the field's name.

## 1.3.9

### Today
- An event an hour long or shorter now shows its name on the day grid. It used to show a blank
  bar, because the name needed more room than one hour's cell has.
- When two events clash, the hours they share grow taller to hold both bars, instead of a second
  row of cells appearing underneath.

## 1.3.7

### Look
- New app icon: a calendar page holding three events, drawn the way Today draws them, on the
  app's blue. Themed icons on Android 13+ get a matching outline.

## 1.3.6

### Tasks
- A task has a **Show task** setting: *Same day* (the default) shows it on Today only on its due
  date, *1 week before* shows it on each of the seven days before too, and *Until task finished*
  keeps it on Today every day until you tick it off. The setting syncs with Jarvis.
- "Due at a specific time" is a switch instead of a checkbox.

## 1.3.5

### Colours
- New palette: a softer off-white background, darker ink text and a bright blue accent.
- Events are drawn as a pale wash of their colour with a solid stripe down the left edge and dark
  text, on Today, Week and Month.
- Today's hour grid puts each hour's number inside its cell, with the events in a bar beneath.

## 1.3.4

### Tasks
- Tasks sync with Jarvis on the PC. Create a calendar called **Tasks** in Google Calendar; the app
  keeps one event per task there, and Jarvis does the same, so a task added, edited, ticked or
  deleted on one shows up on the other. The Tasks calendar never shows as events here, so tasks
  still never count as busy.

## 1.3.3

### Today
- Each task is its own card: a date badge on the left (red when overdue, amber when due today),
  the title and its time in the middle, and the tick box on the right.

## 1.3.2

### Accounts
- Settings has an **Accounts** card. Add the phone accounts you want the app to use and give each
  a short name, so "Personal" shows instead of a long email. With none added, every account shows,
  as before.
- A three-dots button on Today, Week and Month opens a list of your accounts, their other
  calendars (Formula 1, Family…) and Birthday. Untick one to hide it everywhere, widgets included.
  A hidden calendar still counts as busy when the editor checks for clashes.
- The calendar picker in the event editor and in Settings is grouped by account and names
  calendars by their short names, instead of listing every email twice.

### Month
- The header switch now changes between the grid and the list. Birthdays show on the grid and in
  the list next to events, in pink, instead of on a separate birthday calendar.

### Birthdays
- Birthdays from Google Contacts can be hidden from their editor; Settings can show them again.
- Birthdays from a calendar whose sync is switched off, or from an account you haven't added, are
  no longer shown.

### Sync
- The **Sync with Google** card folds to one line; tap the arrow for each account's details.

## 1.3.1

- A malformed date sent to the app by another app is ignored instead of crashing it.

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
