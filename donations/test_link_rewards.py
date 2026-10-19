import concurrent.futures
import http.client
import json
import sqlite3
import tempfile
import threading
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch
from http.server import HTTPServer
from ledger import Ledger
from service import Service, handler


class LinkRewardTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / 'ledger.sqlite'
        self.ledger = Ledger(self.path)
        self.owner = str(uuid.uuid4())

    def tearDown(self):
        self.temp.cleanup()

    def test_default_old_and_new_players_each_receive_once(self):
        # An old wallet is not evidence that its owner already claimed this new reward.
        self.ledger.change(self.owner, 'old', 'add', 'root', 'Old', amount=100)
        self.assertEqual(15000, self.ledger.link_rewards.settings()['rewardMinor'])
        first = self.ledger.link_rewards.claim(self.owner, 'tg1', 'Old')
        self.assertEqual(15100, first['balanceMinor'])
        self.assertFalse(first['alreadyClaimed'])
        new_owner = str(uuid.uuid4())
        self.assertEqual(15000, self.ledger.link_rewards.claim(new_owner, 'tg2', 'New')['amountMinor'])

    def test_concurrent_callbacks_tg_change_and_restart_keep_one_uuid_claim(self):
        def claim(i):
            return self.ledger.link_rewards.claim(self.owner.upper() if i % 2 else self.owner, str(i), 'Player')
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            replies = list(pool.map(claim, range(12)))
        self.assertEqual(1, sum(not r['alreadyClaimed'] for r in replies))
        restarted = Ledger(self.path)
        self.assertTrue(restarted.wallet(self.owner)['linkRewardClaimed'])
        self.assertTrue(restarted.link_rewards.claim(self.owner, 'another-telegram', 'Renamed')['alreadyClaimed'])
        self.assertEqual(15000, restarted.wallet(self.owner)['balanceMinor'])
        with restarted.connection() as db:
            self.assertEqual(1, db.execute("SELECT COUNT(*) FROM entries WHERE source='LINK_REWARD'").fetchone()[0])
            self.assertEqual(1, db.execute('SELECT COUNT(*) FROM link_reward_claims').fetchone()[0])
            self.assertEqual(1, db.execute("SELECT COUNT(*) FROM donation_audit WHERE action='link_reward'").fetchone()[0])

    def test_setting_changes_future_claims_and_stores_actual_award(self):
        self.ledger.link_rewards.claim(self.owner, 'tg', 'Old')
        self.ledger.link_rewards.update('root', 12345)
        self.assertEqual(12345, Ledger(self.path).link_rewards.settings()['rewardMinor'])
        second = str(uuid.uuid4())
        self.assertEqual(12345, self.ledger.link_rewards.claim(second, 'tg2', 'New')['amountMinor'])
        self.assertEqual(15000, self.ledger.link_rewards.claim(self.owner, 'tg3', 'Old')['amountMinor'])
        self.assertEqual(15000, self.ledger.wallet(self.owner)['balanceMinor'])
        for bad in (-1, 100_000_001, True, 1.25, '150'):
            with self.assertRaises(ValueError):
                self.ledger.link_rewards.update('root', bad)

    def test_claim_rolls_back_both_money_and_identity_on_error(self):
        original = self.ledger.entry
        def failed(*args, **kwargs):
            original(*args, **kwargs)
            raise RuntimeError('simulated transaction failure')
        with patch.object(self.ledger, 'entry', side_effect=failed):
            with self.assertRaises(RuntimeError):
                self.ledger.link_rewards.claim(self.owner, 'tg', 'Player')
        self.assertFalse(self.ledger.wallet(self.owner)['linkRewardClaimed'])
        self.assertEqual(0, self.ledger.wallet(self.owner)['balanceMinor'])
        self.assertEqual(15000, self.ledger.link_rewards.claim(self.owner, 'tg', 'Player')['balanceMinor'])

    def test_zero_amount_claim_is_still_durable_and_does_not_unblock_debt(self):
        self.ledger.link_rewards.update('root', 0)
        self.assertEqual(0, self.ledger.link_rewards.claim(self.owner, 'tg', 'Player')['amountMinor'])
        self.ledger.link_rewards.update('root', 15000)
        self.assertEqual(0, self.ledger.link_rewards.claim(self.owner, 'tg', 'Player')['amountMinor'])
        other = str(uuid.uuid4())
        with self.ledger.connection() as db:
            db.execute('INSERT INTO wallets VALUES(?, -10000, 1)', (other,))
        self.ledger.link_rewards.claim(other, 'tg2', 'Player')
        self.assertEqual(5000, self.ledger.wallet(other)['balanceMinor'])
        self.assertTrue(self.ledger.wallet(other)['blocked'])

    def test_http_private_key_boundary_and_exact_decimal_minor_units(self):
        config = {'api_key': 'a'*48, 'website_api_key': 'b'*48}
        server = HTTPServer(('127.0.0.1', 0), handler(Service(config, self.ledger, None)))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        def post(path, body, key):
            conn = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=5)
            try:
                conn.request('POST', path, json.dumps(body), {'Authorization': 'Bearer '+key})
                r = conn.getresponse()
                return r.status, json.loads(r.read())
            finally:
                conn.close()
        try:
            body = dict(owner=self.owner, actor='42', name='Player')
            for endpoint in ('/link-reward-settings', '/link-reward-update', '/link-reward-claim'):
                self.assertEqual(403, post(endpoint, body, config['website_api_key'])[0])
                self.assertEqual(401, post(endpoint, body, 'bad')[0])
            self.assertEqual(200, post('/link-reward-update', dict(actor='root', rewardMinor=15025), config['api_key'])[0])
            first = post('/link-reward-claim', body, config['api_key'])
            self.assertEqual((200, 15025), (first[0], first[1]['amountMinor']))
            self.assertTrue(post('/link-reward-claim', body, config['api_key'])[1]['alreadyClaimed'])
            self.assertTrue(post('/wallet', dict(owner=self.owner), config['api_key'])[1]['linkRewardClaimed'])
        finally:
            server.shutdown(); server.server_close(); thread.join(5)


class LegacySchemaTests(unittest.TestCase):
    def test_existing_premium_entries_operations_and_balances_survive_migration(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)/'old.sqlite'
            with sqlite3.connect(path) as db:
                db.executescript('''
                    CREATE TABLE entries(id TEXT PRIMARY KEY,owner TEXT NOT NULL,amount INTEGER NOT NULL,created INTEGER NOT NULL);
                    CREATE TABLE premium(owner TEXT PRIMARY KEY,until INTEGER NOT NULL,version INTEGER NOT NULL,applied INTEGER NOT NULL DEFAULT 0);
                    CREATE TABLE wallets(owner TEXT PRIMARY KEY,balance INTEGER NOT NULL DEFAULT 0,blocked INTEGER NOT NULL DEFAULT 0);
                    CREATE TABLE operations(id TEXT PRIMARY KEY,payload TEXT NOT NULL,result TEXT NOT NULL);
                    INSERT INTO entries VALUES('seed','player',10000,1);
                    INSERT INTO premium VALUES('player',2000000000,3,2);
                    INSERT INTO wallets VALUES('player',10000,0);
                ''')
                db.execute('INSERT INTO operations VALUES(?,?,?)', ('old-buy',
                           json.dumps(['player','buy','user','Player',30,0]), json.dumps({'balanceMinor':10000})))
            db.close()
            ledger = Ledger(path)
            self.assertEqual(2000000000, ledger.wallet('player')['premiumUntil'])
            self.assertEqual(10000, ledger.wallet('player')['balanceMinor'])
            self.assertEqual(10000, ledger.change('player','old-buy','buy','user','Player',days=30)['balanceMinor'])
            self.assertEqual(2000000000, ledger.wallet('player')['premiumUntil'])
            self.assertFalse(ledger.wallet('player')['linkRewardClaimed'])
            self.assertEqual('premium', ledger.premium_tasks()[0]['product'])
            with ledger.connection() as db:
                self.assertEqual(10000, db.execute('SELECT amount FROM entries WHERE id=\'seed\'').fetchone()[0])
            Ledger(path)  # Repeating startup migrations is harmless.


if __name__ == '__main__':
    unittest.main()
