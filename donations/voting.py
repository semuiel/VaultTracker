"""Durable voting domain. Uses the donation wallet transaction, but no payment provider code."""
import re
import time
import uuid

from ledger import Ledger


class VoteRepository:
    MAX_REWARD_MINOR = 100_000_000

    def __init__(self, ledger, providers, config):
        self.ledger, self.providers, self.config = ledger, providers, config
        with ledger.connection() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS vote_provider_settings(
                    provider TEXT PRIMARY KEY, enabled INTEGER NOT NULL, reward INTEGER NOT NULL,
                    updated INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS vote_events(
                    id TEXT PRIMARY KEY, provider TEXT NOT NULL, replay_key TEXT NOT NULL,
                    source_nickname TEXT NOT NULL, normalized_nickname TEXT NOT NULL,
                    owner TEXT, display_nickname TEXT NOT NULL, source_timestamp INTEGER NOT NULL,
                    received INTEGER NOT NULL, credited INTEGER, actual_reward INTEGER NOT NULL,
                    status TEXT NOT NULL, UNIQUE(provider,replay_key));
                CREATE INDEX IF NOT EXISTS vote_events_owner_provider_time ON vote_events(owner,provider,received);
                CREATE INDEX IF NOT EXISTS vote_events_provider_time ON vote_events(provider,received);
                CREATE INDEX IF NOT EXISTS vote_events_pending_name ON vote_events(status,normalized_nickname);
                CREATE TABLE IF NOT EXISTS vote_settings_audit(
                    id INTEGER PRIMARY KEY AUTOINCREMENT, actor TEXT NOT NULL, provider TEXT NOT NULL,
                    setting TEXT NOT NULL, old_value TEXT NOT NULL, new_value TEXT NOT NULL, created INTEGER NOT NULL);
            ''')
            configured = config.get('voting', {}).get('providers', {})
            now = int(time.time())
            for provider_id in providers:
                item = configured.get(provider_id, {})
                enabled = int(item.get('enabled', False) is True)
                reward = item.get('reward_minor', 0)
                if type(reward) is not int or not 0 <= reward <= self.MAX_REWARD_MINOR:
                    raise ValueError('Invalid voting reward configuration')
                db.execute('INSERT OR IGNORE INTO vote_provider_settings VALUES(?,?,?,?)',
                           (provider_id, enabled, reward, now))

    def providers_view(self, enabled_only=False):
        configured = self.config.get('voting', {}).get('providers', {})
        with self.ledger.connection() as db:
            rows = db.execute('SELECT * FROM vote_provider_settings ORDER BY provider').fetchall()
            result=[]
            for row in rows:
                if enabled_only and not row['enabled']:
                    continue
                adapter=self.providers.get(row['provider'])
                if not adapter:
                    continue
                item=configured.get(row['provider'], {})
                result.append({'id':row['provider'],'name':adapter.display_name,'enabled':bool(row['enabled']),
                               'rewardMinor':row['reward'],'voteUrl':item.get('vote_url','') if isinstance(item.get('vote_url',''),str) else ''})
            return result

    def setting(self, provider):
        with self.ledger.connection() as db:
            row=db.execute('SELECT * FROM vote_provider_settings WHERE provider=?',(provider,)).fetchone()
            if not row or provider not in self.providers:
                raise ValueError('unknown-provider')
            return dict(row)

    def update(self, provider, actor, enabled=None, reward=None):
        if not isinstance(actor,str) or not actor or len(actor)>128:
            raise ValueError('Invalid actor')
        if enabled is None and reward is None:
            raise ValueError('Invalid voting update')
        if enabled is not None and type(enabled) is not bool:
            raise ValueError('Invalid voting state')
        if reward is not None and (type(reward) is not int or not 0 <= reward <= self.MAX_REWARD_MINOR):
            raise ValueError('Invalid voting reward')
        with self.ledger.connection() as db:
            row=db.execute('SELECT * FROM vote_provider_settings WHERE provider=?',(provider,)).fetchone()
            if not row or provider not in self.providers:
                raise ValueError('Unknown voting provider')
            now=int(time.time())
            if enabled is not None and int(enabled)!=row['enabled']:
                db.execute('INSERT INTO vote_settings_audit(actor,provider,setting,old_value,new_value,created) VALUES(?,?,?,?,?,?)',
                           (actor,provider,'enabled',str(row['enabled']),str(int(enabled)),now))
                db.execute('UPDATE vote_provider_settings SET enabled=?,updated=? WHERE provider=?',(int(enabled),now,provider))
            if reward is not None and reward!=row['reward']:
                db.execute('INSERT INTO vote_settings_audit(actor,provider,setting,old_value,new_value,created) VALUES(?,?,?,?,?,?)',
                           (actor,provider,'reward',str(row['reward']),str(reward),now))
                db.execute('UPDATE vote_provider_settings SET reward=?,updated=? WHERE provider=?',(reward,now,provider))
        return next(item for item in self.providers_view() if item['id']==provider)

    def accept(self, event):
        with self.ledger.connection() as db:
            setting=db.execute('SELECT * FROM vote_provider_settings WHERE provider=?',(event.provider,)).fetchone()
            existing=db.execute('SELECT * FROM vote_events WHERE provider=? AND replay_key=?',
                                (event.provider,event.replay_key)).fetchone()
            if existing:
                return {'id':existing['id'],'status':'duplicate'}
            if not setting or not setting['enabled']:
                raise ValueError('provider-disabled')
            event_id=uuid.uuid4().hex
            db.execute('INSERT INTO vote_events VALUES(?,?,?,?,?,?,?,?,?,?,?,?)',
                       (event_id,event.provider,event.replay_key,event.nickname,event.nickname.lower(),None,event.nickname,
                        event.source_timestamp,int(time.time()),None,setting['reward'],'pending'))
            return {'id':event_id,'status':'pending'}

    def pending(self, limit=100):
        with self.ledger.connection() as db:
            return [dict(row) for row in db.execute(
                "SELECT source_nickname AS nickname,COUNT(*) AS count FROM vote_events WHERE status='pending' "
                'GROUP BY normalized_nickname,source_nickname ORDER BY MIN(received) LIMIT ?', (limit,))]

    def resolve(self, nickname, owner, display_name):
        if not re.fullmatch(r'[A-Za-z0-9_]{1,16}',nickname or ''):
            raise ValueError('Invalid nickname')
        owner=str(uuid.UUID(owner))
        if not isinstance(display_name,str) or not re.fullmatch(r'[A-Za-z0-9_]{1,16}',display_name):
            raise ValueError('Invalid display name')
        now=int(time.time());credited=0;votes=0
        with self.ledger.connection() as db:
            rows=db.execute("SELECT * FROM vote_events WHERE status='pending' AND normalized_nickname=? ORDER BY received,id",
                            (nickname.lower(),)).fetchall()
            for row in rows:
                Ledger.entry(db,'vote:'+row['id'],owner,row['actual_reward'])
                db.execute("UPDATE vote_events SET owner=?,display_nickname=?,credited=?,status='credited' WHERE id=? AND status='pending'",
                           (owner,display_name,now,row['id']))
                credited+=row['actual_reward'];votes+=1
        return {'votes':votes,'creditedMinor':credited}

    def statistics(self, provider=None):
        if provider is not None and provider not in self.providers:
            raise ValueError('Unknown voting provider')
        where='WHERE provider=?' if provider else ''
        args=(provider,) if provider else ()
        now=int(time.time())
        with self.ledger.connection() as db:
            totals=db.execute(f'''SELECT
                SUM(CASE WHEN status='credited' THEN 1 ELSE 0 END) AS total,
                SUM(CASE WHEN status='credited' AND received>=? THEN 1 ELSE 0 END) AS today,
                SUM(CASE WHEN status='credited' AND received>=? THEN 1 ELSE 0 END) AS week,
                SUM(CASE WHEN status='credited' AND received>=? THEN 1 ELSE 0 END) AS month,
                SUM(CASE WHEN status='credited' THEN actual_reward ELSE 0 END) AS rewarded,
                SUM(CASE WHEN status='pending' THEN 1 ELSE 0 END) AS pending
                FROM vote_events {where}''',(now-now%86400,now-7*86400,now-30*86400,*args)).fetchone()
            rows=db.execute('''SELECT provider,
                SUM(CASE WHEN status='credited' THEN 1 ELSE 0 END) AS votes,
                SUM(CASE WHEN status='credited' THEN actual_reward ELSE 0 END) AS rewarded,
                SUM(CASE WHEN status='pending' THEN 1 ELSE 0 END) AS pending
                FROM vote_events GROUP BY provider ORDER BY provider''').fetchall() if provider is None else []
            clean=lambda value: int(value or 0)
            return {'total':clean(totals['total']),'today':clean(totals['today']),'week':clean(totals['week']),
                    'month':clean(totals['month']),'rewardedMinor':clean(totals['rewarded']),
                    'pending':clean(totals['pending']),'providers':[dict(row) for row in rows]}


class VoteService:
    def __init__(self, config, ledger, providers):
        self.ledger=ledger
        self.providers=providers
        self.repository=VoteRepository(ledger,providers,config)

    def callback(self, provider_id, fields):
        provider=self.providers.get(provider_id)
        if provider is None:
            raise ValueError('unknown-provider')
        return self.repository.accept(provider.parse(fields))
