"""FunPay adapter. Unknown markup/status/amount fails closed, never credits by chat text."""
import re


def session_account(key):
    from FunPayAPI import Account
    from FunPayAPI.common import exceptions
    import requests
    from types import MethodType
    from urllib.parse import urlsplit
    account = Account(key, requests_timeout=8)
    session = requests.Session()
    session.cookies.set('golden_key', key, domain='funpay.com', path='/')

    def method(self, request_method, api_method, headers, payload,
               exclude_phpsessid=False, raise_not_200=False):
        url = api_method if api_method.startswith('https://') else 'https://funpay.com/' + api_method
        if urlsplit(url).netloc != 'funpay.com':
            raise ValueError('Unexpected FunPay host')
        # The old library drops all cookies except golden_key and PHPSESSID.
        # Preserve the site's additional CSRF cookies in this private session.
        response = session.request(request_method, url, headers=dict(headers), data=payload,
                                   timeout=self.requests_timeout, allow_redirects=False)
        if response.status_code == 403:
            raise exceptions.UnauthorizedError(response)
        if response.status_code != 200 and raise_not_200:
            raise exceptions.RequestFailedError(response)
        return response

    account.method = MethodType(method, account)
    return account.get()


class FunPayProvider:
    def __init__(self, config):
        self.config = config
        self.account = session_account(config['golden_key'])
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
        # Open paid orders have no status badge. Require fresh, explicit paid
        # evidence from the seller list as well as the order's refund action.
        from bs4 import BeautifulSoup
        soup = BeautifulSoup(order.html, 'html.parser')
        paid = False
        if not soup.select('.text-warning,.text-success,.text-primary,.text-info'):
            _, rows = self.account.get_sells(id=order_id, state='paid')
            matches = [r for r in rows if r.id == order_id]
            if len(matches) == 1:
                row = BeautifulSoup(matches[0].html, 'html.parser').select_one('.tc-item.info')
                paid = row is not None and 'warning' not in row.get('class', [])
        return parse_verified_order(order, self.config, paid)

    def send_code(self, row):
        # Retry sends the SAME code; it cannot create another balance credit.
        order = self.account.get_order(row['id'])
        if (order.seller_id != self.seller_id or order.buyer_username != row['buyer']
                or order.subcategory.id != self.config['category_id']
                or self.config['order_marker'] not in (order.short_description or '')):
            raise ValueError('Wrong order recipient')
        chat_id = order_chat_id(order)
        text = (f'Заказ #{row["id"]}. Код пополнения: {row["code"]}\n'
                f'Сумма: {row["amount"]/100:.2f} монет. Введите код в личном кабинете сервера: '
                'Пожертвования → Ввести код. Никому не передавайте код. '
                'Подтверждайте получение на FunPay только после зачисления баланса.')
        import json
        payload = {
            'objects': json.dumps([{'type': 'chat_node', 'id': chat_id, 'tag': '00000000',
                                   'data': {'node': chat_id, 'last_message': -1, 'content': ''}}]),
            'request': json.dumps({'action': 'chat_message',
                                   'data': {'node': chat_id, 'last_message': -1, 'content': text}}),
            'csrf_token': self.account.csrf_token,
        }
        response = self.account.method('post', 'runner/',
            {'accept': '*/*', 'content-type': 'application/x-www-form-urlencoded; charset=UTF-8',
             'x-requested-with': 'XMLHttpRequest'}, payload, raise_not_200=True).json()
        result = response.get('response')
        if not isinstance(result, dict) or not result or result.get('error') is not None:
            raise ValueError('Message delivery not confirmed')


def order_chat_id(order):
    from bs4 import BeautifulSoup
    nodes = BeautifulSoup(order.html, 'html.parser').select('.chat[data-id][data-name]')
    expected = {int(order.seller_id), int(order.buyer_id)}
    ids = set()
    for node in nodes:
        match = re.fullmatch(r'users-(\d+)-(\d+)', node['data-name'])
        if match and {int(match[1]), int(match[2])} == expected and node['data-id'].isdigit():
            ids.add(int(node['data-id']))
    if len(ids) != 1 or next(iter(ids)) <= 0:
        raise ValueError('Order chat unavailable or ambiguous')
    return ids.pop()


def parse_verified_order(order, config, paid_in_seller_list=False):
    from bs4 import BeautifulSoup
    soup = BeautifulSoup(order.html, 'html.parser')
    # Library 1.1.0 defaults unknown statuses to PAID; do not trust that fallback.
    labels = {e.get_text(' ', strip=True) for e in soup.select('.text-warning,.text-success,.text-primary,.text-info')}
    states = {'Оплачен': 'PAID', 'Закрыт': 'CLOSED', 'Возврат': 'REFUNDED'}
    found = {states[s] for s in labels if s in states}
    if not labels and paid_in_seller_list:
        refund = soup.select_one('button.btn-refund[data-target=".modal-refund"]')
        if refund and refund.get_text(' ', strip=True) == 'Вернуть деньги покупателю':
            found.add('PAID')
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
