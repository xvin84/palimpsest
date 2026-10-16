# Changelog

**English** | [Русский](CHANGELOG.ru.md)

## 0.3.2 — 2026-10-16

### New
- **Choose the lyrics source**: the «Source: …» chip above the lyrics lists every variant found — the tag, a `.lrc`,
  Yandex, LRCLIB, NetEase and Genius — marked synced or plain, with the first lines. A hand-picked text stays put.
- **Video quality**: Standard · 1080p · 30 fps, High · 1080p · 60 fps, Max · 1440p · 60 fps. Mode, format and quality
  are remembered, so the next render starts from them.

### Changed
- Synced lyrics always win: if the tag holds plain text and a timed version exists elsewhere, the timed one is used.
  With no lyrics or only plain text, the app looks for synced lyrics by itself when the track opens.
- Screens just slide up and down again — no blur, no "sucking in". Predictive back is off.
- Video lyrics are bolder with a soft shadow, like subtitles; the sung line takes the track's colour.

### Fixed
- Renders could stop with "Muxer error: no output sample written in the last 10000 milliseconds" when the phone paused
  background work. Rendering now keeps the CPU awake, tolerates pauses and retries once.
- The My Wave card only showed up after scrolling the library up.
- A long sung line in the player ran off the edge of the screen.

## 0.3.1 — 2026-10-16

### Changed
- Lyrics are translated with Yandex Translate first (reachable from Russia without a VPN); Google is the fallback.
- The Original · Both · Translation switch is a compact centred control that hides after 3 seconds and comes back
  when you touch the lyrics. Under it you see what's going on: translating, already in your language, or why it failed.
- In lyrics videos lines change smoothly: the current one grows and lights up, the previous one dims, the column
  glides without jumps; before the song starts, the first line waits below the centre and eases in.

### Fixed
- After swiping the player down, the library flashed blurred for half a second.

## 0.3.0 — 2026-10-16

### New
- **Track videos**: render a whole track to MP4 — the audio straight from the file through your EQ, ReplayGain and limiter
  (no microphone, no noise), the player's visualiser and either the cover or the synced lyrics scrolling along.
  Vertical 9:16 and horizontal 16:9, rendered in the background with progress, saved to Movies/Palimpsest.
- **Language**: English and Russian, switchable in Settings (or in Android's per-app language settings).
- **Themes**: system, dark, AMOLED black and light.
- **Storage**: how much music, old versions, the streaming cache, covers, translations and backups take, with cleanup
  buttons and a streaming cache limit.
- **Lyrics from NetEase** (synced) and **Genius** (plain) besides LRCLIB and Yandex; synced lyrics are looked for in every source first.
- **Self-update from GitHub**: a daily check, a notification, download and install over the current version.
- **Share the file**: besides the card, send the track itself, or both.
- **Download notification** shows the track and its progress, with Skip and Stop buttons.

### Changed
- Faster animations: screens rise and come into focus over a blurred, dimmed screen and drop down when closed; tabs
  cross-fade; the library blurs while the player slides over it.
- The share card's background is the blurred cover tinted with the track's colour instead of a gradient.
- Lyrics are translated into the app's language, in three modes: Original, Both (the translation under each line)
  and Translation; the choice is remembered. A second translation endpoint takes over when the first is rate-limited.
- The artist page buttons share the row (the «Radio» label used to wrap letter by letter on narrow screens).
- Licensed under GPL-3.0; releases are signed with a permanent key.

### Fixed
- My Wave and stations started from the tuning sheet failed with "rememberCoroutineScope left the composition" /
  InterruptedIOException: closing the sheet cancelled the start. Cancellations are no longer reported as errors.
- «Download» and «Add to playlist» in the track menu could be cancelled silently when the menu closed.
- Skipping or stopping a download no longer leaves temporary files behind.
- Dates were always formatted in Russian.

## 0.2.0 — 2026-10-16

- My Wave and radio from Yandex, wave tuning and activity stations, an offline wave from downloads.
- Yandex artist pages, albums and similar tracks; tracks with several artists are split into individual artists.
- A/B version comparison, «Replace with FLAC», duplicate finder, cover finder, album-wide tag editing.
- New release notifications, smooth transitions between tracks, Android Auto, lyrics translation,
  Wrapped-style stories, a «now listening» share card, backups.
- Errors explained in plain words with a journal and crash reports; liked tracks download right away; sync every 3 hours.

## 0.1.0 — 2026-10-15

The first version: a local-first library that understands Murglar's layout, a Media3 player with a 10-band EQ,
AutoEQ, ReplayGain and a spectrum visualiser, synced lyrics, track version history, a tag editor, Yandex Music,
YouTube Music and Spotify import, listening history, smart playlists, ListenBrainz scrobbling and a desktop preview.
