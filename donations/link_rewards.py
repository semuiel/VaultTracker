"""UUID-scoped, transactional one-time rewards using the existing donation ledger."""
import time
import uuid


class LinkRewards:
    DEFAULT_MINOR = 15000

    def __init__(self, ledger):
        self.ledger = ledger
        with ledger.connection() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS link_reward_settings(
                    id INTEGER PRIMARY KEY CHECK(id=1), amount INTEGER NOT NULL CHECK(amount>=0));
                INSERT OR IGNORE INTO link_reward_settings VALUES(1,15000);
                CREATE TABLE IF NOT EXISTS link_reward_claims(
                    owner TEXT PRIMARY KEY, amount INTEGER NOT NULL, created INTEGER NOT NULL);
            ''')

    def settings(self):
        with self.ledger.connection() as db:
            return {'rewardMinor': db.execute('SELECT amount FROM link_reward_settings WHERE id=1').fetchone()[0]}

    def update(self, actor, amount):
        if type(amount) is not int or not 0 <= amount <= 100_000_000:
            raise ValueError('Введите сумму от 0 до 1000000 монет.')
        if not isinstance(actor, str) or not actor or len(actor) > 128:
            raise ValueError('Invalid actor')
        with self.ledger.connection() as db:
            db.execute('UPDATE link_reward_settings SET amount=? WHERE id=1', (amount,))
            db.execute('INSERT INTO donation_audit(actor,owner,name,action,amount,created) VALUES(?,?,?,?,?,?)',
                       (actor, '', '', 'link_reward_setting', amount, int(time.time())))
        return {'rewardMinor': amount}

    def claim(self, owner, actor, name):
        # The authenticated plugin verifies the current GuardService link. Telegram ID is audit only.
        owner = str(uuid.UUID(owner))
        if not isinstance(actor, str) or not actor or len(actor) > 128 or not isinstance(name, str) or len(name) > 64:
            raise ValueError('Invalid actor')
        with self.ledger.connection() as db:
            previous = db.execute('SELECT amount FROM link_reward_claims WHERE owner=?', (owner,)).fetchone()
            amount = previous['amount'] if previous else db.execute('SELECT amount FROM link_reward_settings WHERE id=1').fetchone()[0]
            if not previous:
                self.ledger.entry(db, 'link-reward:' + owner, owner, amount, source='LINK_REWARD')
                db.execute('INSERT INTO link_reward_claims VALUES(?,?,?)', (owner, amount, int(time.time())))
                db.execute('INSERT INTO donation_audit(actor,owner,name,action,amount,created) VALUES(?,?,?,?,?,?)',
                           (actor, owner, name, 'link_reward', amount, int(time.time())))
            balance = db.execute('SELECT balance FROM wallets WHERE owner=?', (owner,)).fetchone()[0]
            return {'amountMinor': amount, 'balanceMinor': balance, 'alreadyClaimed': bool(previous)}
