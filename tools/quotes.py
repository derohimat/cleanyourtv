#!/usr/bin/env python3
"""Build a theme's quotes.json (Quran verses and hadith shown under the prayer times) from a list of references.

    tools/quotes.py themes/masjid-malam/quotes.txt          writes themes/masjid-malam/quotes.json

The text is never typed in by hand: every verse and hadith is fetched from its source and cut out of it
exactly, so what the TV shows is the source's text, character for character.
  quran 94:5-6        Arabic and Kemenag's Indonesian translation (2019 edition) from equran.id
  hadith 13 | HR. Bukhari dan Muslim | <Arabic start> … <Arabic end> | <Indonesian start> … <Indonesian end>
                      a hadith from Arba'in An-Nawawi (api.myquran.com). The source text also carries the chain of
                      narrators and the takhrij, so the line names where the Prophet's words (the matan) begin and
                      end, in both languages; a marker that is not found stops the run. A fifth field "excerpt"
                      marks an opening part quoted on its own and adds an ellipsis.
"""
import json
import sys
import unicodedata
import urllib.request
from pathlib import Path

SEP = " … "


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "cleanyourtv-quotes/1.0"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def nfc(text):
    """Unicode NFC: the same text, its vowel marks in one canonical order. Sources and editors store a shadda and a
    fatha in either order, which looks identical but would not match."""
    return unicodedata.normalize("NFC", text)


def cut(text, markers, what):
    """The part of text from the start marker to the end of the end marker; both must be in it."""
    text, markers = nfc(text), nfc(markers)
    start, _, end = markers.partition(SEP)
    end = end or start
    i = text.find(start)
    if i < 0:
        sys.exit(f"{what}: start not found: {start!r}")
    j = text.find(end, i)
    if j < 0:
        sys.exit(f"{what}: end not found after the start: {end!r}")
    return text[i:j + len(end)]


ARABIC_DIGITS = str.maketrans("0123456789", "٠١٢٣٤٥٦٧٨٩")


def quran(ref, surahs):
    number, _, verses = ref.partition(":")
    first, _, last = verses.partition("-")
    first, last = int(first), int(last or first)
    if number not in surahs:
        surahs[number] = fetch(f"https://equran.id/api/v2/surat/{int(number)}")["data"]
    surah = surahs[number]
    ayat = [a for a in surah["ayat"] if first <= a["nomorAyat"] <= last]
    if len(ayat) != last - first + 1:
        sys.exit(f"quran {ref}: Surah {surah['namaLatin']} has no such verse")
    # More than one verse: each ends with the end-of-verse sign and its number, as in a mushaf.
    arab = " ".join(a["teksArab"].strip() + (f" ۝{str(a['nomorAyat']).translate(ARABIC_DIGITS)}" if len(ayat) > 1 else "")
                    for a in ayat)
    indo = " ".join(a["teksIndonesia"].strip() for a in ayat)
    return {"ar": nfc(arab), "id": nfc(indo), "src": f"QS. {surah['namaLatin']}: {verses}"}


def hadith(fields, arbain):
    number, source, ar, idn = (f.strip() for f in fields[:4])
    excerpt = len(fields) > 4 and fields[4].strip() == "excerpt"
    h = arbain.get(number)
    if h is None:
        sys.exit(f"hadith {number}: not in Arba'in An-Nawawi")
    arab = cut(h["arab"], ar, f"hadith {number} Arabic")
    indo = cut(h["indo"], idn, f"hadith {number} Indonesian")
    if excerpt:
        arab, indo = arab + " …", indo + " …"
    return {"ar": arab, "id": indo, "src": f"{source} · Arba'in An-Nawawi no. {number}"}


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    refs = Path(sys.argv[1])
    surahs, arbain, quotes = {}, None, []
    for n, line in enumerate(refs.read_text(encoding="utf-8").splitlines(), 1):
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        kind, _, rest = line.partition(" ")
        if kind == "quran":
            quotes.append(quran(rest.strip(), surahs))
        elif kind == "hadith":
            if arbain is None:
                arbain = {str(h["no"]): h for h in fetch("https://api.myquran.com/v2/hadits/arbain/semua")["data"]}
            quotes.append(hadith(rest.split("|"), arbain))
        else:
            sys.exit(f"{refs}:{n}: expected 'quran' or 'hadith': {line}")
    out = refs.with_name("quotes.json")
    out.write_text(json.dumps({"quotes": quotes}, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"{len(quotes)} quotes -> {out}")


if __name__ == "__main__":
    main()
