#!/usr/bin/env python3
"""Daily word-of-the-day for the Portal dashboard.

Primary source: Transparent Language's Word of the Day RSS feeds
(checked 2026-10-25 at Sameer's request):
- Urdu:    https://feeds.feedblitz.com/urdu-word-of-the-day
- English: https://feeds.feedblitz.com/english-word-of-the-day-for-spanish
  (Transparent's English feed is aimed at Spanish speakers, so the pair's
  "translation" is Spanish; we take the English word + its example
  sentence, which the dashboard shows as the sub-line.)

Each feed item carries: word, translation, part of speech, an example
sentence in the target language, and the sentence's meaning. The feeds
do not include transliteration or native audio, so:
- ur_translit is empty for feed words (the page then shows just the
  English meaning under the Urdu word);
- audio is generated: English via /opt/hatch/bin/tts, Urdu via Google
  Translate's speech endpoint (the local engine renders Urdu as empty).

Fallback: if either feed fails or parses dirty, rotate the local bank
in words.json instead, so the dashboard is never left wordless.

Everything is staged first; word.json, the audio clips, and data.json
are only updated once both clips exist. Exits non-zero on total failure.
"""
import html as html_mod
import json
import re
import subprocess
import sys
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
      "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36")
FEEDS = {
    "ur": "https://feeds.feedblitz.com/urdu-word-of-the-day",
    "en": "https://feeds.feedblitz.com/english-word-of-the-day-for-spanish",
}
ARABIC = re.compile(r"[\u0600-\u06FF]")


def fetch_text(url):
    # FeedBlitz sniffs the UA: a browser UA gets the human subscribe
    # page, a plain one gets the XML. Keep this one short on purpose.
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.read().decode("utf-8", "replace")


def item_parts(xml_text):
    """Split the first RSS item into (title, body text, transliteration).

    FeedBlitz XML is not strictly well-formed, so work textually. The
    item's <title> carries the pair as 'WORD: translation'; the body
    tables carry part of speech / example sentence / sentence meaning;
    <wotd:transliteratedWord> carries a transliteration when the feed
    provides one (often empty)."""
    m = re.search(r"<item>(.*?)</item>", xml_text, flags=re.S)
    if not m:
        raise ValueError("no <item> in feed")
    block = m.group(1)
    t = re.search(r"<title>(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?</title>",
                  block, flags=re.S)
    if not t:
        raise ValueError("no title in item")
    title = html_mod.unescape(t.group(1)).strip()
    tr = re.search(r"<wotd:transliteratedWord>(.*?)</wotd:transliteratedWord>",
                   block, flags=re.S)
    translit = html_mod.unescape(tr.group(1)).strip() if tr else ""
    raw = re.sub(r"<!\[CDATA\[(.*?)\]\]>", r"\1", block, flags=re.S)
    raw = html_mod.unescape(re.sub(r"<[^>]+>", " ", raw))
    body = re.sub(r"\s+", " ", raw).strip()
    return title, body, translit


def parse_wotd(xml_text):
    title, body, translit = item_parts(xml_text)
    if ":" not in title:
        raise ValueError(f"title has no pair colon: {title!r}")
    word, translation = title.split(":", 1)
    pos = re.search(r"Part of speech\s*:\s*([A-Za-z]+)", body)
    ex = re.search(r"Example sentence\s*:\s*(.+?)\s*Sentence meaning", body)
    return {
        "word": word.strip(" -–—"),
        "translation": translation.strip(),
        "pos": pos.group(1) if pos else "",
        "example": ex.group(1).strip() if ex else "",
        "translit": translit,
    }


def from_transparent():
    ur = parse_wotd(fetch_text(FEEDS["ur"]))
    en = parse_wotd(fetch_text(FEEDS["en"]))
    if not ARABIC.search(ur["word"]):
        raise ValueError(f"Urdu word has no Arabic script: {ur['word']!r}")
    if ARABIC.search(en["word"]) or not en["word"]:
        raise ValueError(f"English word looks wrong: {en['word']!r}")
    if not ur["example"] or not en["example"]:
        raise ValueError("missing example sentence")
    return {
        "en": en["word"],
        "en_def": en["example"],
        "ur": ur["word"],
        "ur_translit": ur.get("translit", ""),
        "ur_meaning": ur["translation"],
        "audio_en": "audio/wotd-en.mp3",
        "audio_ur": "audio/wotd-ur.mp3",
        "source": "transparent",
        "_en_spoken": f"{en['word']}. {en['example']} {en['word']}.",
        "_ur_spoken": f"{ur['word']}۔ {ur['word']}۔ {ur['example']}",
    }


def from_bank(state):
    words = json.loads((HERE / "words.json").read_text())
    idx = state.get("next", 1) % len(words)
    pair = words[idx]
    return {
        "en": pair["en"],
        "en_def": pair["en_def"],
        "ur": pair["ur"],
        "ur_translit": pair["ur_translit"],
        "ur_meaning": pair["ur_meaning"],
        "audio_en": "audio/wotd-en.mp3",
        "audio_ur": "audio/wotd-ur.mp3",
        "source": "bank",
        "_en_spoken": f"{pair['en']}. {pair['en_def']}. {pair['en']}.",
        "_ur_spoken": f"{pair['ur']}۔ {pair['ur']}۔ {pair['ur']} کا مطلب ہے {pair['ur_gloss']}۔",
        "_bank_idx": idx,
        "_bank_len": len(words),
    }


def gen_en_audio(text, out_path):
    r = subprocess.run(
        ["/opt/hatch/bin/tts", "speak", "--language", "en", "--text-stdin",
         "--output", str(out_path)],
        input=text, capture_output=True, text=True, timeout=180)
    if r.returncode != 0 or out_path.stat().st_size <= 5000:
        raise RuntimeError(f"English TTS failed: {r.stderr.strip()[:150]}")


def gen_ur_audio(text, out_path):
    url = ("https://translate.google.com/translate_tts?ie=UTF-8&tl=ur"
           "&client=tw-ob&q=" + urllib.parse.quote(text))
    data = fetch_bytes(url)
    if len(data) < 5000 or not (data[:3] == b"ID3" or data[0] == 0xFF):
        raise RuntimeError(f"Urdu TTS suspect audio ({len(data)} bytes)")
    out_path.write_bytes(data)


def fetch_bytes(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return resp.read()


def main():
    state_path = HERE / "state.json"
    state = json.loads(state_path.read_text()) if state_path.exists() else {"next": 1}

    word = None
    try:
        word = from_transparent()
    except Exception as e:  # noqa: BLE001
        print(f"daily_word: Transparent feed unusable ({e}); using bank",
              file=sys.stderr)
    if word is None:
        word = from_bank(state)

    audio_dir = HERE / "audio"
    audio_dir.mkdir(exist_ok=True)
    tmp_en = audio_dir / "wotd-en.mp3.tmp"
    tmp_ur = audio_dir / "wotd-ur.mp3.tmp"
    try:
        gen_en_audio(word.pop("_en_spoken"), tmp_en)
        gen_ur_audio(word.pop("_ur_spoken"), tmp_ur)
    except Exception as e:  # noqa: BLE001
        print(f"daily_word: audio failed: {e}", file=sys.stderr)
        sys.exit(1)
    tmp_en.replace(audio_dir / "wotd-en.mp3")
    tmp_ur.replace(audio_dir / "wotd-ur.mp3")

    bank_idx = word.pop("_bank_idx", None)
    word.pop("_bank_len", None)
    (HERE / "word.json").write_text(json.dumps(word, ensure_ascii=False, indent=2))

    r = subprocess.run([sys.executable, str(HERE / "build_data.py")],
                       capture_output=True, text=True, timeout=120)
    if r.returncode != 0:
        print(f"daily_word: build_data.py failed: {r.stderr.strip()[:200]}",
              file=sys.stderr)
        sys.exit(1)

    if bank_idx is not None:
        state["next"] = bank_idx + 1
        state_path.write_text(json.dumps(state))
    print(f"word of the day ({word['source']}): {word['en']} / {word['ur']}")


if __name__ == "__main__":
    main()
