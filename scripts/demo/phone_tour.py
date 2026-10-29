#!/usr/bin/env python3
"""The phone's part of the tour (see desktop/src/test/.../Tour.kt and TourCut.kt).

Drives the app on an emulator with real input — taps on what's on screen (found by its words in
uiautomator's dump), swipes — while scrcpy records the screen at 60 fps together with the phone's
own sound (nothing is played on the computer). Writes phone.mkv and phone.json (chapters and
captions, in seconds from the video's start) into the given directory.

    emulator -avd … -gpu host -feature -Vulkan
    adb shell settings put system show_touches 1
    scripts/demo/phone_tour.py /some/dir
"""
import json
import re
import signal
import subprocess
import threading
import sys
import time
from pathlib import Path

out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
chapters, captions = [], []
caption = None


def adb(*a, check=False):
    return subprocess.run(["adb", *a], capture_output=True, text=True, check=check).stdout


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    for n in re.findall(r"<node [^>]*>", xml):
        g = lambda k: (re.search(k + r'="([^"]*)"', n) or [None, ""])[1]
        b = list(map(int, re.findall(r"\d+", g("bounds")) or [0, 0, 0, 0]))
        yield g("text"), g("content-desc"), b


def find(pattern, below=0, above=10_000, nth=1):
    rx = re.compile(pattern)
    for text, desc, b in nodes():
        cy = (b[1] + b[3]) // 2
        if (rx.search(text) or rx.search(desc)) and below <= cy <= above:
            nth -= 1
            if nth == 0:
                return (b[0] + b[2]) // 2, cy
    return None


def tap(pattern=None, at=None, below=0, above=10_000, pause=1.0, nth=1):
    p = at or find(pattern, below, above, nth)
    if p is None:
        print(f"phone tour: no «{pattern}» on screen", file=sys.stderr)
        return False
    adb("shell", "input", "tap", str(p[0]), str(p[1]))
    time.sleep(pause)
    return True


def tap_scrolling(pattern, pause=1.0, tries=5):
    """Taps [pattern], scrolling the list down until it shows."""
    for _ in range(tries):
        p = find(pattern, below=400, above=1900)
        if p:
            return tap(at=p, pause=pause)
        swipe(540, 1650, 540, 1050, 350, pause=0.5)
    print(f"phone tour: no «{pattern}» even after scrolling", file=sys.stderr)
    return False


def swipe(x1, y1, x2, y2, ms=450, pause=0.8):
    adb("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(ms))
    time.sleep(pause)


def back(pause=0.8):
    adb("shell", "input", "keyevent", "BACK")
    time.sleep(pause)


def now():
    return time.monotonic() - video_start


def say(ru, en=""):
    global caption
    if caption and caption[1]:
        captions.append({"from": caption[0], "to": now(), "ru": caption[1], "en": caption[2]})
    caption = (now(), ru, en)


def chapter(ru, en, sub_ru, sub_en, length=2.6):
    say("")
    chapters.append({"at": now(), "ru": ru, "en": en, "subRu": sub_ru, "subEn": sub_en, "length": length})
    time.sleep(length)  # the card covers the screen meanwhile


# Start where a person would: the app open on its library.
adb("shell", "am", "force-stop", "dev.xvin.palimpsest")
adb("shell", "am", "start", "-n", "dev.xvin.palimpsest/.MainActivity")
time.sleep(5)

adb("shell", "cmd", "media_session", "volume", "--stream", "3", "--set", "15")
record = subprocess.Popen(
    # Unbuffered, or «Recording started» only shows when scrcpy exits.
    ["stdbuf", "-o0", "-e0", "scrcpy", "--no-playback", "--no-window", "--audio-source=playback", "--max-fps=60", "--video-bit-rate=16M", f"--record={out / 'phone.mkv'}"],
    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
)
for line in record.stdout:  # the recording's first frame is about now
    if "Recording started" in line:
        break
video_start = time.monotonic()
threading.Thread(target=lambda: [None for _ in record.stdout], daemon=True).start()
time.sleep(1.0)

# --- The tour ---
chapter("Телефон", "The phone", "Та же медиатека и всё то же — в кармане", "The same library and all the rest — in your pocket", 3.0)
say("Скачанное играет и без сети", "What's downloaded plays with no network")
tap("^Все$", pause=0.8)  # the filter is remembered: from «all», then downloads
tap("^Скачанные$", pause=1.5)
say("Плеер: обложка во весь экран, спектр, волна трека", "The player: the cover edge to edge, the spectrum, the track's waveform")
tap_scrolling("^Бабочки$", pause=1.5)  # a song with synced lyrics in its tags
tap(at=(351, 1984), pause=3.5)  # the mini player opens the big one
say("Синхронный текст — по нажатию", "Synced lyrics — one tap away")
tap("^Текст$", pause=6.0)
tap("^Текст$", pause=1.0)
back(1.0)

chapter("Стили", "Styles", "Пять обликов приложения — переключаются одним касанием", "Five looks for the app — one tap to switch")
say("Настройки → Стиль", "Settings → Style")
tap("^Настройки$|^Settings$", pause=1.5)
swipe(900, 640, 200, 640, 400, pause=0.8)
say("Vinyl: пластинка крутится, тонарм опускается", "Vinyl: the record turns, the tonearm swings on")
tap("^Vinyl$", pause=1.2)
back(1.0)
tap(at=(351, 1984), pause=4.5)
back(1.0)
tap("^Настройки$|^Settings$", pause=1.5)
swipe(200, 640, 900, 640, 400, pause=0.8)
say("Lane: обложка во главе, фон в её цвете", "Lane: the cover leads, the background takes its colour")
tap("^Lane$", pause=1.2)
back(1.0)
tap(at=(351, 1984), pause=4.0)
back(1.0)

# Back to the top of the library, where internet radio is.
for _ in range(3):
    swipe(540, 800, 540, 1700, 300, pause=0.3)
chapter("Интернет-радио", "Internet radio", "Станции со всего мира — и слова песни, что сейчас в эфире", "Stations from all over the world — and the words of what's on air")
say("Каталог: жанры, популярное в стране", "The catalog: genres, the country's most listened")
tap("^Интернет-радио$", pause=4.0)
say("Включаем станцию — и видно, что играет", "Turn a station on — and see what's playing")
tap("^ROCK FM$|^Relax FM Chillout$", pause=9.0)
say("«Текст» — слова этой песни", "«Lyrics» — the words of that song")
tap("^Текст$", pause=7.0)
tap("^Текст$", pause=1.0)
say("")
time.sleep(0.5)

duration = now()
record.send_signal(signal.SIGINT)  # scrcpy finishes the file on ^C
try:
    record.wait(timeout=20)
except subprocess.TimeoutExpired:
    record.kill()
(out / "phone.json").write_text(json.dumps({
    "video": "phone.mkv", "audioOffset": 0.0,
    "duration": duration - 0.3, "chapters": chapters, "captions": captions,
}, ensure_ascii=False, indent=1))
adb("shell", "am", "force-stop", "dev.xvin.palimpsest")
print(f"phone tour: {duration:.1f} s → {out}")
