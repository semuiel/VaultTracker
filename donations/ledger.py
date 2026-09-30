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
                CREATE TABLE IF NOT EXISTS alert_deliveries(alert_id TEXT NOT NULL, recipient INTEGER NOT NULL,
                    delivered INTEGER NOT NULL DEFAULT 0, due INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(alert_id,recipient));
                CREATE INDEX IF NOT EXISTS alert_deliveries_due ON alert_deliveries(delivered,due);
                CREATE TABLE IF NOT EXISTS premium(owner TEXT PRIMARY KEY, until INTEGER NOT NULL, version INTEGER NOT NULL, applied INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS operations(id TEXT PRIMARY KEY, payload TEXT NOT NULL, result TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS donation_audit(id INTEGER PRIMARY KEY AUTOINCREMENT, actor TEXT NOT NULL, owner TEXT NOT NULL, name TEXT NOT NULL, action TEXT NOT NULL, amount INTEGER NOT NULL, created INTEGER NOT NULL);
            ''')
            if not any(row['name'] == 'stars' for row in db.execute('PRAGMA table_info(orders)')):
                db.execute('ALTER TABLE orders ADD COLUMN stars INTEGER')

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

    def sync(self, order_id, buyer, amount, status, reviewed=False, stars=None):
        if status not in ('PAID', 'CLOSED', 'REFUNDED'):
            raise ValueError('Unverified order')
        stars = stars if type(stars) is int and 1 <= stars <= 5 else None
        now = int(time.time())
        with self.connection() as db:
            existing = db.execute('SELECT * FROM orders WHERE id=?', (order_id,)).fetchone()
            if status == 'REFUNDED' and amount is None and existing:
                amount = existing['amount']
            if type(amount) is not int or not 0 < amount <= 100_000_000:
                raise ValueError('Unverified order')
            db.execute('INSERT OR IGNORE INTO orders(id,buyer,amount,code,status,checked,created) VALUES(?,?,?,?,?,?,?)',
                       (order_id, buyer, amount, secrets.token_hex(16).upper(), status, now, now))
            row = db.execute('SELECT * FROM orders WHERE id=?', (order_id,)).fetchone()
            if row['amount'] != amount or row['buyer'] != buyer and status != 'REFUNDED':
                raise ValueError('Order amount or buyer changed; manual review required')
            # A refunded order is terminal. Reopened orders need a new payment/order.
            if row['status'] == 'REFUNDED':
                status = 'REFUNDED'
            db.execute('UPDATE orders SET status=?, reviewed=?, stars=?, checked=? WHERE id=?',
                       (status, int(stars is not None), stars, now, order_id))
            if status == 'REFUNDED' and row['credited'] and not row['reversed']:
                self.entry(db, order_id + ':refund', row['owner'], -row['amount'] - row['bonus'])
                db.execute('UPDATE orders SET reversed=1 WHERE id=?', (order_id,))
                balance = db.execute('SELECT balance FROM wallets WHERE owner=?', (row['owner'],)).fetchone()[0]
                if balance < 0:
                    db.execute('UPDATE wallets SET blocked=1 WHERE owner=?', (row['owner'],))
                db.execute('INSERT OR IGNORE INTO alerts VALUES(?,?,0)',
                           (order_id + ':refund', f'Возврат FunPay #{order_id}. Игрок UUID {row["owner"]}. Списано {(row["amount"]+row["bonus"])/100:.2f}. Баланс {balance/100:.2f}.'))
            if status == 'CLOSED' and row['status'] != 'CLOSED' and row['credited']:
                db.execute('INSERT OR IGNORE INTO alerts VALUES(?,?,0)',
                           (order_id + ':confirmed', f'Заказ FunPay #{order_id} подтверждён. Игрок UUID {row["owner"]}. Сумма {row["amount"]/100:.2f} доступна для покупок.'))
            if status == 'CLOSED' and stars is not None and row['credited'] and not row['reversed'] and not row['bonus']:
                bonus = amount * stars // 100
                if bonus:
                    self.entry(db, order_id + ':review', row['owner'], bonus)
                    db.execute('UPDATE orders SET bonus=? WHERE id=?', (bonus, order_id))
                    balance = db.execute('SELECT balance FROM wallets WHERE owner=?', (row['owner'],)).fetchone()[0]
                    db.execute('INSERT OR IGNORE INTO alerts VALUES(?,?,0)',
                               (order_id + ':review', f'{"★"*stars} · бонус {stars}% за отзыв FunPay #{order_id}. Игрок UUID {row["owner"]}. Зачислено {bonus/100:.2f}. Баланс {balance/100:.2f}.'))
            return db.execute('SELECT * FROM orders WHERE id=?', (order_id,)).fetchone()

    def lookup(self, code):
        if len(code) != 32 or any(c not in '0123456789ABCDEF' for c in code):
            return None
        with self.connection() as db:
            row = db.execute('SELECT * FROM orders WHERE code=?', (code,)).fetchone()
            return dict(row) if row else None

    def claim(self, owner, code):
        with self.connection() as db:
            row = db.execute('SELECT * FROM orders WHERE code=?', (code,)).fetchone()
            if not row or row['status'] not in ('PAID', 'CLOSED') or int(time.time())-row['checked'] > 30:
                raise ValueError('Код недоступен или оплата не подтверждена. Повторите проверку позже.')
            if row['owner'] and row['owner'] != owner:
                raise ValueError('Код недоступен или уже использован.')
            credited_now = 0
            if not row['credited']:
                self.entry(db, row['id'] + ':payment', owner, row['amount'])
                db.execute('UPDATE orders SET owner=?,credited=1 WHERE id=?', (owner, row['id']))
                credited_now += row['amount']
            if row['status'] == 'CLOSED' and row['stars'] in (1, 2, 3, 4, 5) and not row['bonus']:
                bonus = row['amount'] * row['stars'] // 100
                if bonus:
                    self.entry(db, row['id'] + ':review', owner, bonus)
                    db.execute('UPDATE orders SET bonus=? WHERE id=?', (bonus, row['id']))
                    credited_now += bonus
                    if row['credited']:
                        balance = db.execute('SELECT balance FROM wallets WHERE owner=?', (owner,)).fetchone()[0]
                        db.execute('INSERT OR IGNORE INTO alerts VALUES(?,?,0)',
                                   (row['id'] + ':review', f'{"★"*row["stars"]} · бонус {row["stars"]}% за отзыв FunPay #{row["id"]}. Игрок UUID {owner}. Зачислено {bonus/100:.2f}. Баланс {balance/100:.2f}.'))
            if not row['credited']:
                balance = db.execute('SELECT balance FROM wallets WHERE owner=?', (owner,)).fetchone()[0]
                pending = ' Ожидает подтверждения заказа.' if row['status'] == 'PAID' else ''
                db.execute('INSERT OR IGNORE INTO alerts VALUES(?,?,0)',
                           (row['id'] + ':payment', f'Пополнение FunPay #{row["id"]}. Игрок UUID {owner}. Зачислено {credited_now/100:.2f}. Баланс {balance/100:.2f}.{pending}'))
                if row['status'] == 'CLOSED':
                    db.execute('INSERT OR IGNORE INTO alerts VALUES(?,?,0)',
                               (row['id'] + ':confirmed', f'Заказ FunPay #{row["id"]} подтверждён. Игрок UUID {owner}. Сумма {row["amount"]/100:.2f} доступна для покупок.'))
            return {'amountMinor': row['amount'], 'alreadyClaimed': bool(row['credited']), 'orderId': row['id']}

    def wallet(self, owner):
        with self.connection() as db:
            row = db.execute('SELECT * FROM wallets WHERE owner=?', (owner,)).fetchone()
            premium = db.execute('SELECT * FROM premium WHERE owner=?', (owner,)).fetchone()
            until = premium['until'] if premium else 0
            balance = row['balance'] if row else 0
            pending = self.pending_amount(db, owner)
            return {'balanceMinor': balance, 'availableMinor': max(0, balance - pending),
                    'pendingMinor': pending, 'blocked': bool(row['blocked']) if row else False,
                    'premium': until < 0 or until > time.time(), 'premiumUntil': until,
                    'premiumPending': bool(premium and premium['version'] != premium['applied'])}

    @staticmethod
    def pending_amount(db, owner):
        return db.execute("SELECT COALESCE(SUM(amount),0) FROM orders WHERE owner=? AND credited=1 AND reversed=0 AND status='PAID'",
                          (owner,)).fetchone()[0]

    def change(self, owner, request_id, action, actor, name, days=0, amount=0, base_until=0):
        if action not in ('buy', 'grant', 'remove', 'add', 'subtract', 'spend', 'unblock'):
            raise ValueError('Unknown operation')
        if action == 'buy' and (type(days) is not int or days not in (30,60)):
            raise ValueError('Invalid duration')
        if action == 'grant' and (type(days) is not int or days != -1 and not 1 <= days <= 36500):
            raise ValueError('Invalid duration')
        if action in ('add','subtract','spend') and (type(amount) is not int or not 0 < amount <= 100_000_000):
            raise ValueError('Invalid amount')
        if type(base_until) is not int or not -1 <= base_until <= 253402300799:
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
            pending=self.pending_amount(db,owner)
            available=wallet['balance']-pending
            if action=='unblock':
                if available<0:
                    raise ValueError('Сначала погасите долг подтверждёнными средствами.')
                db.execute('UPDATE wallets SET blocked=0 WHERE owner=?',(owner,))
            delta=0
            if action=='buy':
                cost=60000 if days==30 else 100000
                if wallet['blocked']: raise ValueError('Покупки заблокированы после возврата. Обратитесь к администратору.')
                if available<cost:
                    if wallet['balance']>=cost and pending:
                        raise ValueError('Средства станут доступны после подтверждения заказа на FunPay.')
                    raise ValueError('Недостаточно средств на балансе.')
                delta=-cost
            elif action in ('add','subtract','spend'):
                if action=='spend' and wallet['blocked']: raise ValueError('Покупки заблокированы после возврата.')
                delta=amount if action=='add' else -amount
                if wallet['balance']+delta<0 and action!='add': raise ValueError('Нельзя списать больше текущего баланса.')
                if action=='spend' and available<amount:
                    raise ValueError('Средства станут доступны после подтверждения заказа на FunPay.')
            if delta: self.entry(db,request_id+':balance',owner,delta)
            if action in ('buy','grant','remove'):
                old=db.execute('SELECT * FROM premium WHERE owner=?',(owner,)).fetchone()
                previous_until=old['until'] if old else 0
                if action=='remove': until=0
                elif days<0: until=-1
                elif previous_until<0 or base_until<0: raise ValueError('У игрока уже бессрочный премиум.')
                else: until=max(int(time.time()),previous_until,base_until)+days*86400
                version=(old['version'] if old else 0)+1
                db.execute('INSERT INTO premium(owner,until,version) VALUES(?,?,?) ON CONFLICT(owner) DO UPDATE SET until=excluded.until,version=excluded.version', (owner,until,version))
            balance=db.execute('SELECT balance FROM wallets WHERE owner=?',(owner,)).fetchone()[0]
            result={'balanceMinor':balance,'availableMinor':max(0,balance-pending),'pendingMinor':pending,'action':action}
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

    def recipient_alerts(self, recipients):
        if not isinstance(recipients, list) or len(recipients)>1000 or any(type(r) is not int or r<=0 for r in recipients):
            raise ValueError('Invalid recipients')
        recipients=list(dict.fromkeys(recipients))
        if not recipients:
            return []
        with self.connection() as db:
            for recipient in recipients:
                db.execute('INSERT OR IGNORE INTO alert_deliveries(alert_id,recipient) SELECT id,? FROM alerts WHERE delivered=0',(recipient,))
            placeholders=','.join('?' for _ in recipients)
            return [dict(row) for row in db.execute(
                'SELECT a.id,a.body,d.recipient FROM alerts a JOIN alert_deliveries d ON a.id=d.alert_id '
                f'WHERE a.delivered=0 AND d.delivered=0 AND d.due<=? AND d.recipient IN ({placeholders}) ORDER BY d.due,a.id,d.recipient LIMIT 20',
                (int(time.time()),*recipients))]

    def acknowledge_recipient(self, alert_id, recipient, success=True):
        with self.connection() as db:
            if success:
                db.execute('UPDATE alert_deliveries SET delivered=1 WHERE alert_id=? AND recipient=?',(alert_id,recipient))
                db.execute('UPDATE alerts SET delivered=1 WHERE id=? AND EXISTS(SELECT 1 FROM alert_deliveries WHERE alert_id=?) '
                           'AND NOT EXISTS(SELECT 1 FROM alert_deliveries WHERE alert_id=? AND delivered=0)',(alert_id,alert_id,alert_id))
            else:
                db.execute('UPDATE alert_deliveries SET due=? WHERE alert_id=? AND recipient=? AND delivered=0',
                           (int(time.time())+300,alert_id,recipient))
