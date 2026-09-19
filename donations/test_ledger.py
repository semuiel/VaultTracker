import tempfile
import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from ledger import Ledger
from service import Service


class Tests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.ledger = Ledger(Path(self.temp.name)/'test.sqlite')
        self.owner = str(uuid.uuid4())

    def tearDown(self):
        self.temp.cleanup()

    def order(self, status='PAID', reviewed=False):
        return self.ledger.sync('ORDER123', 'Buyer', 12345, status, reviewed)

    def test_concurrent_redeem_and_restart_credit_once(self):
        row = self.order()
        with ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(lambda _: self.ledger.claim(self.owner, row['code']), range(12)))
        again = Ledger(self.ledger.path)
        self.assertEqual(12345, again.wallet(self.owner)['balanceMinor'])
        self.assertTrue(again.claim(self.owner, row['code'])['alreadyClaimed'])
        with self.assertRaises(ValueError):
            again.claim(str(uuid.uuid4()), row['code'])

    def test_any_review_bonus_once_then_refund_reverses_both(self):
        row = self.order()
        self.ledger.claim(self.owner, row['code'])
        for _ in range(3):
            self.order('CLOSED', True)
        self.assertEqual(13579, self.ledger.wallet(self.owner)['balanceMinor'])
        for _ in range(3):
            self.order('REFUNDED')
        self.assertEqual(0, self.ledger.wallet(self.owner)['balanceMinor'])
        self.assertEqual(1, len(self.ledger.alerts()))
        self.order('PAID')
        with self.assertRaises(ValueError):
            self.ledger.claim(self.owner, row['code'])

    def test_refund_before_claim_and_unknown_payment(self):
        row = self.order('REFUNDED')
        with self.assertRaises(ValueError):
            self.ledger.claim(self.owner, row['code'])
        with self.assertRaises(ValueError):
            self.order('UNKNOWN')
        self.assertEqual(0, self.ledger.wallet(self.owner)['balanceMinor'])

    def test_refund_after_spending_blocks_future_purchases(self):
        row = self.order()
        self.ledger.claim(self.owner, row['code'])
        # Future premium module will use the same ledger. Simulate an already delivered purchase.
        with self.ledger.connection() as db:
            Ledger.entry(db, 'test-premium', self.owner, -10000)
        self.order('REFUNDED')
        self.assertEqual(-10000, self.ledger.wallet(self.owner)['balanceMinor'])
        self.assertTrue(self.ledger.wallet(self.owner)['blocked'])

    def test_service_rechecks_provider_before_credit(self):
        row = self.order()
        class Provider:
            def verify(inner, order_id):
                return dict(order_id=order_id, buyer='Buyer', amount=12345, status='REFUNDED')
        service = Service({}, self.ledger, Provider())
        with self.assertRaises(ValueError):
            service.redeem(self.owner, row['code'])
        self.assertEqual(0, self.ledger.wallet(self.owner)['balanceMinor'])

    def test_rate_limit_and_amount_change(self):
        row = self.order()
        with self.assertRaises(ValueError):
            self.ledger.sync('ORDER123', 'Buyer', 99999, 'PAID')
        service = Service({}, self.ledger, None)
        for _ in range(6):
            with self.assertRaises(ValueError):
                service.redeem(self.owner, '0'*32)


if __name__ == '__main__':
    unittest.main()
