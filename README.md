# Palimpsest

**English** | [Русский](README.ru.md)

> ⚠️ **Beta.** Palimpsest is pre-1.0 — expect a few rough edges. Please report bugs and ideas in the
> [issues](https://github.com/xvin84/palimpsest/issues); the in-app error log has a «Copy details» button for that.

A music player for Android and the computer (Windows, macOS, Linux) where nothing gets erased. When an artist
re-uploads a track, the old version stays in its history — like the earlier text on a palimpsest. Local files first,
streaming services second: your library plays offline, Yandex Music and YouTube Music fill the gaps, and every
download lands in your music folder as a properly tagged file.

<p>
  <img src="docs/screenshots/library-en.png" width="24%" alt="Library">
  <img src="docs/screenshots/player-en.png" width="24%" alt="Now playing with the spectrum and the track's waveform">
  <img src="docs/screenshots/lyrics-en.png" width="24%" alt="Synced lyrics">
  <img src="docs/screenshots/lane-en.png" width="24%" alt="The Lane style: the cover edge to edge">
</p>
<p>
  <img src="docs/screenshots/desktop-player-en.png" width="49%" alt="Computer: the big player with lyrics">
  <img src="docs/screenshots/desktop-menu-en.png" width="49%" alt="Computer: the library and a track's menu">
</p>

## Features

**Library, offline first**
- Scans `Music/` (and any folders you add) incrementally: about half a second per hundred new FLACs, milliseconds on rescans.
- Tracks, albums, individual artists (features are split out), folders, playlists, favourites; filters by source, sorting.
- Understands Murglar's layout (`Murglar/Яндекс/Artist - Title.flac`), album folders, yt-dlp files with `[videoId]`;
  a `URL` tag links a file to its Yandex track. Picks up `.m3u/.m3u8` playlists, even ones written on a PC.
- «Open with» from any file manager or messenger.
- Duplicate finder: copies merge into one track and the extra files become its versions.
- Missing covers are found (Yandex, Deezer, iTunes, MusicBrainz) and embedded into the files.
- **Library health**: files without tags, covers or lyrics — and «Fill in tags» in one tap.
- **«Shrink the library»**: hi-res FLAC → CD-quality FLAC or AAC right on the phone; try it on three tracks first.
- **Storage screen**: what the app keeps (music, old versions, streaming cache, covers, backups) and how to free it.
- Files can be deleted and moved; deleted ones wait in a trash and come back with their likes and history.
- Search by kind — tracks, artists, albums, playlists — with a best-match card, sorting and filters.

**Track versions**
- A re-uploaded track downloads next to the old one; the old file moves to `Music/.palimpsest/versions/`, hidden from other players.
- Any version can be played, restored or deleted; **A/B comparison** switches between versions at the same second.
- **FLAC versions**: for the MP3 and AAC files in your library the same song is looked up in FLAC on Yandex; compare
  them from the same second and decide whether to replace. The old file stays as a version.
- The same audio is never stored twice (an audio fingerprint, so editing tags doesn't count as a new version).

**Player**
- Background playback, lock screen, Bluetooth buttons, gapless, smooth transitions between tracks, Android Auto.
- 10-band equalizer with presets, your own profiles and **AutoEQ** import; a separate profile for each pair of headphones.
- ReplayGain, a limiter against clipping, speed and pitch (slowed / nightcore), sleep timer with a fade-out.
- Spectrum visualiser (bars, mirror, ring around the cover) without the microphone permission.
- Synced lyrics from the file tag, a `.lrc`, Yandex, **LRCLIB**, **NetEase** or **Genius**; tap a line to seek;
  a translation into the app's language — under each line or instead of it; word-by-word karaoke where the source has it.
  Pick another variant if you like; the chosen one goes straight into the file's tag.
- Seek on the track's waveform; the colours follow the cover.

**Services**
- **Yandex Music**: sign-in with a device code, likes, playlists, search, lossless streaming, downloads with tags,
  covers and synced lyrics; liked tracks download right away. **My Wave** (with mood/character/language tuning and
  activity stations), radio by track or artist, artist pages, albums, similar tracks, new release notifications.
- **YouTube Music**: no account needed — search, streaming, downloads (M4A with tags).
- **Bandcamp**: search, artists, albums, streaming and downloads.
- **Spotify**: playlists and albums are imported by link and matched on Yandex or YouTube.
- The proxy setting applies only to YouTube, Spotify, Bandcamp and the cover databases; everything else goes direct.
- Offline wave: a mix of your downloads when there's no network — with moods (calm, energetic, focus, sad) from an
  analysis of the tracks themselves (tempo, key, energy) and smooth transitions.
- Yandex Music, YouTube and Bandcamp links open right in the app.

**Make a video from a track**
- The whole track rendered to MP4: the audio straight from the file through your EQ (no microphone, no noise),
  the visualiser and either the cover or the synced lyrics scrolling along. 9:16 for Reels/Shorts or 16:9 for YouTube.

<p>
  <img src="docs/screenshots/video-lyrics.png" width="24%" alt="Lyrics video frame">
  <img src="docs/screenshots/video-cover.png" width="73%" alt="Cover video frame">
</p>

**On the computer**
- The same library, Yandex Music, YouTube Music, Bandcamp, lyrics and downloads; the window adapts from half a screen
  to full screen, with a big player showing the lyrics and the spectrum.
- **A track menu** on right-click or «⋯»: tags (for many files at once too), a cover from a file or the internet,
  moving to a folder, finding the FLAC, versions, similar tracks, the link. **Select** several tracks with Ctrl and Shift.
- **«Upgrade to FLAC»**: pick folders and FLAC versions are found for the MP3/AAC files; listen to the old and the new
  file from the same second and decide.
- **Video from a track**, as on the phone: the cover or the lyrics, 9:16 or 16:9, up to 1440p at 60 fps.
- **My Wave one click away** in the sidebar, tuned by character, mood and language.
- **Keyboard shortcuts like Yandex Music** (K, J/L, N/P, F/D, R, S, M, W…), any of them can be changed in Settings.
- A tray icon (closing the window keeps the music going), media keys and the system's player controls (MPRIS on Linux).
- **Sync with the phone** over the local network: likes, playlists and history, paired with a six-digit code.

**And also**
- History, most played, Wrapped-style yearly stories, a «now listening» share card (or the file itself), ListenBrainz scrobbling.
- Errors explained in plain words with what to try next, and a journal with copyable details.
- Backups of the library, history, playlists, versions and settings (never sign-in tokens); weekly and one-tap restore.
- Styles **Palimpsest**, **Lane** (full-bleed cover, backgrounds in its colour), **Glass**, **Vinyl** and **Mono**, switched with one tap; six app icons to choose from.
- Themes: dark, AMOLED black, light, or follow the system. English and Russian.
- Settings by category, with search and an explanation for each option.
- Updates itself from GitHub Releases.

## Install

Download `palimpsest-<version>.apk` from [Releases](https://github.com/xvin84/palimpsest/releases) and open it on the phone
(Android 13 or newer). On the first launch allow «All files access» — it's needed for tags, `.lrc` files and versions;
without it the app can only play.

New versions show up in Settings → Updates and install over the current one.

**Desktop** (Windows, macOS, Linux): take the `.msi`/`.exe`, `.dmg`, `.deb`/`.rpm` or the portable
`Palimpsest-linux-x64.tar.gz` from the same release. They include their own Java and ffmpeg. The library, Yandex Music,
YouTube Music, Bandcamp, lyrics and downloads work as on the phone. Keys follow Yandex Music (Space or K — pause, J/L —
seek, N/P — tracks, F — like); the full list, and changing them, is in Settings → Keyboard shortcuts. Double-click plays,
right-click opens a track's menu.
On Arch Linux, add the repository from the releases to `/etc/pacman.conf`, then `sudo pacman -Syu palimpsest-bin`;
it uses the system ffmpeg and updates with the system (`sudo pacman -Syu`). Without the `y` pacman won't know about a
new version:
```ini
[palimpsest]
SigLevel = Optional TrustAll
Server = https://github.com/xvin84/palimpsest/releases/latest/download
```

**If the computer app doesn't open:** it writes what went wrong to `crash.log` — on Windows in `%APPDATA%\Palimpsest`,
on macOS in `~/Library/Application Support/Palimpsest`, on Linux in `~/.config/palimpsest-desktop` — and shows the
error in a window. Send that file along with the report. When a start dies before the window is drawn (a graphics
driver, say), the next one draws without the video card on its own; Settings → About turns that back off.

**If the phone refuses to install it:**
- *«Blocked by Play Protect» / «unsafe app»*: tap «More details» → «Install anyway». Builds before 0.4.1 were signed
  with a development certificate that Play Protect distrusts; later ones use a proper key (and still update the old ones).
- *Samsung «Auto Blocker»*: Settings → Security and privacy → Auto Blocker → off (you can turn it back on afterwards).
- *Xiaomi / HyperOS*: allow installing from the file manager or browser you opened the APK in.
- *«App not installed» on Android 12 or older*: Palimpsest needs Android 13 or newer.

## Build

Requirements: JDK 17, Android SDK (compileSdk 37).

```bash
./gradlew :core:test                    # unit tests of the shared core (DSP, LRC, M3U, Yandex on mocks, lyrics parsers…)
./gradlew :app:assembleDebug            # debug APK
./gradlew :app:assembleRelease          # minified APK; signed with your key if ~/.gradle/gradle.properties has
                                        # palimpsest.keystore / .keystore.password / .key.alias / .key.password,
                                        # otherwise with the debug key
LIVE=1 ./gradlew :core:test --tests '*LiveTest*'   # hits the real services (YouTube, LRCLIB, NetEase, Genius…)
./gradlew :desktop:run                  # the desktop app (needs ffmpeg on PATH when run like this)
./gradlew :desktop:packageDistributionForCurrentOS   # a native package for this OS
```

## How it's built

```
core/     plain Kotlin/JVM, shared by Android and desktop
  dsp/       equalizer (RBJ biquads), limiter, FFT spectrum, AutoEQ parser
  yandex/    Yandex Music API client, device-code OAuth
  youtube/   NewPipe Extractor
  lyrics/    LRC, LRCLIB, NetEase, Genius
  library/   file names, M3U, versions (fingerprints), artist splitting
  covers/    Deezer, iTunes, Cover Art Archive
  analysis/  tempo, key, energy
  sync/      phone ↔ computer sync over the local network
app/
  data/      Room, scanner, tags (jaudiotagger), downloads, sync, versions, backups, storage, updates
  playback/  Media3 service, an AudioProcessor for EQ + ReplayGain + spectrum, streaming with cache and decryption
  render/    track → video (Media3 Transformer, frames drawn on Canvas)
  ui/        Compose screens
desktop/  the computer app on Compose Multiplatform: audio through ffmpeg, tray, MPRIS, keyboard shortcuts
```

Kotlin · Jetpack Compose · Media3 (ExoPlayer, Transformer) · Room · jaudiotagger · NewPipe Extractor

Fonts: Playfair Display and Manrope, both under the SIL Open Font License.

## License

[GPL-3.0](LICENSE). Palimpsest is free software: use it, study it, change it and share it under the same terms.

Palimpsest isn't affiliated with Yandex, Google or Spotify. Streaming services are used through their public and
unofficial interfaces for your personal listening with your own account; please respect their terms.
