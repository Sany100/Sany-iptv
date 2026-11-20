#!/usr/bin/env python3
"""Generate the iOS App Store icon from Android's sany_logo_icon.xml vector."""
from pathlib import Path
import cairosvg

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "SANYIPTV" / "Assets.xcassets" / "AppIcon.appiconset"
OUT.mkdir(parents=True, exist_ok=True)

# Paths and colors are copied from app/src/main/res/drawable/sany_logo_icon.xml.
svg = """<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 108 108">
<g transform="translate(28.08 28.08) scale(0.48)">
<path fill="#030A23" d="M0,0h108v108h-108z"/>
<path stroke="#087BFF" stroke-width="2.2" fill="#071735" d="M8,28 Q8,10 26,10 H82 Q100,10 100,28 V80 Q100,98 82,98 H26 Q8,98 8,80 Z"/>
<path stroke="#19E8FF" stroke-width="1.4" fill="none" d="M12,30 Q12,14 29,14 H79 Q96,14 96,30 V78 Q96,94 79,94 H29 Q12,94 12,78 Z"/>
<path stroke="#19DFFF" stroke-width="3" stroke-linecap="round" fill="none" d="M43,15 L34,5 M65,15 L74,5"/>
<path fill="#FFFFFF" d="M20,31 Q20,24 28,24 H80 Q88,24 88,32 V66 Q88,74 80,74 H28 Q20,74 20,66 Z"/>
<path fill="#06132E" d="M24,34 Q24,28 30,28 H78 Q84,28 84,34 V64 Q84,70 78,70 H30 Q24,70 24,64 Z"/>
<path stroke="#0AA8FF" stroke-width="6" stroke-linecap="round" stroke-linejoin="round" fill="none" d="M69,35 C58,27 36,29 33,40 C30,49 53,49 65,54 C81,61 68,72 49,71 C42,71 35,69 30,65"/>
<path fill="#13E8FF" d="M48,38 Q45,36 45,42 V61 Q45,66 50,63 L70,53 Q75,50 70,47 Z"/>
<path stroke="#087BFF" stroke-width="2.5" stroke-linecap="round" fill="none" d="M13,82 Q45,96 94,78"/>
<path stroke="#FFFFFF" stroke-width="1.2" fill="none" d="M20,87 Q50,98 88,84"/>
</g></svg>"""

cairosvg.svg2png(bytestring=svg.encode("utf-8"), write_to=str(OUT / "AppIcon.png"), output_width=1024, output_height=1024)
(OUT / "Contents.json").write_text('''{
  "images" : [
    {
      "filename" : "AppIcon.png",
      "idiom" : "universal",
      "platform" : "ios",
      "size" : "1024x1024"
    }
  ],
  "info" : {
    "author" : "xcode",
    "version" : 1
  }
}
''')
print(f"Generated {OUT / 'AppIcon.png'}")
