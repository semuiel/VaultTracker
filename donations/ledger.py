"""Durable donation ledger. Amounts are integer hundredths of a coin, never floats."""
import secrets
import sqlite3
import time
from contextlib import contextmanager


class Ledger:
    def __init__(self, path):
        self.path = str(path)
        with self.connection() as db:
            db.executescript('''
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS orders(
                    id TEXT PRIMARY KEY, buyer TEXT NOT NULL, amount INTEGER NOT NULL CHECK(amount>0),
                    code TEXT UNIQUE NOT NULL, status TEXT NOT NULL, owner TEXT,
                    sent INTEGER NOT NULL DEFAULT 0, credited INTEGER NOT NULL DEFAULT 0,
                    bonus INTEGER NOT NULL DEFAULT 0, reversed INTEGER NOT NULL DEFAULT 0,
                    reviewed INTEGER NOT NULL DEFAULT 0, checked INTEGER NOT NULL,
                    created INTEGER NOT NULL, attempted INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS wallets(owner TEXT PRIMARY KEY, balance INTEGER NOT NULL DEFAULT 0,
                    blocked INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS entries(id TEXT PRIMARY KEY, owner TEXT NOT NULL,
                    amount INTEGER NOT NULL, created INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS alerts(id TEXT PRIMARY KEY, body TEXT NOT NULL, delivered INTEGER NOT NULL DEFAULT 0);
            ''')

    @contextmanager
    def connection(self):
        db = sqlite3.connect(self.path, timeout=5, isolation_level=None)
        db.row_factory = sqlite3.Row
        db.execute('PRAGMA busy_timeout=5000')
        db.execute('PRAGMA synchronous=FULL')
        try:
            db.execute('BEGIN IMMEDIATE')
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    @staticmethod
    def entry(db, key, owner, amount):
        if db.execute('SELECT 1 FROM entries WHERE id=?', (key,)).fetchone():
            return
        db.execute('INSERT OR IGNORE INTO wallets(owner) VALUES(?)', (owner,))
        db.execute('INSERT INTO entries VALUES(?,?,?,?)', (key, owner, amount, int(time.time())))
        db.execute('UPDATE wallets SET balance=balance+? WHERE owner=?', (amount, owner))

    def sync(self, order_id, buyer, amount, status, reviewed=False, bonus_percent=10):
        if status not in ('PAID', 'CLOSED', 'REFUNDED') or type(amount) is not int or not 0 < amount <= 100_000_000:
            raise ValueError('Unverified order')
        if not 0 <= bonus_percent <= 100:
            raise ValueError('Invalid bonus')
        now = int(time.time())
        with self.connection() as db:
            db.execute('INSERT OR IGNORE INTO orders(id,buyer,amount,code,status,checked,created) VALUES(?,?,?,?,?,?,?)',
                       (order_id, buyer, amount, secrets.token_hex(16).upper(), status, now, now))
            row = db.execute('SELECT * FROM orders WHERE id=?', (order_id,)).fetchone()
            if row['amount'] != amount or row['buyer'] != buyer:
                raise ValueError('Order amount or buyer changed; manual review required')
            # A refunded order is terminal. Reopened orders need a new payment/order.
            if row['status'] == 'REFUNDED':
                status = 'REFUNDED'
            db.execute('UPDATE orders SET status=?, reviewed=?, checked=? WHERE id=?',
                       (status, int(reviewed), now, order_id))
            if status == 'REFUNDED' and row['credited'] and not row['reversed']:
                self.entry(db, order_id + ':refund', row['owner'], -row['amount'] - row['bonus'])
                db.execute('UPDATE orders SET reversed=1 WHERE id=?', (order_id,))
                balance = db.execute('SELECT balance FROM wallets WHERE owner=?', (row['owner'],)).fetchone()[0]
                if balance < 0:
                    db.execute('UPDATE wallets SET blocked=1 WHERE owner=?', (row['owner'],))
                db.execute('INSERT OR IGNORE INTO alerts VALUES(?,?,0)',
                           (order_id + ':refund', f'Возврат FunPay #{order_id}. Игрок UUID {row["owner"]}. Списано {(row["amount"]+row["bonus"])/100:.2f}. Баланс {balance/100:.2f}.'))
            if status == 'CLOSED' and reviewed and row['credited'] and not row['reversed'] and not row['bonus']:
                bonus = amount * bonus_percent // 100
                if bonus:
                    self.entry(db, order_id + ':review', row['owner'], bonus)
                    db.execute('UPDATE orders SET bonus=? WHERE id=?', (bonus, order_id))
            return db.execute('SELECT * FROM orders WHERE id=?', (order_id,)).fetchone()

    def lookup(self, code):
        if len(code) != 32 or any(c not in '0123456789ABCDEF' for c in code):
            return None
        with self.connection() as db:
            row = db.execute('SELECT * FROM orders WHERE code=?', (code,)).fetchone()
            return dict(row) if row else None

    def claim(self, owner, code, bonus_percent=10):
        with self.connection() as db:
            row = db.execute('SELECT * FROM orders WHERE code=?', (code,)).fetchone()
            if not row or row['status'] not in ('PAID', 'CLOSED') or int(time.time())-row['checked'] > 30:
                raise ValueError('Код недоступен или оплата не подтверждена. Повторите проверку позже.')
            if row['owner'] and row['owner'] != owner:
                raise ValueError('Код недоступен или уже использован.')
            if not row['credited']:
                self.entry(db, row['id'] + ':payment', owner, row['amount'])
                db.execute('UPDATE orders SET owner=?,credited=1 WHERE id=?', (owner, row['id']))
            if row['status'] == 'CLOSED' and row['reviewed'] and not row['bonus']:
                bonus = row['amount'] * bonus_percent // 100
                if bonus:
                    self.entry(db, row['id'] + ':review', owner, bonus)
                    db.execute('UPDATE orders SET bonus=? WHERE id=?', (bonus, row['id']))
            return {'amountMinor': row['amount'], 'alreadyClaimed': bool(row['credited']), 'orderId': row['id']}

    def wallet(self, owner):
        with self.connection() as db:
            row = db.execute('SELECT * FROM wallets WHERE owner=?', (owner,)).fetchone()
            return {'balanceMinor': row['balance'] if row else 0, 'blocked': bool(row['blocked']) if row else False, 'premium': False}

    def pending(self):
        with self.connection() as db:
            return [dict(r) for r in db.execute("SELECT * FROM orders WHERE status!='REFUNDED' ORDER BY attempted LIMIT 20")]

    def attempted(self, order_id):
        with self.connection() as db:
            db.execute('UPDATE orders SET attempted=? WHERE id=?', (time.time_ns(), order_id))

    def sent(self, order_id):
        with self.connection() as db:
            db.execute('UPDATE orders SET sent=1 WHERE id=?', (order_id,))

    def alerts(self):
        with self.connection() as db:
            return [dict(r) for r in db.execute('SELECT id,body FROM alerts WHERE delivered=0 LIMIT 20')]

    def acknowledge(self, alert_id):
        with self.connection() as db:
            db.execute('UPDATE alerts SET delivered=1 WHERE id=?', (alert_id,))
