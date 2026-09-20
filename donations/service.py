"""Separate loopback-only service. No Minecraft files, commands, RCON or JVM access."""
import argparse
import hmac
import json
import logging
import re
import threading
import time
import uuid
import traceback
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from ledger import Ledger
from funpay_provider import FunPayProvider

LOG = logging.getLogger('donations')


class Service:
    def __init__(self, config, ledger, provider):
        self.config, self.ledger, self.provider = config, ledger, provider
        self.lock = threading.Lock()
        self.attempts = {}
        self.cursor = None

    def refresh(self, order_id):
        self.ledger.attempted(order_id)
        verified = self.provider.verify(order_id)
        return self.ledger.sync(**verified, bonus_percent=self.config.get('review_bonus_percent', 10))

    def redeem(self, owner, code):
        now = time.monotonic()
        self.attempts = {u: values for u, values in self.attempts.items() if values[0] > now-60}
        since, count = self.attempts.get(owner, (now, 0))
        if count >= 5:
            raise ValueError('Слишком много попыток. Подождите минуту.')
        self.attempts[owner] = (since, count+1)
        row = self.ledger.lookup(code)
        if not row or (row['owner'] and row['owner'] != owner):
            raise ValueError('Код недоступен или уже использован.')
        if not self.lock.acquire(timeout=1):
            raise ValueError('Проверка занята. Повторите через несколько секунд.')
        try:
            self.refresh(row['id'])
            result = self.ledger.claim(owner, code, self.config.get('review_bonus_percent', 10))
            result.update(self.ledger.wallet(owner))
            return result
        finally:
            self.lock.release()

    def poll(self, stop):
        while not stop.is_set():
            try:
                # Always check the newest page, even while walking old history.
                # Otherwise a large order history delays newly paid orders.
                ids = self.discover()
                for order_id in ids:
                    if stop.is_set():
                        return
                    self.refresh_and_send(order_id)
                    if stop.wait(1):
                        return
                # Includes old claimed/closed orders: refunds and later reviews are not lost.
                for row in self.ledger.pending():
                    self.refresh_and_send(row['id'])
                    if stop.wait(1):
                        return
            except Exception as error:
                # Credentials, codes, cookies, response HTML must never enter logs.
                LOG.warning('FunPay check unavailable (%s); retry scheduled', type(error).__name__)
            stop.wait(max(15, self.config.get('poll_seconds', 30)))

    def discover(self):
        with self.lock:
            first_cursor, ids = self.provider.recent()
            if self.cursor:
                self.cursor, older = self.provider.recent(self.cursor)
                ids = list(dict.fromkeys([*ids, *older]))
            else:
                self.cursor = first_cursor
        return ids

    def refresh_and_send(self, order_id):
        try:
            with self.lock:
                current = self.refresh(order_id)
                if not current['sent'] and current['status'] in ('PAID', 'CLOSED'):
                    self.provider.send_code(current)
                    self.ledger.sent(current['id'])
        except Exception as error:
            LOG.warning('Order check unavailable (%s); no credit issued', type(error).__name__)


def handler(service):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def setup(self):
            super().setup()
            self.connection.settimeout(12)

        def do_POST(self):
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= 4096:
                    raise ValueError('Invalid request')
                # Drain the bounded request before closing even for denied auth.
                # Unread request data causes Windows to reset instead of delivering 401.
                payload = self.rfile.read(length)
                supplied = self.headers.get('Authorization', '')
                expected = 'Bearer ' + service.config['api_key']
                website_key=service.config.get('website_api_key','')
                website= len(website_key)>=40 and hmac.compare_digest(supplied.encode(),('Bearer '+website_key).encode())
                if not hmac.compare_digest(supplied.encode(), expected.encode()) and not website:
                    self.reply(401, {'error': 'Unauthorized'})
                    return
                body = json.loads(payload)
                if website:
                    if self.path not in ('/wallet','/donation-change') or (self.path=='/donation-change' and body.get('action') not in ('add','spend')):
                        self.reply(403, {'error':'Operation not permitted for website key'})
                        return
                    body['actor']='website'
                if self.path == '/alerts':
                    result = {'alerts': service.ledger.alerts()}
                elif self.path == '/premium-tasks':
                    result = {'tasks': service.ledger.premium_tasks()}
                elif self.path == '/donation-audit':
                    result = service.ledger.audit(int(body.get('page',0)))
                elif self.path == '/ack':
                    service.ledger.acknowledge(str(body['id']))
                    result = {'ok': True}
                else:
                    owner = str(uuid.UUID(body['owner']))
                    if self.path == '/wallet':
                        result = service.ledger.wallet(owner)
                    elif self.path == '/premium-ack':
                        service.ledger.premium_ack(owner,int(body['version']))
                        result = {'ok':True}
                    elif self.path == '/donation-change':
                        result = service.ledger.change(owner,str(uuid.UUID(body['requestId'])),body['action'],
                            str(body['actor']),str(body['name']),body.get('days',0),body.get('amountMinor',0),body.get('baseUntil',0))
                    elif self.path == '/redeem':
                        code = str(body['code']).strip().upper()
                        if not re.fullmatch('[A-F0-9]{32}', code):
                            raise ValueError('Проверьте код: нужны 32 символа из сообщения FunPay.')
                        result = service.redeem(owner, code)
                    else:
                        self.reply(404, {'error': 'Not found'})
                        return
                self.reply(200, result)
            except ValueError as error:
                # Only locally created errors are safe for users; never return provider text.
                message = str(error)
                if not message or not ('А' <= message[0] <= 'Я'):
                    message = 'Проверка недоступна. Повторите позже.'
                self.reply(400, {'error': message})
            except Exception as error:
                # Local diagnostic contains only exception type and our source lines.
                # Request bodies, cookies, credentials and provider responses are excluded.
                frames=[f'{Path(frame.filename).name}:{frame.lineno}' for frame in traceback.extract_tb(error.__traceback__)
                        if Path(frame.filename).name in ('service.py','ledger.py')]
                LOG.warning('Local API %s failed (%s; %s)',self.path,type(error).__name__,' <- '.join(frames))
                self.reply(503, {'error': 'Сервис временно недоступен. Повторите позже.'})

        def reply(self, status, value):
            data = json.dumps(value, ensure_ascii=False).encode()
            self.close_connection = True
            self.send_response(status)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Connection', 'close')
            self.end_headers()
            self.wfile.write(data)
    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--check', action='store_true', help='Read-only login and seller verification')
    parser.add_argument('--check-order', help='Read-only verification of a seller order; no code, message or credit')
    args = parser.parse_args()
    config = json.loads(args.config.read_text(encoding='utf-8-sig'))
    if len(config.get('api_key', '')) < 40 or not config.get('golden_key') or not config.get('order_marker'):
        raise SystemExit('Fill local config: api_key, golden_key, order_marker')
    if config.get('review_bonus_percent', 10) not in range(101):
        raise SystemExit('Invalid review bonus percentage')
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s')
    try:
        provider = FunPayProvider(config)
        if args.check_order:
            checked = provider.verify(args.check_order.strip().upper().lstrip('#'))
            print(json.dumps({k: checked[k] for k in ('order_id', 'amount', 'status', 'reviewed')}, ensure_ascii=False))
            return
        if args.check:
            provider.recent()
            print('FunPay login and seller verification OK; no messages sent, no credits issued.')
            return
    except Exception as error:
        # Library exceptions can contain response bodies; never print them.
        raise SystemExit('FunPay connection/check failed (' + type(error).__name__ + '). No payment processed.') from None
    service = Service(config, Ledger(args.config.parent / 'donations.sqlite'), provider)
    stop = threading.Event()
    server = HTTPServer(('127.0.0.1', config.get('port', 18763)), handler(service))
    thread = threading.Thread(target=service.poll, args=(stop,), daemon=True)
    thread.start()
    LOG.info('Donation service listening on 127.0.0.1:%s', server.server_port)
    try:
        server.serve_forever()
    finally:
        stop.set()
        server.server_close()


if __name__ == '__main__':
    main()
