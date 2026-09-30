import hashlib
import http.client
import json
import tempfile
import threading
import unittest
import uuid
from pathlib import Path
from urllib.parse import urlencode

from ledger import Ledger
from service import Service, handler
from vote_providers import MinecraftRatingProvider, VoteEvent
from voting import VoteRepository, VoteService


def config(reward=500, enabled=True):
    return {'api_key':'a'*48,'voting':{'providers':{'minecraft_rating':{
        'enabled':enabled,'reward_minor':reward,'secret':'secret-value','vote_url':'https://minecraftrating.ru/'}}}}


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
        self.assertEqual(1400,self.service.repository.resolve('Player_1',self.owner,'Player_1')['creditedMinor'])
        self.assertEqual(1400,self.ledger.wallet(self.owner)['balanceMinor'])

    def test_unknown_is_pending_then_resolved_and_statistics_are_sql_aggregates(self):
        self.service.callback('minecraft_rating',callback())
        self.assertEqual([{'nickname':'Player_1','count':1}],self.service.repository.pending())
        self.assertEqual(1,self.service.repository.statistics()['pending'])
        self.service.repository.resolve('Player_1',self.owner,'Player_1')
        stats=self.service.repository.statistics()
        self.assertEqual((1,1,1,1,500,0),(stats['total'],stats['today'],stats['week'],stats['month'],stats['rewardedMinor'],stats['pending']))
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
                status,wallet=post('/wallet',{'owner':owner},auth=True);self.assertEqual(500,json.loads(wallet)['balanceMinor'])
                self.assertEqual(500,post('/voting/minecraft-rating',callback(signature='wrong'),True)[0])
                self.assertEqual(500,post('/voting/minecraft-rating',{'username':'Player_1'},True)[0])
            finally: server.shutdown();server.server_close();thread.join(5)


if __name__=='__main__': unittest.main()
