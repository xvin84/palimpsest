# Changelog

**English** | [Русский](CHANGELOG.ru.md)

## 0.4.3 — 2026-10-21

### New
- **Library health** (Settings → Library, Storage): tracks without a cover, lyrics or tags, duplicates, lossy and
  hi-res files, new versions and audio not analysed — each with its list and a one-tap fix.
- **Audio analysis**: tempo, key (Camelot) and energy of every track, worked out on the phone; shown on the track page.
- **Offline wave moods**: calm, energetic, focus, sad; the mix is ordered so tempo, key and energy flow.
- **Storage budget** (Shrink the library): the library is kept within a size — favourites and often played tracks stay
  as they are, the least played become AAC 256; now, or by itself once a week while charging.
- **Links**: Yandex Music, YouTube and Bandcamp links open in the app — tapped or shared.
- **Karaoke**: lyrics with word timings (enhanced LRC, NetEase) fill the sung line word by word.
- **Sync with the computer** over the home network: likes, own playlists and history; pairs with a 6-digit code,
  syncs on demand, on launch and in the background.
- **MP3 → AAC 192** in Shrink the library: for MP3 and other lossy files of 256–320 kbps, about 40 % smaller
  (a second lossy pass — said plainly).
- Desktop: **media keys and the system's player controls** on Linux (MPRIS); «Sync with the phone» setting.

### Fixed
- Update notes are shown in the app's language, and no longer break mid-sentence.

## 0.4.2 — 2026-10-21

### New
- **FLAC versions** (Settings → Library/Downloads): for every file stored as MP3/AAC the app looks for the same
  recording on Yandex Music — by the file’s link, or by artist, title and length — and downloads the FLAC as a version
  next to it. In the player you compare them A/B at the same second and answer «Same song?»: only «yes» replaces the
  file (the old one goes to history), «not it» drops the find for good.
- **Shrink the library** (Storage): hi-res FLAC → CD-quality FLAC (16-bit; sounds the same, ~40 % smaller), or AAC
  256/128 kbps. Converted on the phone with tags, cover, lyrics, link and ReplayGain; originals deleted or kept as
  versions. A trial run on 3 tracks first. A result of the wrong length is refused.

### Fixed
- «Replace with FLAC» saved some tracks as .m4a: Yandex sends FLAC inside MP4 — it’s now unpacked into a real .flac,
  bit for bit.
- Covers were never written into FLAC files (cover search and downloads).
- MP3/M4A service links went into a tag field nothing read back — YouTube downloads lost their link after «re-read tags».
- With a lyrics translation the sung line drifted to the bottom; it now rests a little below the top (desktop too).

## 0.4.1 — 2026-10-21

### New
- **Three more styles**: Glass (the cover blurred over the whole screen, controls on frosted glass), Vinyl (the cover
  becomes the label of a spinning record, with a tonearm and warm retro colours) and Mono (black and white only,
  a monospace font, an ASCII progress bar). Picked next to Palimpsest and Lane.
- **A choice of app icons**: Classic, Vinyl, Layers, Neon, Cassette, Lane — Settings → Appearance.
- **Palimpsest for the desktop**, a full app: the library as a track table plus albums, artists and folders, favourites
  and playlists, a queue, Yandex Music (sign-in by code, My Wave, likes, playlists), YouTube Music and Bandcamp, search by
  kind, artist and album pages, downloads with tags, synced lyrics with translation, a full-window player, history, the
  Palimpsest and Lane styles, keyboard shortcuts. Packages for Windows, macOS and Linux are in this release.

### Fixed
- **«Blocked for security reasons» on some phones**: releases were signed with a development certificate that Play
  Protect blocks. They now use a proper key; installed copies update in place as usual.
- In the Lane style the lyrics could run over the track title.

## 0.4.0 — 2026-10-20

### New
- **Styles**: a «Style» section in Settings — Palimpsest (as before) and the new **Lane**. In Lane the cover fills the
  top of the player and melts into a backdrop of its colour, the whole app is tinted by it, headings are set in Manrope,
  buttons are white pills, the seek bar is a hairline and the mini player floats as a card. One tap switches; works with
  the dark, AMOLED and light themes.
- **Reworked settings**: categories with summaries, a search over every option, an explanation under each one.
- **Search by kind**: all / tracks / artists / albums / playlists, a best-match card on top (search for an artist and
  get their card and their songs first), sort and filters, «Show more», recent searches. YouTube Music artists, albums
  and playlists open in the app.
- **Bandcamp** as a source: search, artist and album pages, streaming and downloads (MP3 128). Paid-only tracks are
  skipped with an explanation. From Russia it needs the proxy setting.
- **Delete and move files** from a track's menu. Deleted files go to a trash (Storage → Trash): undo right away or
  restore later with likes, history and playlists. Retention time and size limit are adjustable, or the trash can be off.
- **Lyrics saved into files**: lyrics found or picked go into the file's tag (timed when there are timings), so any
  player shows them. Downloads carry the lyrics chosen in the app. Can be switched off.

### Changed
- Lyrics videos look for synced lyrics in every source (the file, Yandex, LRCLIB, NetEase); only when there are none
  anywhere is the cover used. Plain lyrics don't count for videos.
- The library remembers its sort and filters.
- In History and Most played a tap plays the track, a long press opens its menu (it used to open the tag editor).
- With no network the queue leaves out cloud tracks that aren't cached, and when the network drops the player jumps
  to what it can play.
- Library search ignores case in any alphabet.

### Fixed
- The My Wave card sometimes appeared only after touching the screen.
- Covers and streams from CDNs with broken IPv6 could take 15 seconds and still fail.

## 0.3.3 — 2026-10-19

### Fixed
- The backdrop of rendered videos is a smooth blur of the cover, not a blocky grid of enlarged pixels.

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
