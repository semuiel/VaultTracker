"""FunPay adapter. Unknown markup/status/amount fails closed, never credits by chat text."""
import re


class FunPayProvider:
    def __init__(self, config):
        from FunPayAPI import Account
        self.config = config
        self.account = Account(config['golden_key'], requests_timeout=8).get()
        self.seller_id = int(self.account.id)
        if self.seller_id <= 0 or (config.get('seller_id') and self.seller_id != config['seller_id']):
            raise ValueError('Wrong FunPay seller')

    def recent(self, cursor=None):
        cursor, rows = self.account.get_sells(start_from=cursor, section='lot-' + str(self.config['category_id']))
        return cursor, [r.id for r in rows if self.config['order_marker'] in r.description]

    def verify(self, order_id):
        if not re.fullmatch(r'[A-Z0-9]{6,32}', order_id):
            raise ValueError('Invalid order id')
        order = self.account.get_order(order_id)
        if order.seller_id != self.seller_id or order.subcategory.id != self.config['category_id']:
            raise ValueError('Wrong seller or category')
        if self.config['order_marker'] not in (order.short_description or ''):
            raise ValueError('Wrong offer')
        return parse_verified_order(order, self.config)

    def send_code(self, row):
        # Retry sends the SAME code; it cannot create another balance credit.
        chat = self.account.get_chat_by_name(row['buyer'], True)
        if chat is None:
            raise ValueError('Buyer chat unavailable')
        text = (f'Заказ #{row["id"]}. Код пополнения: {row["code"]}\n'
                f'Сумма: {row["amount"]/100:.2f} монет. Введите код в личном кабинете сервера: '
                'Пожертвования → Ввести код. Никому не передавайте код. '
                'Подтверждайте получение на FunPay только после зачисления баланса.')
        self.account.send_message(chat.id, text)


def parse_verified_order(order, config):
    from bs4 import BeautifulSoup
    soup = BeautifulSoup(order.html, 'html.parser')
    # Library 1.1.0 defaults unknown statuses to PAID; do not trust that fallback.
    labels = {e.get_text(' ', strip=True) for e in soup.select('.text-warning,.text-success,.text-primary,.text-info')}
    states = {'Оплачен': 'PAID', 'Закрыт': 'CLOSED', 'Возврат': 'REFUNDED'}
    found = {states[s] for s in labels if s in states}
    if len(found) != 1:
        raise ValueError('Unknown FunPay order status markup')
    params = {}
    for item in soup.select('.param-item'):
        heading = item.find('h5')
        value = item.find('div')
        if heading and value:
            params[heading.get_text(strip=True)] = value.get_text(' ', strip=True)
    quantity = params.get('Количество', '').replace('\xa0', ' ')
    match = re.fullmatch(r'([0-9][0-9 ]*)\s*(?:шт\.?)?', quantity)
    if not match:
        raise ValueError('Unknown quantity; refusing to infer from fees or messages')
    units = int(match[1].replace(' ', ''))
    # The offer sells exactly one coin per unit; payment commissions are not coins.
    if not 1 <= units <= config.get('max_units', 100000):
        raise ValueError('Quantity out of bounds')
    status = found.pop()
    review = order.review
    reviewed = bool(review and review.stars in (1, 2, 3, 4, 5))
    # FunPayAPI discards reviews with an empty text. A rating still counts,
    # including one star, but only inside this order's review block.
    if not reviewed:
        rating = soup.select_one('.order-review .rating > div')
        reviewed = bool(rating and any(re.fullmatch(r'rating[1-5]', c)
                                      for c in rating.get('class', [])))
    return {'order_id': order.id, 'buyer': order.buyer_username, 'amount': units * 100,
            'status': status, 'reviewed': reviewed}
