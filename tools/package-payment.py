"""Create a source-only payment-service archive from an explicit allowlist."""
import argparse
import hashlib
from pathlib import Path
import re
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[1]
FILES = (
    'service.py', 'ledger.py', 'link_rewards.py', 'premium_products.py',
    'voting.py', 'vote_providers.py', 'vote_monthly.py', 'vote_schema.py',
    'vote_reconcile.py', 'funpay_provider.py', 'configure.py', 'check_local.py',
    'config.example.json', 'requirements.txt', 'README.md', 'BALANCE-API.md',
    'START.cmd', 'SETUP.cmd', 'CHECK.cmd', 'CHECK-LOCAL.cmd',
)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'build' / 'distributions')
    args = parser.parse_args()
    version = re.search(r'else "([\d.]+)-preview-folia26"', (ROOT / 'build.gradle.kts').read_text()).group(1)
    args.output.mkdir(parents=True, exist_ok=True)
    target = args.output / ('payment-service-' + version + '.zip')
    for name in FILES:
        if not (ROOT / 'donations' / name).is_file():
            raise SystemExit('Required source missing: ' + name)
    with ZipFile(target, 'w', ZIP_DEFLATED) as archive:
        for name in FILES:
            archive.write(ROOT / 'donations' / name, name)
        archive.write(ROOT / 'docs' / 'voting-eligibility.md', 'VOTING-ELIGIBILITY.md')
        archive.writestr('RELEASE.txt', 'VaultTracker ' + version + '\nSource-only update: preserve existing config.local.json, donations.sqlite, WAL/SHM, runtime and libraries.\nFor a fresh install see README.md; this archive includes no Python runtime or secrets.\n')
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    target.with_suffix('.zip.sha256').write_text(digest + '  ' + target.name + '\n', encoding='ascii')
    print(str(target))
    print('SHA256 ' + digest)

if __name__ == '__main__':
    main()
