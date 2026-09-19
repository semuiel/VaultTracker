"""Durable donation ledger. Amounts are integer hundredths of a coin, never floats."""
import secrets
import sqlite3
import time
import json
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
                CREATE TABLE IF NOT EXISTS premium(owner TEXT PRIMARY KEY, until INTEGER NOT NULL, version INTEGER NOT NULL, applied INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS operations(id TEXT PRIMARY KEY, payload TEXT NOT NULL, result TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS donation_audit(id INTEGER PRIMARY KEY AUTOINCREMENT, actor TEXT NOT NULL, owner TEXT NOT NULL, name TEXT NOT NULL, action TEXT NOT NULL, amount INTEGER NOT NULL, created INTEGER NOT NULL);
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
            premium = db.execute('SELECT * FROM premium WHERE owner=?', (owner,)).fetchone()
            until = premium['until'] if premium else 0
            return {'balanceMinor': row['balance'] if row else 0, 'blocked': bool(row['blocked']) if row else False,
                    'premium': until > time.time(), 'premiumUntil': until,
                    'premiumPending': bool(premium and premium['version'] != premium['applied'])}

    def change(self, owner, request_id, action, actor, name, days=0, amount=0, base_until=0):
        if action not in ('buy', 'grant', 'remove', 'add', 'subtract', 'spend'):
            raise ValueError('Unknown operation')
        if action in ('buy','grant') and (type(days) is not int or days not in (30,60)):
            raise ValueError('Invalid duration')
        if action in ('add','subtract','spend') and (type(amount) is not int or not 0 < amount <= 100_000_000):
            raise ValueError('Invalid amount')
        if type(base_until) is not int or not 0 <= base_until <= 253402300799:
            raise ValueError('Invalid expiry')
        if not actor or len(actor)>128 or len(name)>64 or len(request_id)>128:
            raise ValueError('Invalid actor')
        # Exclude the live LP snapshot from the identity: retries may see the applied expiry.
        payload=json.dumps([owner,action,actor,name,days,amount],ensure_ascii=False)
        with self.connection() as db:
            previous=db.execute('SELECT * FROM operations WHERE id=?',(request_id,)).fetchone()
            if previous:
                if previous['payload'] != payload: raise ValueError('Operation identity mismatch')
                return json.loads(previous['result'])
            db.execute('INSERT OR IGNORE INTO wallets(owner) VALUES(?)',(owner,))
            wallet=db.execute('SELECT * FROM wallets WHERE owner=?',(owner,)).fetchone()
            delta=0
            if action=='buy':
                cost=60000 if days==30 else 100000
                if wallet['blocked']: raise ValueError('Покупки заблокированы после возврата. Обратитесь к администратору.')
                if wallet['balance']<cost: raise ValueError('Недостаточно средств на балансе.')
                delta=-cost
            elif action in ('add','subtract','spend'):
                if action=='spend' and wallet['blocked']: raise ValueError('Покупки заблокированы после возврата.')
                delta=amount if action=='add' else -amount
                if wallet['balance']+delta<0 and action!='add': raise ValueError('Нельзя списать больше текущего баланса.')
            if delta: self.entry(db,request_id+':balance',owner,delta)
            if action in ('buy','grant','remove'):
                old=db.execute('SELECT * FROM premium WHERE owner=?',(owner,)).fetchone()
                until=0 if action=='remove' else max(int(time.time()),old['until'] if old else 0,base_until)+days*86400
                version=(old['version'] if old else 0)+1
                db.execute('INSERT INTO premium(owner,until,version) VALUES(?,?,?) ON CONFLICT(owner) DO UPDATE SET until=excluded.until,version=excluded.version', (owner,until,version))
            balance=db.execute('SELECT balance FROM wallets WHERE owner=?',(owner,)).fetchone()[0]
            result={'balanceMinor':balance,'action':action}
            db.execute('INSERT INTO operations VALUES(?,?,?)',(request_id,payload,json.dumps(result)))
            db.execute('INSERT INTO donation_audit(actor,owner,name,action,amount,created) VALUES(?,?,?,?,?,?)',
                       (actor,owner,name,action,days if action in ('buy','grant') else delta,int(time.time())))
            return result

    def premium_tasks(self):
        with self.connection() as db:
            return [dict(r) for r in db.execute('SELECT owner,until,version FROM premium WHERE applied!=version LIMIT 100')]

    def premium_ack(self, owner, version):
        with self.connection() as db:
            db.execute('UPDATE premium SET applied=? WHERE owner=? AND version=?',(version,owner,version))

    def audit(self, page):
        with self.connection() as db:
            count=db.execute('SELECT COUNT(*) FROM donation_audit').fetchone()[0]
            pages=max(1,(count+19)//20);page=max(0,min(page,pages-1))
            rows=[dict(r) for r in db.execute('SELECT * FROM donation_audit ORDER BY id DESC LIMIT 20 OFFSET ?',(page*20,))]
            return {'rows':rows,'page':page,'pages':pages}

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
