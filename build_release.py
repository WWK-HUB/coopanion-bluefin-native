"""Build the importable character ZIP and a self-contained interactive preview."""
from pathlib import Path
import base64
import hashlib
import json
import zipfile

ROOT = Path(__file__).resolve().parent
PACK = ROOT / 'bluefin-native'
DIST = ROOT / 'dist'
DIST.mkdir(exist_ok=True)
manifest = json.loads((PACK / 'figure.json').read_text(encoding='utf-8'))
version = manifest['version']


def data_url(mime, content):
    return f'data:{mime};base64,' + base64.b64encode(content).decode('ascii')


rig = data_url('text/javascript', (ROOT / 'preview/kit/rig.js').read_bytes())
kit = (ROOT / 'preview/kit/body.js').read_text(encoding='utf-8')
kit = kit.replace("'./rig.js'", json.dumps(rig))
payload = {
    'kit': data_url('text/javascript', kit.encode('utf-8')),
    'figure': data_url('text/javascript', (PACK / 'figure.js').read_bytes()),
    'images': {
        p.name: data_url('image/png', p.read_bytes())
        for p in sorted((PACK / 'assets').glob('*.png'))
    },
    'vocab': manifest['vocab'],
}
template = (ROOT / 'preview-template.html').read_text(encoding='utf-8')
html = template.replace('__PAYLOAD__', json.dumps(payload, ensure_ascii=False))
html_path = DIST / 'bluefin-preview.html'
html_path.write_text(html, encoding='utf-8', newline='\n')

zip_path = DIST / f'bluefin-native-{version}.zip'
with zipfile.ZipFile(zip_path, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
    for item in sorted(PACK.rglob('*')):
        if item.is_file():
            # Fixed ZIP metadata makes repeated builds reproducible.
            info = zipfile.ZipInfo(item.relative_to(ROOT).as_posix(), (2026, 11, 3, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, item.read_bytes())

checksums = ''.join(
    hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + p.name + '\n'
    for p in [zip_path, html_path]
)
(DIST / 'SHA256SUMS.txt').write_text(checksums, encoding='ascii')
print(f'Built {zip_path.name} ({zip_path.stat().st_size:,} bytes)')
print(f'Built {html_path.name} ({html_path.stat().st_size:,} bytes)')
