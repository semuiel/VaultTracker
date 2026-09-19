"""Interactive local credential entry. Never echo or accept credentials in CLI arguments."""
import getpass
import json
import os
from pathlib import Path
import secrets
import sys


def main():
    root = Path(__file__).resolve().parent
    target = root / 'config.local.json'
    if target.exists():
        config = json.loads(target.read_text(encoding='utf-8-sig'))
    else:
        config = json.loads((root / 'config.example.json').read_text(encoding='utf-8-sig'))
    if not sys.stdin.isatty():
        raise SystemExit('Run configuration in a local interactive terminal.')
    print('FunPay setup: paste golden_key locally. Input is hidden and never logged.')
    key = getpass.getpass('golden_key (Enter keeps existing key): ').strip()
    key = key or config.get('golden_key', '')
    if not key:
        raise SystemExit('No key provided; nothing changed.')
    try:
        from FunPayAPI import Account
        account = Account(key, requests_timeout=8).get()
        seller = int(account.id)
        if seller <= 0:
            raise ValueError('Missing seller')
    except Exception as error:
        raise SystemExit('Login failed (' + type(error).__name__ + '). Configuration not changed.') from None
    if config.get('seller_id') and config['seller_id'] != seller:
        raise SystemExit('Different seller account. Configuration not changed.')
    config['golden_key'] = key
    config['seller_id'] = seller
    if len(config.get('api_key', '')) < 40:
        config['api_key'] = secrets.token_urlsafe(48)
    temporary = target.with_suffix('.json.tmp')
    with open(temporary, 'w', encoding='utf-8') as stream:
        json.dump(config, stream, ensure_ascii=False, indent=2)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, target)
    print(f'Login OK. Seller ID: {seller}. Configuration saved locally. No payments processed.')


if __name__ == '__main__':
    main()
