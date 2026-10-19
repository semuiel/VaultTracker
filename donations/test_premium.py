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

    def test_unblock_requires_confirmed_debt_repayment_and_is_idempotent(self):
        with self.ledger.connection() as db:
            db.execute("UPDATE wallets SET balance=-100,blocked=1 WHERE owner='player'")
        with self.assertRaises(ValueError):
            self.ledger.change('player','unlock','unblock','root','Player')
        order=self.ledger.sync('ORDER','buyer',100,'PAID')
        self.ledger.claim('player',order['code'])
        with self.assertRaises(ValueError):
            self.ledger.change('player','unlock','unblock','root','Player')
        self.ledger.sync('ORDER','buyer',100,'CLOSED')
        self.assertTrue(self.ledger.wallet('player')['blocked'])
        self.ledger.change('player','unlock','unblock','root','Player')
        self.ledger.change('player','unlock','unblock','root','Player')
        self.assertFalse(self.ledger.wallet('player')['blocked'])
        self.assertEqual(1,sum(row['action']=='unblock' for row in self.ledger.audit(0)['rows']))

    def test_external_lp_time_preserved(self):
        future=int(time.time())+10*86400
        self.ledger.change('player','purchase','buy','user','Player',days=30,base_until=future)
        self.assertEqual(future+30*86400,self.ledger.premium_tasks()[0]['until'])

    def test_both_products_all_prices_durations_and_persistent_expiries(self):
        expected={'premiumlite':{30:31900,60:54900,180:149000,360:269000},
                  'premium':{30:60000,60:100000,180:270000,360:480000}}
        for product, prices in expected.items():
            for days,cost in prices.items():
                with self.subTest(product=product,days=days):
                    owner=f'{product}-{days}'
                    self.ledger.change(owner,owner+'seed','add','root','Player',amount=1_000_000)
                    result=self.ledger.change(owner,owner+'buy','buy','user','Player',days=days,product=product)
                    self.assertEqual(1_000_000-cost,result['balanceMinor'])
                    task=next(r for r in self.ledger.premium_tasks() if r['owner']==owner)
                    self.assertEqual(product,task['product'])
                    self.assertAlmostEqual(time.time()+days*86400,task['until'],delta=5)
                    self.ledger.change(owner,owner+'buy','buy','user','Player',days=days,product=product)
                    self.assertEqual(1_000_000-cost,self.ledger.wallet(owner)['balanceMinor'])
                    self.ledger.premium_ack(owner,task['version'],product)
        self.assertEqual([],Ledger(self.ledger.path).premium_tasks())

    def test_two_plans_extend_independently_and_cross_plan_retry_cannot_reuse_id(self):
        self.ledger.change('player','more','add','root','Player',amount=1_000_000)
        self.ledger.change('player','lite','buy','user','Player',days=30,product='premiumlite')
        first=self.ledger.wallet('player')['premiumLiteUntil']
        self.assertEqual(0,self.ledger.wallet('player')['premiumUntil'])
        self.ledger.change('player','full','buy','user','Player',days=60)
        full=self.ledger.wallet('player')['premiumUntil']
        self.ledger.change('player','lite2','buy','user','Player',days=180,product='premiumlite')
        self.assertEqual(first+180*86400,self.ledger.wallet('player')['premiumLiteUntil'])
        self.assertEqual(full,self.ledger.wallet('player')['premiumUntil'])
        with self.assertRaises(ValueError):self.ledger.change('player','lite','buy','user','Player',days=30)
        task=next(t for t in self.ledger.premium_tasks() if t['product']=='premiumlite')
        self.ledger.premium_ack('player',task['version']-1,'premiumlite')
        self.assertTrue(self.ledger.wallet('player')['premiumLitePending'])
        self.ledger.change('player','removeLite','remove','root','Player',product='premiumlite')
        self.assertEqual(full,self.ledger.wallet('player')['premiumUntil'])
        self.assertEqual(0,Ledger(self.ledger.path).wallet('player')['premiumLiteUntil'])

    def test_lite_concurrent_purchase_pending_money_and_refund_debt_lock(self):
        def buy(_):return self.ledger.change('player','lite-once','buy','user','Player',days=30,product='premiumlite')
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:list(pool.map(buy,range(8)))
        self.assertEqual(168100,self.ledger.wallet('player')['balanceMinor'])
        owner='pending'
        row=self.ledger.sync('pending-order','buyer',31900,'PAID');self.ledger.claim(owner,row['code'])
        with self.assertRaises(ValueError):self.ledger.change(owner,'blockedBuy','buy','user','Player',days=30,product='premiumlite')
        self.assertEqual(0,self.ledger.wallet(owner)['premiumLiteUntil'])
        self.ledger.sync('pending-order','buyer',31900,'CLOSED')
        self.ledger.change(owner,'okBuy','buy','user','Player',days=30,product='premiumlite')
        self.ledger.sync('pending-order','buyer',31900,'REFUNDED')
        self.assertEqual(-31900,self.ledger.wallet(owner)['balanceMinor'])
        self.assertTrue(self.ledger.wallet(owner)['blocked'])
        self.ledger.change(owner,'rootCredit','add','root','Player',amount=100000)
        for product in ('premium','premiumlite'):
            with self.assertRaises(ValueError):self.ledger.change(owner,'buy-'+product,'buy','user','Player',days=30,product=product)

    def test_unknown_product_invalid_period_and_permanent_lite_never_charge(self):
        for product,days in [('fake',30),('premiumlite',31),('premium',365),('premiumlite',True)]:
            with self.assertRaises(ValueError):self.ledger.change('player','invalid','buy','user','Player',days=days,product=product)
        self.ledger.change('player','permanent','grant','root','Player',days=-1,product='premiumlite')
        with self.assertRaises(ValueError):self.ledger.change('player','nocharge','buy','user','Player',days=30,product='premiumlite')
        self.assertEqual(200000,self.ledger.wallet('player')['balanceMinor'])

if __name__=='__main__':unittest.main()
