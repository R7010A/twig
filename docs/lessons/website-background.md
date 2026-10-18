# Website background execution

> One section of Twig's hard-won lessons. See [AGENTS.md](../../AGENTS.md).

## Music stops in the background and resumes when the page returns (2026-10-18)

The page stayed loaded, but music stopped after some time with the screen locked or
another app open. Merely skipping `WebView.onPause()` for opted-in websites did not
resolve it. That change only removes an explicit WebView pause; it does not establish
an Android background execution session.

Read-only system event logs on the Android 16 test device contained repeated
`am_freeze [pid,com.twig.app]` / `am_unfreeze` pairs. This supports cached-process
freezing as a cause, rather than proving that WebView was destroyed. Android's
[cached-app freezer](https://source.android.com/docs/core/perf/cached-apps-freezer)
keeps processes in RAM while suspending their threads; returning to the Activity
unfreezes them. Do not disable the device-wide freezer to fix an app lifecycle bug.

An explicitly opted-in, live website session now holds `WebsiteBackgroundService`, a
`specialUse` foreground service for general website tasks with its own notification/channel. Start it from
`DefaultLifecycleObserver.onResume`, when Lifecycle is actually RESUMED: checking
that state from inside Activity.onResume can be too early, and silently skips start.
Backgrounding does not release the session. Closing the page, disabling the option,
removing its document task, or closing websites from the notification releases it.
Multiple website pages share the service; closing one must not stop the others.
Detaching a stopped service before requesting its shutdown avoids losing a new
session opened while the old service is being destroyed.

This is session-based and applies to general page tasks as well as media. The
notification remains until the page/session is closed, even without audio playing.
Use the general special-use type and declare its website-session purpose in the
manifest; a media-only type would misrepresent non-media page tasks. Store the option
as allowBackgroundRunning, defaulting to false when absent. Keep the notification
channel ID stable so users retain their notification settings. No page scripts,
MediaSession emulation, extra wake lock, or automatic page restoration are involved.
Use START_NOT_STICKY: a restarted process has no live authenticated WebView to retain.
Notifications carry names and bookmark IDs, never URLs, headers or credentials.

Tests cover real Activity lifecycle transitions, foreground type/notification,
multi-page cleanup, task removal, stale shutdown, orphan starts, and API 24. Those
checks establish lifecycle behavior; they do not measure continuous playback or
freezing on a real device. Verify the affected site's lock-screen playback separately.
