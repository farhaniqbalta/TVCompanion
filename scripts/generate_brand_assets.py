#!/usr/bin/env python3
"""Generate project-authored SVG and Android vector branding. No font/image dependencies."""
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]
DESIGN = ROOT / 'android/core/designsystem/src/main/res/drawable'
APP = ROOT / 'android/app/src/main/res'
BASE, MINT, AMBER = '#0E171C', '#A2E3D0', '#EFD29A'
FRAME = 'M42,13H16C11.6,13 8,16.6 8,21V39C8,43.4 11.6,47 16,47H48C52.4,47 56,43.4 56,39V27M24,55H40'
DOT = 'M61,14C61,17.87 57.87,21 54,21C50.13,21 47,17.87 47,14C47,10.13 50.13,7 54,7C57.87,7 61,10.13 61,14Z'
# Original geometric lettering, not outlined third-party font glyphs.
GLYPHS = {
    'T': ('M0,0H8M4,0V12', 8),
    'V': ('M0,0L4,12L8,0', 8),
    'C': ('M8,1C6,-1 0,-1 0,4V8C0,13 6,13 8,11', 8),
    'O': ('M4,0C1,0 0,2 0,4V8C0,10 1,12 4,12C7,12 8,10 8,8V4C8,2 7,0 4,0Z', 8),
    'M': ('M0,12V0L5,7L10,0V12', 10),
    'P': ('M0,12V0H4C9,0 9,6 4,6H0', 8),
    'A': ('M0,12L4,0L8,12M2,7H6', 8),
    'N': ('M0,12V0L8,12V0', 8),
    'I': ('M0,0V12', 0),
}

def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)

def vector(width, height, viewport, children):
    return f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="{width}dp" android:height="{height}dp" android:viewportWidth="{viewport[0]}" android:viewportHeight="{viewport[1]}">\n{children}\n</vector>\n'

def mark_xml(mono=False):
    color = '#FFFFFF' if mono else MINT
    dot = '#FFFFFF' if mono else AMBER
    return (f'    <path android:pathData="{FRAME}" android:fillColor="@android:color/transparent" android:strokeColor="{color}" android:strokeWidth="4" android:strokeLineCap="round" android:strokeLineJoin="round" />\n'
            f'    <path android:pathData="{DOT}" android:fillColor="{dot}" />')

def mark_svg():
    return (f'<path d="{FRAME}" fill="none" stroke="{MINT}" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"/>'
            f'<path d="{DOT}" fill="{AMBER}"/>')

write(DESIGN / 'ic_companion_mark.xml', vector(32, 32, (64, 64), mark_xml()))
write(DESIGN / 'ic_companion_notification.xml', vector(24, 24, (64, 64), mark_xml(True)))
write(APP / 'drawable/ic_launcher_foreground.xml', vector(108, 108, (108, 108),
      '    <group android:translateX="22" android:translateY="22">\n' + mark_xml() + '\n    </group>'))
write(APP / 'drawable/ic_launcher_monochrome.xml', vector(108, 108, (108, 108),
      '    <group android:translateX="22" android:translateY="22">\n' + mark_xml(True) + '\n    </group>'))
write(APP / 'mipmap-anydpi/ic_launcher.xml', '''<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/brand_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
''')
write(APP / 'values/colors.xml', f'<resources><color name="brand_background">{BASE}</color></resources>\n')

word = 'TVCOMPANION'
spacing = 3.4
width = sum(GLYPHS[ch][1] for ch in word) + spacing * (len(word) - 1)
scale = 1.8
start = (320 - width * scale) / 2
xml_letters, svg_letters = [], []
x = 0
for character in word:
    path, advance = GLYPHS[character]
    xml_letters.append(f'        <group android:translateX="{x:.2f}"><path android:pathData="{escape(path)}" android:fillColor="@android:color/transparent" android:strokeColor="#F2F5F4" android:strokeWidth="1.25" android:strokeLineCap="round" android:strokeLineJoin="round" /></group>')
    svg_letters.append(f'<path transform="translate({x:.2f},0)" d="{path}"/>')
    x += advance + spacing
banner = f'''    <path android:fillColor="{BASE}" android:pathData="M0,0H320V180H0Z" />
    <group android:translateX="128" android:translateY="25">{mark_xml()}</group>
    <group android:translateX="{start:.2f}" android:translateY="119" android:scaleX="{scale}" android:scaleY="{scale}">
''' + '\n'.join(xml_letters) + '\n    </group>'
# Intrinsic size is deliberately small; the 320x180 layer below gives the TV banner its size.
write(APP / 'drawable/brand_banner.xml', vector(160, 90, (320, 180), banner))
write(APP / 'drawable/tv_banner.xml', '''<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:drawable="@drawable/brand_banner" android:width="320dp" android:height="180dp" android:gravity="center" />
</layer-list>
''')
write(ROOT / 'brand/mark.svg', f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><title>TVCompanion mark</title>{mark_svg()}</svg>\n')
write(ROOT / 'brand/banner.svg', f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 320 180">
<title>TVCompanion TV launcher banner</title>
<path fill="{BASE}" d="M0 0h320v180H0z"/>
<g transform="translate(128,25)">{mark_svg()}</g>
<g transform="translate({start:.2f},119) scale({scale})" fill="none" stroke="#F2F5F4" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round">{''.join(svg_letters)}</g>
</svg>
''')
print('Generated TVCompanion brand SVGs, launcher assets, and shared vector marks.')
