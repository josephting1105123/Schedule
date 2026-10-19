# Schedule

An Android app that reads the calendars already synced to your phone (Google and others) and shows
them in one place. It has no account of its own.

- **Today** — an hour grid, clashes, tasks and birthdays
- **Week** — a timetable
- **Month** — a full-page grid or a list
- **Widgets** — one for today, one for the month
- Edits and new events are written back to the same calendars.

## Install

Download `Schedule.apk` from the [latest release](https://github.com/josephting1105123/Schedule/releases/latest)
and open it on your phone. It needs Android 10 or later, and you will have to allow installs from
unknown sources for your browser or file manager.

## Build from source

You need JDK 21 and the Android SDK.

```bash
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest
```

A release build is signed with the debug key unless `SIGNING_STORE_FILE`, `SIGNING_STORE_PASSWORD`,
`SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD` are all set in the environment.

See `NOTICE.md` for the terms that apply to the optional on-device Gemma model.

## About this repository

This is a public copy of a private repository, so changes are ported in by hand.

## Licence

MIT — see `LICENSE`.
