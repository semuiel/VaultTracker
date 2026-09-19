import http.client
import json
from pathlib import Path
import tempfile
import threading
import unittest
import uuid
from http.server import HTTPServer
from ledger import Ledger
from service import Service, handler


class HttpTests(unittest.TestCase):
    def test_website_key_has_only_balance_access_and_retries_are_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            ledger=Ledger(Path(directory)/'ledger.sqlite')
            config={'api_key':'a'*48,'website_api_key':'b'*48}
            server=HTTPServer(('127.0.0.1',0),handler(Service(config,ledger,None)))
            thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
            owner=str(uuid.uuid4())
            def request(path,body):
                conn=http.client.HTTPConnection('127.0.0.1',server.server_port,timeout=5)
                try:
                    conn.request('POST',path,json.dumps(body),{'Authorization':'Bearer '+config['website_api_key']})
                    response=conn.getresponse();return response.status,json.loads(response.read())
                finally:conn.close()
            try:
                body={'owner':owner,'requestId':str(uuid.uuid4()),'action':'add','actor':'forged-admin','name':'Alex','amountMinor':1000}
                self.assertEqual(200,request('/donation-change',body)[0]);self.assertEqual(200,request('/donation-change',body)[0])
                self.assertEqual(1000,request('/wallet',{'owner':owner})[1]['balanceMinor'])
                self.assertEqual('website',ledger.audit(0)['rows'][0]['actor'])
                body.update(requestId=str(uuid.uuid4()),action='spend',amountMinor=250)
                self.assertEqual(750,request('/donation-change',body)[1]['balanceMinor'])
                body.update(requestId=str(uuid.uuid4()),action='grant',days=30)
                self.assertEqual(403,request('/donation-change',body)[0])
                self.assertEqual(403,request('/donation-audit',{})[0]);self.assertEqual(403,request('/premium-tasks',{})[0])
            finally:server.shutdown();server.server_close();thread.join(5)

    def test_auth_redeem_review_refund_through_http(self):
        with tempfile.TemporaryDirectory() as directory:
            ledger = Ledger(Path(directory) / 'ledger.sqlite')
            class Provider:
                state = 'PAID'
                reviewed = False
                def verify(self, order_id):
                    return dict(order_id=order_id, buyer='buyer', amount=10000,
                                status=self.state, reviewed=self.reviewed)
            provider = Provider()
            config = dict(api_key='test-only-' + 'a'*48, review_bonus_percent=10)
            service = Service(config, ledger, provider)
            code = service.refresh('ORDER123')['code']
            owner = str(uuid.uuid4())
            server = HTTPServer(('127.0.0.1', 0), handler(service))
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            def request(path, body, authenticated=True):
                connection = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=5)
                headers = {'Content-Type': 'application/json'}
                if authenticated:
                    headers['Authorization'] = 'Bearer ' + config['api_key']
                try:
                    connection.request('POST', path, json.dumps(body), headers)
                    response = connection.getresponse()
                    return response.status, json.loads(response.read())
                finally:
                    connection.close()
            try:
                self.assertEqual(401, request('/redeem', dict(owner=owner, code=code), False)[0])
                self.assertEqual(0, ledger.wallet(owner)['balanceMinor'])
                status, result = request('/redeem', dict(owner=owner, code=code))
                self.assertEqual(200, status)
                self.assertEqual(10000, result['balanceMinor'])
                self.assertFalse(result['alreadyClaimed'])
                self.assertTrue(request('/redeem', dict(owner=owner, code=code))[1]['alreadyClaimed'])
                provider.state, provider.reviewed = 'CLOSED', True
                service.refresh('ORDER123')
                self.assertEqual(11000, request('/wallet', dict(owner=owner))[1]['balanceMinor'])
                provider.state = 'REFUNDED'
                service.refresh('ORDER123')
                self.assertEqual(0, request('/wallet', dict(owner=owner))[1]['balanceMinor'])
                self.assertEqual(400, request('/redeem', dict(owner=owner, code=code))[0])
                alerts = request('/alerts', {})[1]['alerts']
                self.assertEqual(1, len(alerts))
                self.assertEqual(200, request('/ack', dict(id=alerts[0]['id']))[0])
                self.assertEqual([], request('/alerts', {})[1]['alerts'])
            finally:
                server.shutdown()
                server.server_close()
                thread.join(5)

    def test_new_orders_are_checked_while_scanning_history(self):
        class Provider:
            def __init__(self):
                self.calls = []
            def recent(self, cursor=None):
                self.calls.append(cursor)
                return ('page2', ['NEW']) if cursor is None else ('page3', ['OLD'])
        provider = Provider()
        service = Service({}, None, provider)
        self.assertEqual(['NEW'], service.discover())
        self.assertEqual(['NEW', 'OLD'], service.discover())
        self.assertEqual([None, None, 'page2'], provider.calls)


if __name__ == '__main__':
    unittest.main()
