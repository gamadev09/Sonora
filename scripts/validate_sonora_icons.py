from pathlib import Path
import re,xml.etree.ElementTree as ET
res=Path('app/src/main/res'); a='{http://schemas.android.com/apk/res/android}'
original=ET.parse(res/'drawable/ic_launcher_foreground.xml').getroot()
paths=[p.get(a+'pathData') for p in original.findall('path')]
assert len(paths)==5
for variant in ['light','silver','monochrome','themed']:
    vector=ET.parse(res/f'drawable/ic_sonora_waveform_{variant}.xml').getroot()
    assert [p.get(a+'pathData') for p in vector.findall('path')]==paths,variant
    assert all(vector.get(a+k)==original.get(a+k) for k in ['width','height','viewportWidth','viewportHeight'])
for name in ['ic_launcher','ic_launcher_round','ic_sonora_light','ic_sonora_dark','ic_sonora_monochrome']:
    icon=ET.parse(res/f'mipmap-anydpi/{name}.xml').getroot()
    assert icon.tag=='adaptive-icon'
    assert icon.find('monochrome').get(a+'drawable')=='@drawable/ic_sonora_waveform_themed'
manifest=ET.parse('app/src/main/AndroidManifest.xml').getroot()
aliases=manifest.findall('./application/activity-alias')
assert len(aliases)==4
assert sum(x.get(a+'enabled')=='true' for x in aliases)==1
assert {x.get(a+'name') for x in aliases}=={'.LauncherSonora'+v for v in ['Default','Light','Dark','Monochrome']}
for alias in aliases:
    assert alias.get(a+'targetActivity')=='.MainActivity'
    assert alias.get(a+'icon')==alias.get(a+'roundIcon')
    assert alias.find("./intent-filter/category[@"+a+"name='android.intent.category.LAUNCHER']") is not None
for path in res.rglob('*'):
    if path.is_file():
        assert not re.match(r'ic_(liquid_glass|samsung_glass|pixel_glass|samsung_neon|pixel_neon|neon_|glass_|monochrome)',path.stem),path
print('PASS: all four variants preserve official waveform geometry, adaptive masks, themed icons and exactly one default launcher; no legacy assets remain.')
