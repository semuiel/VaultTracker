import hashlib
import http.client
import json
import tempfile
import threading
import unittest
import uuid
from pathlib import Path
from urllib.parse import urlencode
from unittest.mock import patch

from ledger import Ledger
from service import Service, handler
from vote_providers import MinecraftRatingProvider, VoteEvent, configured_providers
from vote_reconcile import MinecraftRatingHistory
from voting import VoteRepository, VoteService


def config(reward=500, enabled=True):
    return {'api_key':'a'*48,'voting':{'providers':{'minecraft_rating':{
        'enabled':enabled,'reward_minor':reward,'secret':'secret-value','vote_url':'https://minecraftrating.ru/',
        'callback_enabled':True}}}}


def callback(username='Player_1', timestamp='1700000000', secret='secret-value', **changes):
    fields={'username':username,'ip':'192.0.2.1','timestamp':timestamp,
            'signature':hashlib.sha1((username+timestamp+secret).encode()).hexdigest()}
    fields.update(changes)
    return fields


class VotingTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.path=Path(self.temp.name)/'ledger.sqlite'
        self.ledger=Ledger(self.path)
        self.service=VoteService(config(),self.ledger,{'minecraft_rating':MinecraftRatingProvider(config()['voting']['providers']['minecraft_rating'])})
        self.owner=str(uuid.uuid4())

    def tearDown(self): self.temp.cleanup()

    def test_official_callback_duplicate_restart_and_exact_reward(self):
        first=self.service.callback('minecraft_rating',callback())
        self.assertEqual('pending',first['status'])
        self.assertEqual('duplicate',self.service.callback('minecraft_rating',callback())['status'])
        restarted=VoteService(config(),Ledger(self.path),{'minecraft_rating':MinecraftRatingProvider(config()['voting']['providers']['minecraft_rating'])})
        self.assertEqual('duplicate',restarted.callback('minecraft_rating',callback())['status'])
        self.assertEqual({'votes':1,'creditedMinor':500},restarted.repository.resolve('Player_1',self.owner,'Renamed'))
        self.assertEqual(500,self.ledger.wallet(self.owner)['balanceMinor'])
        self.assertEqual({'votes':0,'creditedMinor':0},restarted.repository.resolve('Player_1',self.owner,'Renamed'))

    def test_invalid_secret_and_missing_fields_never_create_vote(self):
        for fields in (callback(signature='bad'),{'username':'Player_1'}):
            with self.assertRaises(ValueError): self.service.callback('minecraft_rating',fields)
        self.assertEqual([],self.service.repository.pending())

    def test_disabled_zero_reward_and_reward_change_only_affect_new_votes(self):
        disabled=VoteService(config(enabled=False),Ledger(Path(self.temp.name)/'disabled.sqlite'),
            {'minecraft_rating':MinecraftRatingProvider(config(enabled=False)['voting']['providers']['minecraft_rating'])})
        with self.assertRaises(ValueError): disabled.callback('minecraft_rating',callback())
        zero=VoteService(config(reward=0),Ledger(Path(self.temp.name)/'zero.sqlite'),
            {'minecraft_rating':MinecraftRatingProvider(config(reward=0)['voting']['providers']['minecraft_rating'])})
        zero.callback('minecraft_rating',callback());zero.repository.resolve('Player_1',self.owner,'Player_1')
        self.assertEqual(0,zero.ledger.wallet(self.owner)['balanceMinor'])
        self.service.callback('minecraft_rating',callback(timestamp='1700000001'))
        self.service.repository.update('minecraft_rating','root',reward=900)
        self.service.callback('minecraft_rating',callback(timestamp='1700000002'))
        self.service.repository.update('minecraft_rating','root',enabled=False)
        self.assertEqual('duplicate',self.service.callback('minecraft_rating',callback(timestamp='1700000002'))['status'])
        result=self.service.repository.resolve('Player_1',self.owner,'Player_1')
        # Both events arrived before resolving: at most one can consume this period.
        self.assertEqual(1,result['votes'])
        self.assertEqual(500,result['creditedMinor'])
        self.assertEqual(result['creditedMinor'],self.ledger.wallet(self.owner)['balanceMinor'])

    def test_unknown_is_pending_then_resolved_and_statistics_are_sql_aggregates(self):
        self.service.callback('minecraft_rating',callback())
        self.assertEqual([{'nickname':'Player_1','count':1}],self.service.repository.pending())
        self.assertEqual(1,self.service.repository.statistics()['pending'])
        self.service.repository.resolve('Player_1',self.owner,'Player_1')
        stats=self.service.repository.statistics()
        self.assertEqual((1,1,1,1,500,0),(stats['total'],stats['today'],stats['week'],stats['month'],stats['rewardedMinor'],stats['pending']))
        self.assertEqual((1,1,1,1,500,500,500,0),
                         (stats['uniqueTotal'],stats['uniqueToday'],stats['uniqueWeek'],stats['uniqueMonth'],
                          stats['rewardedTodayMinor'],stats['rewardedWeekMinor'],stats['rewardedMonthMinor'],stats['pendingPlayers']))
        self.assertGreater(stats['firstReceived'],0)
        self.assertEqual(stats['firstReceived'],stats['lastReceived'])
        self.assertEqual(('Player_1',1,500),(stats['topPlayers'][0]['nickname'],stats['topPlayers'][0]['votes'],stats['topPlayers'][0]['rewarded']))
        self.assertEqual((1,1,500),(stats['daily'][-1]['votes'],stats['daily'][-1]['unique'],stats['daily'][-1]['rewardedMinor']))
        self.assertEqual('minecraft_rating',stats['providers'][0]['provider'])

    def test_provider_isolation(self):
        class Other:
            provider_id='other';display_name='Other'
        cfg=config();cfg['voting']['providers']['other']={'enabled':True,'reward_minor':700}
        repo=VoteRepository(self.ledger,{'minecraft_rating':self.service.providers['minecraft_rating'],'other':Other()},cfg)
        repo.accept(VoteEvent('other','Player_1',1700000000,'other-key'))
        repo.update('minecraft_rating','root',enabled=False)
        self.assertTrue(next(p for p in repo.providers_view() if p['id']=='other')['enabled'])
        self.assertEqual(700,repo.resolve('Player_1',self.owner,'Player_1')['creditedMinor'])

    def test_history_parser_and_callback_in_same_minute_are_one_vote(self):
        html='''<h3>Последние 10 голосов:</h3><table><tbody><tr><td class="player-nick"><img> Player_1</td>
            <td>14.09.2026, в 12:34</td></tr></tbody></table>'''
        event=MinecraftRatingHistory.parse(html)[0]
        self.assertEqual('Player_1',event.nickname)
        self.assertEqual('pending',self.service.repository.accept(event)['status'])
        same_minute=str(event.source_timestamp+25)
        self.assertEqual('duplicate',self.service.callback('minecraft_rating',callback(timestamp=same_minute))['status'])
        self.assertEqual(1,self.service.repository.pending()[0]['count'])

    def test_votifier_record_is_idempotent(self):
        first=self.service.record_votifier('Player_1','MinecraftRating.ru','1700001234')
        second=self.service.record_votifier('Player_1','MinecraftRating.ru','1700001234')
        self.assertEqual('pending',first['status'])
        self.assertEqual('duplicate',second['status'])

    def test_votifier_sites_have_separate_rewards_statistics_and_notifications(self):
        cfg=config()
        cfg['voting']['providers']['top_minecrafter']={
            'enabled':True,'reward_minor':300,
            'vote_url':'https://top-minecrafter.com/server/flexity/',
            'votifier_services':['top-minecrafter.com']}
        cfg['voting']['providers']['hotmc']={
            'enabled':True,'reward_minor':200,
            'vote_url':'https://hotmc.ru/vote-281777',
            'votifier_services':['HotMC.RU']}
        service=VoteService(cfg,self.ledger,configured_providers(cfg))
        self.assertEqual(['minecraft_rating','top_minecrafter','hotmc'],
                         [item['id'] for item in service.repository.providers_view(True)])
        top=service.record_votifier('Player_1','top-minecrafter.com','1700001234')
        service.record_votifier('Player_1','MinecraftRating.ru','1700001234')
        hot=service.record_votifier('Player_1','HotMC.RU','1700001234')
        self.assertEqual('duplicate',service.record_votifier('Player_1','top-minecrafter.com','1700001234')['status'])
        self.assertEqual('duplicate',service.record_votifier('Player_1','HotMC.RU','1700001234')['status'])
        self.assertEqual(1000,service.repository.resolve('Player_1',self.owner,'Player_1')['creditedMinor'])
        self.assertEqual(300,service.repository.statistics('top_minecrafter')['rewardedMinor'])
        self.assertEqual(500,service.repository.statistics('minecraft_rating')['rewardedMinor'])
        self.assertEqual(200,service.repository.statistics('hotmc')['rewardedMinor'])
        self.assertEqual(
            [('minecraft_rating',1),('top_minecrafter',1),('hotmc',1)],
            [(row['provider'],row['votes']) for row in service.repository.player_votes(self.owner)])
        self.assertEqual([0,0,0],[row['votes'] for row in service.repository.player_votes(str(uuid.uuid4()))])
        all_stats=service.repository.statistics()
        self.assertEqual(['minecraft_rating','top_minecrafter','hotmc'],[row['provider'] for row in all_stats['providers']])
        self.assertEqual((3,1,1000),(all_stats['total'],all_stats['uniqueTotal'],all_stats['rewardedMonthMinor']))
        self.assertEqual(3,len(service.repository.notifications()))
        notice_ids=[n['id'] for n in service.repository.notifications()]
        self.assertIn(top['id'],notice_ids)
        self.assertIn(hot['id'],notice_ids)
        service.repository.update('top_minecrafter','root',reward=700,enabled=False)
        with self.assertRaises(ValueError):
            service.record_votifier('Player_1','top-minecrafter.com','1700001235')
        self.assertEqual('pending',service.record_votifier('Player_1','MinecraftRating.ru','1700001235')['status'])
        self.assertEqual('pending',service.record_votifier('Player_1','hotmc.ru','1700001235')['status'])

    def test_unknown_and_ambiguous_votifier_sources_never_credit_minecraft_rating(self):
        with self.assertRaisesRegex(ValueError,'unknown-votifier-service'):
            self.service.record_votifier('Player_1','unconfigured-source','1700001234')
        cfg=config()
        cfg['voting']['providers']['top_minecrafter']={'votifier_services':['MinecraftRating.ru']}
        service=VoteService(cfg,self.ledger,configured_providers(cfg))
        with self.assertRaisesRegex(ValueError,'ambiguous-votifier-service'):
            service.record_votifier('Player_1','MinecraftRating.ru','1700001234')
        self.assertEqual([],service.repository.pending())

    def test_credit_notification_is_durable_and_contains_current_balance(self):
        self.service.record_votifier('Player_1','MinecraftRating.ru','1700001234')
        self.service.repository.resolve('Player_1',self.owner,'Player_1')
        notice=self.service.repository.notifications()[0]
        self.assertEqual((self.owner,'Player_1',500,500),
                         (notice['owner'],notice['nickname'],notice['rewardMinor'],notice['balanceMinor']))
        restarted=VoteService(config(),Ledger(self.path),{'minecraft_rating':MinecraftRatingProvider(config()['voting']['providers']['minecraft_rating'])})
        self.assertEqual(notice['id'],restarted.repository.notifications()[0]['id'])
        restarted.repository.acknowledge_notification(notice['id'])
        self.assertEqual([],restarted.repository.notifications())


class VotingHttpTests(unittest.TestCase):
    def test_form_endpoint_matches_official_contract_and_payment_api_still_works(self):
        with tempfile.TemporaryDirectory() as directory:
            ledger=Ledger(Path(directory)/'ledger.sqlite');cfg=config()
            service=Service(cfg,ledger,None)
            server=__import__('http.server').server.ThreadingHTTPServer(('127.0.0.1',0),handler(service))
            thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
            def post(path,data,form=False,auth=False):
                conn=http.client.HTTPConnection('127.0.0.1',server.server_port,timeout=5)
                headers={'Content-Type':'application/x-www-form-urlencoded' if form else 'application/json'}
                if auth: headers['Authorization']='Bearer '+cfg['api_key']
                raw=urlencode(data) if form else json.dumps(data)
                try:
                    conn.request('POST',path,raw,headers);response=conn.getresponse();return response.status,response.read()
                finally: conn.close()
            try:
                status,body=post('/voting/minecraft-rating',callback(),True)
                self.assertEqual((200,b'ok'),(status,body))
                owner=str(uuid.uuid4())
                status,pending=post('/voting-pending',{},auth=True);self.assertEqual(200,status)
                self.assertEqual('Player_1',json.loads(pending)['players'][0]['nickname'])
                status,resolved=post('/voting-resolve',{'nickname':'Player_1','owner':owner,'displayName':'Player_1'},auth=True)
                self.assertEqual(1,json.loads(resolved)['votes'])
                status,notices=post('/voting-notifications',{'limit':20},auth=True);self.assertEqual(200,status)
                notice=json.loads(notices)['notifications'][0]
                self.assertEqual((owner,500),(notice['owner'],notice['rewardMinor']))
                self.assertEqual(200,post('/voting-notification-ack',{'id':notice['id'],'success':True},auth=True)[0])
                self.assertEqual([],json.loads(post('/voting-notifications',{},auth=True)[1])['notifications'])
                status,wallet=post('/wallet',{'owner':owner},auth=True);self.assertEqual(500,json.loads(wallet)['balanceMinor'])
                status,summary=post('/player-summary',{'owner':owner},auth=True);summary=json.loads(summary)
                self.assertEqual((500,1),(summary['balanceMinor'],summary['votes'][0]['votes']))
                self.assertEqual(500,post('/voting/minecraft-rating',callback(signature='wrong'),True)[0])
                self.assertEqual(500,post('/voting/minecraft-rating',{'username':'Player_1'},True)[0])
            finally: server.shutdown();server.server_close();thread.join(5)


if __name__=='__main__': unittest.main()
