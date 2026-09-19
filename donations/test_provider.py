import unittest
from types import SimpleNamespace
from unittest.mock import patch
from funpay_provider import FunPayProvider, parse_verified_order, order_chat_id


class ProviderParsingTests(unittest.TestCase):
    def test_chat_must_match_both_order_participants(self):
        order = SimpleNamespace(seller_id=12, buyer_id=34,
            html='<div class="chat" data-id="56" data-name="users-12-34"></div>')
        self.assertEqual(56, order_chat_id(order))
        order.buyer_id = 99
        with self.assertRaises(ValueError):
            order_chat_id(order)
        order.buyer_id = 34
        order.html += '<div class="chat" data-id="57" data-name="users-34-12"></div>'
        with self.assertRaises(ValueError):
            order_chat_id(order)

    def test_detected_seller_still_rejects_other_sellers(self):
        account = SimpleNamespace(id=123)
        account.get = lambda: account
        account.get_order = lambda order_id: SimpleNamespace(seller_id=456, subcategory=SimpleNamespace(id=1753))
        with patch('FunPayAPI.Account', return_value=account):
            provider = FunPayProvider(dict(golden_key='test-only', seller_id=0, category_id=1753))
            self.assertEqual(123, provider.seller_id)
            with self.assertRaises(ValueError):
                provider.verify('ORDER123')

    def test_pinned_seller_mismatch_rejects_login(self):
        account = SimpleNamespace(id=123)
        account.get = lambda: account
        with patch('FunPayAPI.Account', return_value=account):
            with self.assertRaises(ValueError):
                FunPayProvider(dict(golden_key='test-only', seller_id=456))

    def order(self, status='Закрыт', quantity='100 шт.', review_html=''):
        return SimpleNamespace(id='ABC12345', buyer_username='buyer', review=None,
                               html=f'<span class="text-success">{status}</span>'
                                    f'<div class="param-item"><h5>Количество</h5><div>{quantity}</div></div>'
                                    + review_html)

    def test_every_rating_counts_even_without_review_text(self):
        for stars in range(1, 6):
            with self.subTest(stars=stars):
                order = self.order(review_html=f'<div class="order-review"><div class="rating"><div class="rating{stars}"></div></div></div>')
                verified = parse_verified_order(order, {})
                self.assertTrue(verified['reviewed'])
                self.assertEqual(10000, verified['amount'])

    def test_open_order_requires_both_paid_listing_and_refund_action(self):
        order = self.order()
        order.html = '<div class="param-item"><h5>Количество</h5><div>1 шт.</div></div>'
        with self.assertRaises(ValueError):
            parse_verified_order(order, {}, True)
        order.html += '<button class="btn-refund" data-target=".modal-refund">Вернуть деньги покупателю</button>'
        with self.assertRaises(ValueError):
            parse_verified_order(order, {})
        result = parse_verified_order(order, {}, True)
        self.assertEqual('PAID', result['status'])
        self.assertEqual(100, result['amount'])

    def test_other_ratings_and_missing_reviews_do_not_count(self):
        for html in ('', '<div class="rating"><div class="rating5"></div></div>',
                     '<div class="order-review"><div class="rating"><div class="rating0"></div></div></div>'):
            self.assertFalse(parse_verified_order(self.order(review_html=html), {})['reviewed'])

    def test_unknown_status_and_ambiguous_quantity_fail_closed(self):
        for order in (self.order(status='Ожидает оплаты'), self.order(quantity='100.5'),
                      self.order(quantity='0'), self.order(quantity='100001'),
                      self.order(review_html='<span class="text-warning">Возврат</span>')):
            with self.assertRaises(ValueError):
                parse_verified_order(order, {})


if __name__ == '__main__':
    unittest.main()
