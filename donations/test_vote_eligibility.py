"""Regression tests for the wallet boundary, independent of Telegram UI."""
import concurrent.futures
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch
from ledger import Ledger
from voting import VoteService
from vote_providers import configured_providers, VoteEvent


class EligibilityTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.path=Path(self.temp.name)/'wallet.sqlite'
        self.config={'voting':{'providers':{p:{'enabled':True,'reward_minor':200}
                     for p in ('minecraft_rating','top_minecrafter','hotmc')}}}
        self.ledger=Ledger(self.path)
        self.service=VoteService(self.config,self.ledger,configured_providers(self.config))
        self.repo=self.service.repository
        self.owner=str(uuid.UUID(int=1));self.now=1790000000

    def tearDown(self):self.temp.cleanup()

    def event(self,provider,key,name='Player'):
        return self.repo.accept(VoteEvent(provider,name,self.now,key))

    def test_three_sites_maximum_600_and_repeats_do_not_extend_timer_or_rank(self):
        with patch('time.time',return_value=self.now):
            for provider in self.service.providers:
                for i in range(3):self.event(provider,str(i))
            self.assertEqual({'votes':3,'creditedMinor':600},self.repo.resolve('Player',self.owner,'Player'))
        with patch('time.time',return_value=self.now+3600):
            self.repo.accept(VoteEvent('MinecraftRating.ru','Player',self.now+3600,'history:later'))
            self.assertEqual(0,self.repo.resolve('Player',self.owner,'Player')['votes'])
            self.assertEqual([82800]*3,[p['remainingSeconds'] for p in self.repo.providers_view(True,self.owner)])
        self.assertEqual(600,self.ledger.wallet(self.owner)['balanceMinor'])
        self.assertEqual(3,len(self.repo.notifications()))
        self.assertEqual(3,self.repo.statistics()['total'])
        self.assertEqual(3,self.repo.monthly.top(self.repo.monthly.month(self.now))['rows'][0]['votes'])
        with self.ledger.connection() as db:
            self.assertEqual(7,db.execute("SELECT COUNT(*) FROM vote_events WHERE status='cooldown'").fetchone()[0])

    def test_simultaneous_resolvers_and_lost_response_retry_credit_once(self):
        with patch('time.time',return_value=self.now):
            for i in range(12):self.event('minecraft_rating',str(i))
            with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
                results=list(pool.map(lambda _:self.repo.resolve('Player',self.owner,'Player'),range(12)))
            self.assertEqual(1,sum(r['votes'] for r in results))
            restarted=VoteService(self.config,Ledger(self.path),configured_providers(self.config)).repository
            self.assertEqual('duplicate',restarted.accept(VoteEvent('minecraft_rating','Player',self.now,'0'))['status'])
            self.assertEqual(0,restarted.resolve('Player',self.owner,'Player')['votes'])
        self.assertEqual(200,self.ledger.wallet(self.owner)['balanceMinor'])

    def test_uuid_rename_boundary_and_durable_cooldown(self):
        with patch('time.time',return_value=self.now):
            self.event('minecraft_rating','first');self.repo.resolve('Player',self.owner,'Player')
        restarted=VoteService(self.config,Ledger(self.path),configured_providers(self.config)).repository
        with patch('time.time',return_value=self.now+86399):
            restarted.accept(VoteEvent('minecraft_rating','Renamed',self.now+1,'renamed'))
            self.assertEqual(0,restarted.resolve('Renamed',self.owner,'Renamed')['votes'])
        with patch('time.time',return_value=self.now+86400):
            restarted.accept(VoteEvent('minecraft_rating','Renamed',self.now+86400,'next'))
            self.assertEqual(1,restarted.resolve('Renamed',self.owner,'Renamed')['votes'])
        self.assertEqual(400,self.ledger.wallet(self.owner)['balanceMinor'])

    def test_credit_failure_rolls_back_eligibility_and_retry_keeps_first_reward(self):
        with patch('time.time',return_value=self.now):
            self.event('minecraft_rating','crash')
            with patch.object(Ledger,'entry',side_effect=RuntimeError('crash')):
                with self.assertRaises(RuntimeError):self.repo.resolve('Player',self.owner,'Player')
            self.assertEqual(0,self.repo.providers_view(True,self.owner)[0]['nextEligibleAt'])
            self.assertEqual(1,self.repo.resolve('Player',self.owner,'Player')['votes'])
        self.assertEqual(200,self.ledger.wallet(self.owner)['balanceMinor'])

    def test_upgrade_seeds_from_existing_credit_and_never_credits_legacy_replay(self):
        with patch('time.time',return_value=self.now):
            self.event('minecraft_rating','legacy');self.repo.resolve('Player',self.owner,'Player')
        with self.ledger.connection() as db:db.execute('DELETE FROM vote_eligibility')
        with patch('time.time',return_value=self.now+60):
            self.event('minecraft_rating','new-id')
            self.assertEqual(0,self.repo.resolve('Player',self.owner,'Player')['votes'])
            self.assertEqual(86340,self.repo.providers_view(True,self.owner)[0]['remainingSeconds'])
