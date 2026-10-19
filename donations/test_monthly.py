import tempfile
import time
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch
from ledger import Ledger
from voting import VoteRepository
from vote_providers import VoteEvent, MinecraftRatingProvider


class MonthlyTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.path=Path(self.temp.name)/'wallet.sqlite'
        self.config={'voting':{'providers':{'a':{'enabled':True,'reward_minor':200,'vote_interval_minutes':480},
                                          'b':{'enabled':True,'reward_minor':200,'vote_interval_minutes':1440}}}}
        self.providers={name:MinecraftRatingProvider({}) for name in ('a','b')}
        self.ledger=Ledger(self.path);self.repo=VoteRepository(self.ledger,self.providers,self.config)
        self.owner=str(uuid.UUID(int=1));self.other=str(uuid.UUID(int=2));self.serial=0
        self.start,self.end=self.repo.monthly.bounds('2026-09');self.now=self.start+3600
    def tearDown(self):self.temp.cleanup()
    def vote(self,owner,provider='a',at=None,name='Player'):
        self.serial+=1
        with patch('time.time',return_value=self.now if at is None else at):
            self.repo.accept(VoteEvent(provider,name,self.now,'v:'+str(self.serial)))
            self.repo.resolve(name,owner,name)
    def test_cooldown_provider_uuid_expiry_and_restart(self):
        self.vote(self.owner,'a');self.vote(self.owner,'b')
        restarted=VoteRepository(Ledger(self.path),self.providers,self.config)
        with patch('time.time',return_value=self.now+60):
            views={p['id']:p for p in restarted.providers_view(True,self.owner)}
            self.assertEqual(1439*60,views['a']['remainingSeconds']);self.assertEqual(1439*60,views['b']['remainingSeconds'])
            self.assertEqual([0,0],[p['remainingSeconds'] for p in restarted.providers_view(True,self.other)])
        with patch('time.time',return_value=self.now+1441*60):
            self.assertEqual([0,0],[p['remainingSeconds'] for p in restarted.providers_view(True,self.owner)])
    def test_pending_rejected_and_legacy_zero_interval_cannot_disable_daily_limit(self):
        self.repo.accept(VoteEvent('a','Unknown',self.now,'pending'))
        with self.assertRaises(ValueError):self.repo.accept(VoteEvent('unknown','Unknown',self.now,'rejected'))
        self.assertEqual([0,0],[p['remainingSeconds'] for p in self.repo.providers_view(True,self.owner)])
        self.config['voting']['providers']['a']['vote_interval_minutes']=0;self.vote(self.owner)
        with patch('time.time',return_value=self.now):
            self.assertEqual(86400,self.repo.providers_view(True,self.owner)[0]['remainingSeconds'])

    def test_top_three_competition_ranks_and_more_than_three_tied_recipients(self):
        owners=[str(uuid.UUID(int=i)) for i in range(1,6)]
        for owner,count in zip(owners,[3,2,2,2,1]):
            for day in range(count):self.vote(owner,at=self.now+day*86400)
        self.repo.monthly.close_month('2026-09',self.end)
        with self.ledger.connection() as db:
            prizes=[tuple(r) for r in db.execute('SELECT owner,rank,amount FROM vote_month_prizes ORDER BY owner')]
        self.assertEqual([(owners[0],1,30000),*( (o,2,20000) for o in owners[1:4])],prizes)
        self.assertEqual([1,2,2,2,5],[r['rank'] for r in self.repo.monthly.top('2026-09')['rows']])

    def test_two_first_places_skip_second_and_split_third_deterministically(self):
        owners=[str(uuid.UUID(int=i)) for i in range(1,5)]
        for owner,count in zip(owners,[2,2,1,1]):
            for day in range(count):self.vote(owner,at=self.now+day*86400)
        self.repo.monthly.update('root',101,'split',3)
        self.repo.monthly.close_month('2026-09',self.end)
        with self.ledger.connection() as db:
            prizes=[tuple(r) for r in db.execute('SELECT rank,amount FROM vote_month_prizes ORDER BY owner')]
        self.assertEqual([(1,15000),(1,15000),(3,51),(3,50)],prizes)

    def test_independent_prizes_persist_and_closed_month_is_immutable(self):
        self.repo.monthly.update('root',456,'full',2)
        self.repo.monthly.update('root',123,None,3)
        self.vote(self.owner);self.repo.monthly.close_month('2026-09',self.end)
        restarted=VoteRepository(Ledger(self.path),self.providers,self.config)
        self.assertEqual([30000,456,123],restarted.monthly.settings()['prizesMinor'])
        restarted.monthly.update('root',999,None,2)
        self.assertFalse(restarted.monthly.close_month('2026-09',self.end+10))
        with self.ledger.connection() as db:
            self.assertEqual((456,123),tuple(db.execute('SELECT reward2,reward3 FROM vote_month_results').fetchone()))
        for value in (-1,1.5,True,100000001):
            with self.assertRaises(ValueError):restarted.monthly.update('root',value,None,2)
        with self.assertRaises(ValueError):restarted.monthly.update('root',123,None,4)
    def test_sql_top_uuid_multiple_providers_and_new_month(self):
        self.vote(self.owner,name='Before');self.vote(self.owner,'b',name='After');self.vote(self.other)
        # Provider removal must not remove its historical votes.
        self.providers.pop('b')
        top=self.repo.monthly.top('2026-09');self.assertEqual(2,top['rows'][0]['votes']);self.assertEqual(self.owner,top['rows'][0]['owner'])
        self.assertEqual([],self.repo.monthly.top('2026-10')['rows'])
        self.assertEqual('2026-10',self.repo.monthly.month(self.end))
    def test_full_tie_snapshot_and_exactly_once_after_restart(self):
        self.vote(self.owner);self.vote(self.other,'b')
        self.repo.monthly.update('root',10000,'full');self.assertTrue(self.repo.monthly.close_month('2026-09',self.end))
        before=self.ledger.wallet(self.owner)['balanceMinor'];self.assertEqual(10200,before)
        restarted=VoteRepository(Ledger(self.path),self.providers,self.config)
        restarted.monthly.update('root',99999,'split');self.assertFalse(restarted.monthly.close_month('2026-09',self.end+1))
        self.assertEqual(before,self.ledger.wallet(self.owner)['balanceMinor']);self.assertEqual(before,self.ledger.wallet(self.other)['balanceMinor'])
        self.assertTrue(restarted.monthly.top('2026-09')['closed'])
        with self.ledger.connection() as db:
            result=db.execute('SELECT reward,policy FROM vote_month_results').fetchone();self.assertEqual((10000,'full'),tuple(result))
            self.assertEqual(2,db.execute("SELECT COUNT(*) FROM entries WHERE id LIKE 'vote-month:%'").fetchone()[0])
    def test_split_remainder_is_exact_and_deterministic(self):
        for owner in (self.other,self.owner,str(uuid.UUID(int=3))):self.vote(owner)
        self.repo.monthly.update('root',100,'split');self.repo.monthly.close_month('2026-09',self.end)
        with self.ledger.connection() as db:
            amounts=[r[0] for r in db.execute('SELECT amount FROM vote_month_prizes ORDER BY owner')]
        self.assertEqual([34,33,33],amounts);self.assertEqual(100,sum(amounts))
        self.assertFalse(self.repo.monthly.close_month('2026-09',self.end))
    def test_no_votes_no_prize_and_open_month_rejected(self):
        self.repo.monthly.update('root',100,'full');self.repo.monthly.close_month('2026-09',self.end)
        self.assertEqual([],self.repo.monthly.top('2026-09')['rows'])
        with self.ledger.connection() as db:self.assertEqual(0,db.execute('SELECT COUNT(*) FROM entries').fetchone()[0])
        with self.assertRaises(ValueError):self.repo.monthly.close_month('2026-10',self.end)
    def test_monthly_transaction_rolls_back_partial_payout(self):
        self.vote(self.owner);self.vote(self.other);self.repo.monthly.update('root',100,'full')
        original=Ledger.entry
        def entry(db,key,owner,amount):
            if owner==self.other:raise RuntimeError('simulated crash')
            original(db,key,owner,amount)
        with patch.object(Ledger,'entry',side_effect=entry):
            with self.assertRaises(RuntimeError):self.repo.monthly.close_month('2026-09',self.end)
        self.assertFalse(self.repo.monthly.top('2026-09')['closed']);self.assertEqual(200,self.ledger.wallet(self.owner)['balanceMinor'])
        self.assertTrue(self.repo.monthly.close_month('2026-09',self.end))
    def test_job_catches_up_once_and_top_paginates(self):
        for i in range(12):self.vote(str(uuid.UUID(int=i+1)))
        self.assertEqual(2,self.repo.monthly.top('2026-09')['pages']);self.assertEqual(2,len(self.repo.monthly.top('2026-09',1)['rows']))
        self.repo.monthly.update('root',100,'full');self.repo.monthly.settle(self.end);self.repo.monthly.settle(self.end)
        self.assertEqual(300,self.ledger.wallet(self.owner)['balanceMinor'])


class LegacyMonthlyMigrationTests(unittest.TestCase):
    def test_existing_single_winner_month_never_gets_second_or_third_prizes(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'legacy.sqlite';ledger=Ledger(path);owner=str(uuid.UUID(int=1))
            with ledger.connection() as db:
                db.executescript('''
                    CREATE TABLE vote_month_settings(id INTEGER PRIMARY KEY,reward INTEGER NOT NULL,policy TEXT NOT NULL);
                    CREATE TABLE vote_month_results(month TEXT PRIMARY KEY,reward INTEGER NOT NULL,policy TEXT NOT NULL,closed INTEGER NOT NULL);
                    CREATE TABLE vote_month_ranks(month TEXT,owner TEXT,nickname TEXT,votes INTEGER,PRIMARY KEY(month,owner));
                    CREATE TABLE vote_month_prizes(month TEXT,kind TEXT,owner TEXT,rank INTEGER,amount INTEGER,PRIMARY KEY(month,kind,owner));
                    INSERT INTO vote_month_settings VALUES(1,777,'full');
                    INSERT INTO vote_month_results VALUES('2026-09',777,'full',1790816400);
                ''')
                Ledger.entry(db,'vote-month:2026-09:leader:'+owner,owner,777)
            for _ in range(2):
                repo=VoteRepository(Ledger(path),{'a':MinecraftRatingProvider({})},{})
                self.assertEqual([777,20000,10000],repo.monthly.settings()['prizesMinor'])
                self.assertFalse(repo.monthly.close_month('2026-09',1790902800))
                self.assertEqual(777,ledger.wallet(owner)['balanceMinor'])
                with ledger.connection() as db:
                    self.assertEqual((777,0,0),tuple(db.execute('SELECT reward,reward2,reward3 FROM vote_month_results').fetchone()))


class DonationStatisticsTests(unittest.TestCase):
    def test_period_totals_and_financial_categories(self):
        with tempfile.TemporaryDirectory() as folder:
            ledger=Ledger(Path(folder)/'ledger.sqlite');owner=str(uuid.uuid4());other=str(uuid.uuid4());now=int(time.time())
            def payment(key,amount,who,when,status='CLOSED'):
                with patch('time.time',return_value=when):
                    order=ledger.sync(key,'buyer',amount,status);ledger.claim(who,order['code'])
            payment('today',100000,owner,now);payment('week',1000,other,now-2*86400)
            payment('month',2000,owner,now-10*86400);payment('old',3000,owner,now-40*86400)
            ledger.sync('today','buyer',100000,'CLOSED',stars=5)
            ledger.sync('week','buyer',1000,'REFUNDED')
            ledger.change(owner,'root-add','add','root','Player',amount=1000)
            ledger.change(owner,'root-sub','subtract','root','Player',amount=500)
            ledger.change(owner,'premium-buy','buy','website','Player',days=30)
            ledger.change(other,'premium-grant','grant','root','Other',days=30)
            stats=ledger.statistics()['periods']
            self.assertEqual([1,2,3,4],[stats[p]['paymentCount'] for p in ('today','week','month','all')])
            self.assertEqual([100000,101000,103000,106000],[stats[p]['paymentMinor'] for p in ('today','week','month','all')])
            today=stats['today'];self.assertEqual(100000,today['confirmedMinor']);self.assertEqual(1000,today['refundMinor'])
            self.assertEqual(5000,today['reviewMinor']);self.assertEqual(1,today['reviewCount'])
            self.assertEqual((2,500),(today['manualCount'],today['manualMinor']))
            self.assertEqual((1,60000,1),(today['premiumPurchases'],today['premiumPurchaseMinor'],today['premiumGrants']))
            self.assertEqual(2,stats['all']['donors']);self.assertEqual(105000,stats['all']['confirmedMinor'])
