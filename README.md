# Found

Realtime lyrics that float above everything else.

Found watches whichever app is playing music, fetches the lyrics, and shows
them in a small bubble in the top corner of the screen — with a karaoke sweep
that follows the audio, in the colour of the album art.

## What it does

- **Floating bubble**, fixed in the top corner (never draggable). It expands
  into a card while something plays and collapses into a thin pill when paused
  (or when you tap it, to fold it away yourself).
- **Two lyric lines**: the line being sung plus the one coming next.
- **Karaoke sweep**: the current line fills with the accent colour as it is
  sung. When the lyrics source has word-level timings, the accent follows the
  words instead.
- **Any player**: Found reads the active Android media session, so it works
  with Spotify, YouTube Music, VLC and anything else that publishes one.
- **Offline**: lyrics are downloaded once and cached in app storage. The next
  time you play that track, it works with no network at all.
- **In the notification shade** too, with play/pause, next and previous.
- **Accent from the cover art**: the bubble takes its colour from the album
  artwork.
- **Customisation**: text size, corner, auto-collapse, and toggles for the
  bubble and for the lyrics in the notification.

## Permissions it needs

| Permission | Why |
|---|---|
| Display over other apps | The floating bubble. |
| Notification access | To read the active media session (title, artist, position). |
| Notifications (Android 13+) | To show the lyrics in the shade. |
| Internet | To look lyrics up from LRCLIB. |
| Foreground service | The bubble keeps running while something plays. |

Nothing is uploaded. The app only fetches lyrics; it never sends anything about
what you play.

If you revoke the display-over-other-apps permission while Found is running,
the bubble is taken down and the service stops.

## Lyrics source

[LRCLIB](https://lrclib.net/) — an open, free lyrics database that serves
timed (LRC) lyrics. The lookup is by track title, artist and duration, with a
search fallback when there is no exact match. The result is cached on disk
under a key made of artist, title and duration, so the same track is only ever
looked up once.

## Building

The project builds with Gradle. There is no wrapper checked in, so use a
Gradle 8.7 installation (or add one):

```bash
gradle :app:assembleDebug      # build the APK
gradle :app:testDebugUnitTest  # run the unit tests
```

GitHub Actions builds the APK on every push to `main` and publishes it to the
`latest` prerelease, which is what the download link on the releases page points
at.

## Layout

```
app/src/main/java/dev/found/lyrics/
├── MainActivity.kt              settings screen
├── FoundSettings.kt             preferences
├── lyrics/                      LRC parsing, sweep maths, LRCLIB lookup, offline cache
├── nowplaying/                  media session listener → NowPlaying
├── notify/                      the notification version of the lyrics
├── overlay/                     the bubble: service, bubble view, karaoke text
└── util/                        colour extraction from the cover art
```
