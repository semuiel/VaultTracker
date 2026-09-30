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

    def order(self, status='PAID', stars=None):
        return self.ledger.sync('ORDER123', 'Buyer', 12345, status, stars=stars)

    def test_concurrent_redeem_and_restart_credit_once(self):
        row = self.order()
        with ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(lambda _: self.ledger.claim(self.owner, row['code']), range(12)))
        again = Ledger(self.ledger.path)
        self.assertEqual(12345, again.wallet(self.owner)['balanceMinor'])
        self.assertTrue(again.claim(self.owner, row['code'])['alreadyClaimed'])
        alerts = again.alerts()
        self.assertEqual(['ORDER123:payment'], [alert['id'] for alert in alerts])
        self.assertIn('Зачислено 123.45', alerts[0]['body'])
        self.assertIn('Ожидает подтверждения заказа', alerts[0]['body'])
        with self.assertRaises(ValueError):
            again.claim(str(uuid.uuid4()), row['code'])

    def test_rating_bonus_once_then_refund_reverses_both(self):
        row = self.order()
        self.ledger.claim(self.owner, row['code'])
        for _ in range(3):
            self.order('CLOSED', 5)
        self.assertEqual(12962, self.ledger.wallet(self.owner)['balanceMinor'])
        for _ in range(3):
            self.order('REFUNDED')
        self.assertEqual(0, self.ledger.wallet(self.owner)['balanceMinor'])
        self.assertEqual(['ORDER123:payment', 'ORDER123:confirmed', 'ORDER123:review', 'ORDER123:refund'],
                         [alert['id'] for alert in self.ledger.alerts()])
        self.order('PAID')
        with self.assertRaises(ValueError):
            self.ledger.claim(self.owner, row['code'])

    def test_each_star_gives_its_exact_percent_once(self):
        for stars in range(1, 6):
            with self.subTest(stars=stars):
                order_id = f'RATING{stars}'
                row = self.ledger.sync(order_id, 'Buyer', 10000, 'PAID')
                self.ledger.claim(self.owner, row['code'])
                for _ in range(3):
                    self.ledger.sync(order_id, 'Buyer', 10000, 'CLOSED', stars=stars)
                self.assertEqual(stars * 100, self.ledger.lookup(row['code'])['bonus'])
                review_alert = next(a for a in self.ledger.alerts() if a['id'] == order_id + ':review')
                self.assertIn('★' * stars + f' · бонус {stars}%', review_alert['body'])
                self.assertEqual(1, sum(a['id'] == order_id + ':review' for a in self.ledger.alerts()))
        self.assertEqual(51500, self.ledger.wallet(self.owner)['balanceMinor'])

    def test_missing_or_invalid_rating_never_uses_old_bonus_fallback(self):
        row = self.ledger.sync('ORDER123', 'Buyer', 10000, 'CLOSED', reviewed=True)
        self.ledger.claim(self.owner, row['code'])
        for stars in (None, 0, 6, '5', True):
            self.ledger.sync('ORDER123', 'Buyer', 10000, 'CLOSED', stars=stars)
        self.assertEqual(10000, self.ledger.wallet(self.owner)['balanceMinor'])
        self.assertEqual(0, self.ledger.lookup(row['code'])['bonus'])

    def test_refund_before_claim_and_unknown_payment(self):
        row = self.order('REFUNDED')
        with self.assertRaises(ValueError):
            self.ledger.claim(self.owner, row['code'])
        with self.assertRaises(ValueError):
            self.order('UNKNOWN')
        self.assertEqual(0, self.ledger.wallet(self.owner)['balanceMinor'])

    def test_closed_order_claim_reports_payment_and_bonus_once(self):
        row = self.order('CLOSED', 5)
        self.ledger.claim(self.owner, row['code'])
        self.ledger.claim(self.owner, row['code'])
        self.order('CLOSED', 5)
        alerts = self.ledger.alerts()
        self.assertEqual(['ORDER123:payment', 'ORDER123:confirmed'],
                         [alert['id'] for alert in alerts])
        self.assertIn('Зачислено 129.62', alerts[0]['body'])
        self.assertNotIn('Ожидает подтверждения заказа', alerts[0]['body'])
        self.assertIn('подтверждён', alerts[1]['body'])

    def test_confirmation_after_claim_is_separate_and_not_repeated(self):
        row = self.order()
        self.ledger.claim(self.owner, row['code'])
        self.order('CLOSED')
        self.order('CLOSED')
        self.ledger.claim(self.owner, row['code'])
        self.assertEqual(['ORDER123:payment', 'ORDER123:confirmed'],
                         [alert['id'] for alert in self.ledger.alerts()])

    def test_refund_after_spending_blocks_future_purchases(self):
        row = self.order()
        self.ledger.claim(self.owner, row['code'])
        # Future premium module will use the same ledger. Simulate an already delivered purchase.
        with self.ledger.connection() as db:
            Ledger.entry(db, 'test-premium', self.owner, -10000)
        self.order('REFUNDED')
        self.assertEqual(-10000, self.ledger.wallet(self.owner)['balanceMinor'])
        self.assertTrue(self.ledger.wallet(self.owner)['blocked'])

    def test_unconfirmed_payment_is_visible_but_cannot_be_spent_until_closed(self):
        row = self.ledger.sync('ORDER123', 'Buyer', 100000, 'PAID')
        self.ledger.claim(self.owner, row['code'])
        wallet = self.ledger.wallet(self.owner)
        self.assertEqual((100000, 0, 100000),
                         (wallet['balanceMinor'], wallet['availableMinor'], wallet['pendingMinor']))
        with self.assertRaisesRegex(ValueError, 'подтверждения заказа'):
            self.ledger.change(self.owner, str(uuid.uuid4()), 'buy', 'user', 'Player', days=30)
        with self.assertRaisesRegex(ValueError, 'подтверждения заказа'):
            self.ledger.change(self.owner, str(uuid.uuid4()), 'spend', 'website', 'Player', amount=100)
        self.ledger.sync('ORDER123', 'Buyer', 100000, 'CLOSED')
        self.assertEqual(100000, self.ledger.wallet(self.owner)['availableMinor'])
        self.ledger.change(self.owner, str(uuid.uuid4()), 'buy', 'user', 'Player', days=30)
        self.assertEqual(40000, self.ledger.wallet(self.owner)['balanceMinor'])

    def test_confirmed_funds_remain_spendable_while_another_order_waits(self):
        closed = self.ledger.sync('CLOSED123', 'Buyer', 100000, 'CLOSED')
        self.ledger.claim(self.owner, closed['code'])
        pending = self.ledger.sync('PAID123', 'Buyer', 2200, 'PAID')
        self.ledger.claim(self.owner, pending['code'])
        self.assertEqual(100000, self.ledger.wallet(self.owner)['availableMinor'])
        self.ledger.change(self.owner, str(uuid.uuid4()), 'buy', 'user', 'Player', days=30)
        self.assertEqual(40000, self.ledger.wallet(self.owner)['availableMinor'])

    def test_refund_reverses_unconfirmed_credit_and_closed_review_bonus_once(self):
        row = self.ledger.sync('ORDER123', 'Buyer', 2200, 'PAID')
        self.ledger.claim(self.owner, row['code'])
        self.ledger.sync('ORDER123', 'Buyer', 2200, 'CLOSED', stars=5)
        self.assertEqual(2310, self.ledger.wallet(self.owner)['balanceMinor'])
        self.ledger.sync('ORDER123', 'Buyer', 2200, 'REFUNDED')
        self.ledger.sync('ORDER123', 'Buyer', 2200, 'REFUNDED')
        self.assertEqual(0, self.ledger.wallet(self.owner)['balanceMinor'])

    def test_known_refund_uses_original_verified_amount_after_detail_disappears(self):
        row = self.ledger.sync('ORDER123', 'Buyer', 2200, 'PAID')
        self.ledger.claim(self.owner, row['code'])
        self.ledger.sync('ORDER123', 'RenamedBuyer', None, 'REFUNDED')
        self.assertEqual(0, self.ledger.wallet(self.owner)['balanceMinor'])
        with self.assertRaises(ValueError):
            self.ledger.sync('UNKNOWN', 'Buyer', None, 'REFUNDED')

    def test_service_reverses_existing_payment_when_refund_lacks_quantity(self):
        row = self.ledger.sync('ORDER123', 'Buyer', 2200, 'PAID')
        self.ledger.claim(self.owner, row['code'])
        self.ledger.sync('ORDER123', 'Buyer', 2200, 'CLOSED', stars=5)
        class Provider:
            def verify(self, order_id):
                return dict(order_id=order_id, buyer='RenamedBuyer', amount=None,
                            status='REFUNDED', reviewed=False)
        Service({}, self.ledger, Provider()).refresh('ORDER123')
        self.assertEqual(0, self.ledger.wallet(self.owner)['balanceMinor'])

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

    def test_custom_and_permanent_admin_premium(self):
        custom_id = str(uuid.uuid4())
        self.ledger.change(self.owner, custom_id, 'grant', 'admin', 'Alex', days=45)
        custom = self.ledger.wallet(self.owner)
        self.assertTrue(custom['premium'])
        self.assertGreater(custom['premiumUntil'], 0)
        permanent_id = str(uuid.uuid4())
        self.ledger.change(self.owner, permanent_id, 'grant', 'admin', 'Alex', days=-1,
                           base_until=custom['premiumUntil'])
        self.assertEqual(-1, self.ledger.wallet(self.owner)['premiumUntil'])
        self.assertTrue(self.ledger.wallet(self.owner)['premium'])
        self.assertEqual(-1, self.ledger.premium_tasks()[0]['until'])
        with self.assertRaisesRegex(ValueError, 'бессрочный'):
            self.ledger.change(self.owner, str(uuid.uuid4()), 'grant', 'admin', 'Alex', days=10)
        self.ledger.change(self.owner, str(uuid.uuid4()), 'remove', 'admin', 'Alex')
        self.assertFalse(self.ledger.wallet(self.owner)['premium'])
        self.assertEqual([-1, 45], [row['amount'] for row in self.ledger.audit(0)['rows'] if row['action']=='grant'])


if __name__ == '__main__':
    unittest.main()
