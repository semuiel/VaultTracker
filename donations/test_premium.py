import concurrent.futures
import tempfile
import time
import unittest
from pathlib import Path
from ledger import Ledger


class PremiumTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.ledger=Ledger(Path(self.temp.name)/'ledger.sqlite')
        self.ledger.change('player','seed','add','admin','Player',amount=200000)

    def tearDown(self):
        self.temp.cleanup()

    def test_concurrent_purchase_retries_charge_and_extend_once(self):
        def buy(_):return self.ledger.change('player','purchase','buy','user','Player',days=30)
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            list(pool.map(buy,range(8)))
        self.assertEqual(140000,self.ledger.wallet('player')['balanceMinor'])
        task=self.ledger.premium_tasks()[0]
        self.assertAlmostEqual(time.time()+30*86400,task['until'],delta=5)
        self.ledger.premium_ack('player',task['version'])
        self.assertEqual([],self.ledger.premium_tasks())
        self.ledger.change('player','purchase','buy','user','Player',days=30,base_until=task['until'])
        self.assertEqual([],self.ledger.premium_tasks())

    def test_extensions_removal_and_stale_ack_survive_restart(self):
        self.ledger.change('player','grant','grant','admin','Player',days=30)
        first=self.ledger.premium_tasks()[0]
        self.ledger.change('player','buy','buy','user','Player',days=60)
        self.assertEqual(first['until']+60*86400,self.ledger.premium_tasks()[0]['until'])
        self.ledger.change('player','remove','remove','admin2','Player')
        self.ledger.premium_ack('player',first['version'])
        restarted=Ledger(self.ledger.path)
        self.assertEqual(0,restarted.premium_tasks()[0]['until'])
        self.assertEqual(4,len(restarted.audit(0)['rows']))

    def test_insufficient_funds_blocked_and_id_reuse(self):
        self.ledger.change('player','debit','subtract','admin','Player',amount=199999)
        with self.assertRaises(ValueError):self.ledger.change('player','buy','buy','user','Player',days=30)
        self.assertEqual([],self.ledger.premium_tasks())
        with self.assertRaises(ValueError):self.ledger.change('player','debit','subtract','admin','Player',amount=1)
        with self.ledger.connection() as db:db.execute('UPDATE wallets SET blocked=1,balance=200000')
        with self.assertRaises(ValueError):self.ledger.change('player','spend','spend','website','Player',amount=1)
        with self.assertRaises(ValueError):self.ledger.change('player','buy','buy','user','Player',days=30)

    def test_external_lp_time_preserved(self):
        future=int(time.time())+10*86400
        self.ledger.change('player','purchase','buy','user','Player',days=30,base_until=future)
        self.assertEqual(future+30*86400,self.ledger.premium_tasks()[0]['until'])

if __name__=='__main__':unittest.main()
