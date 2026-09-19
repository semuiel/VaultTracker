import unittest
from types import SimpleNamespace
from funpay_provider import parse_verified_order


class ProviderParsingTests(unittest.TestCase):
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
