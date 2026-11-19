#!/usr/bin/env python3
"""Adds Material Symbols Rounded (weight 400, unfilled) as vector drawables.

    tools/fetch-symbols.py lock music_note star_fill1 …

Downloads the official SVGs from google/material-design-icons and writes
app/src/main/res/drawable/rounded_<name>.xml, the way Android Studio's importer (and
Essentials) does: 960 viewport, the SVG's -960 origin moved by a translateY group, white
fill tinted at draw time. Never hand-draw icon paths; see AGENTS.md, *Design reference*.
"""
import os
import re
import sys
import urllib.request

RES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "drawable")
URL = "https://raw.githubusercontent.com/google/material-design-icons/master/symbols/web/{0}/materialsymbolsrounded/{1}_24px.svg"


def main(names):
    failed = False
    for name in names:
        try:
            # "<name>_fill1" is the filled variant of <name>.
            base = name[: -len("_fill1")] if name.endswith("_fill1") else name
            svg = urllib.request.urlopen(URL.format(base, name), timeout=20).read().decode()
        except Exception as e:  # noqa: BLE001 — report and carry on with the rest
            print(f"{name}: {e}", file=sys.stderr)
            failed = True
            continue
        if 'viewBox="0 -960 960 960"' not in svg:
            print(f"{name}: unexpected viewBox", file=sys.stderr)
            failed = True
            continue
        paths = "\n".join(
            f'        <path\n            android:fillColor="@android:color/white"\n            android:pathData="{d}" />'
            for d in re.findall(r'<path d="([^"]+)"', svg)
        )
        xml = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Material Symbols Rounded "{name}" (weight 400, unfilled), Apache 2.0. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="960"
    android:viewportHeight="960">
    <group android:translateY="960">
{paths}
    </group>
</vector>
'''
        with open(os.path.join(RES, f"rounded_{name}.xml"), "w") as f:
            f.write(xml)
        print(f"rounded_{name}.xml")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
