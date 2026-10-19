"""Durable voting domain. Uses the donation wallet transaction, but no payment provider code."""
import hashlib
import re
import time
import uuid

from ledger import Ledger
from vote_providers import VoteEvent
from vote_monthly import MonthlyVotes
from vote_schema import migrate


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
                CREATE INDEX IF NOT EXISTS vote_events_credited_time ON vote_events(status,credited,owner);
                CREATE INDEX IF NOT EXISTS vote_events_cooldown ON vote_events(owner,status,provider,credited);
                CREATE TABLE IF NOT EXISTS vote_settings_audit(
                    id INTEGER PRIMARY KEY AUTOINCREMENT, actor TEXT NOT NULL, provider TEXT NOT NULL,
                    setting TEXT NOT NULL, old_value TEXT NOT NULL, new_value TEXT NOT NULL, created INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS vote_notifications(
                    event_id TEXT PRIMARY KEY, owner TEXT NOT NULL, nickname TEXT NOT NULL,
                    reward INTEGER NOT NULL, created INTEGER NOT NULL,
                    delivered INTEGER NOT NULL DEFAULT 0, due INTEGER NOT NULL DEFAULT 0);
                CREATE INDEX IF NOT EXISTS vote_notifications_due ON vote_notifications(delivered,due,created);
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
                interval=item.get('vote_interval_minutes',0)
                if type(interval) is not int or not 0<=interval<=525600:
                    raise ValueError('Invalid voting interval')
        with ledger.connection() as db:
            migrate(db,'eligibility',1,lambda tx: tx.execute('CREATE TABLE IF NOT EXISTS vote_eligibility(owner TEXT NOT NULL,provider TEXT NOT NULL,last_credited INTEGER NOT NULL,next_eligible INTEGER NOT NULL,event_id TEXT NOT NULL,PRIMARY KEY(owner,provider))'))
        self.monthly=MonthlyVotes(ledger,config)

    def interval(self, provider):
        # A configured longer interval remains valid; legacy zero/UI-only short
        # intervals cannot disable the daily reward restriction.
        return max(1440,self.config.get('voting',{}).get('providers',{}).get(provider,{}).get('vote_interval_minutes',1440))*60

    def eligibility(self, db, owner, provider):
        row=db.execute('SELECT last_credited,next_eligible FROM vote_eligibility WHERE owner=? AND provider=?',(owner,provider)).fetchone()
        if row:return int(row['last_credited']),int(row['next_eligible'])
        old=db.execute("SELECT id,credited FROM vote_events WHERE owner=? AND provider=? AND status='credited' ORDER BY credited DESC,id DESC LIMIT 1",(owner,provider)).fetchone()
        if not old:return 0,0
        next_at=int(old['credited'])+self.interval(provider)
        db.execute('INSERT INTO vote_eligibility VALUES(?,?,?,?,?)',(owner,provider,old['credited'],next_at,old['id']))
        return int(old['credited']),next_at

    def providers_view(self, enabled_only=False, owner=None):
        configured = self.config.get('voting', {}).get('providers', {})
        if owner is not None: owner=str(uuid.UUID(owner))
        with self.ledger.connection() as db:
            timers={provider:self.eligibility(db,owner,provider) for provider in self.providers} if owner else {}
            rows = {row['provider']: row for row in db.execute('SELECT * FROM vote_provider_settings').fetchall()}
            result=[]
            # Provider registration order is also the stable order in Telegram.
            for provider_id, adapter in self.providers.items():
                row=rows.get(provider_id)
                if not row:
                    continue
                if enabled_only and not row['enabled']:
                    continue
                item=configured.get(provider_id, {})
                result.append({'id':provider_id,'name':adapter.display_name,'enabled':bool(row['enabled']),
                               'rewardMinor':row['reward'],'voteUrl':item.get('vote_url','') if isinstance(item.get('vote_url',''),str) else '',
                               'intervalMinutes':self.interval(provider_id)//60,'eligibilityKnown':owner is not None,
                               'nextEligibleAt':timers.get(provider_id,(0,0))[1],
                               'remainingSeconds':max(0,timers.get(provider_id,(0,0))[1]-int(time.time()))})
            return result

    def player_votes(self, owner):
        owner=str(uuid.UUID(owner))
        with self.ledger.connection() as db:
            counts={row['provider']:int(row['votes']) for row in db.execute(
                "SELECT provider,COUNT(*) AS votes FROM vote_events "
                "WHERE owner=? AND status='credited' GROUP BY provider",(owner,)).fetchall()}
        return [{'provider':provider_id,'name':adapter.display_name,'votes':counts.get(provider_id,0)}
                for provider_id,adapter in self.providers.items()]

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
        value=str(event.provider).strip().casefold()
        matches=[key for key in self.providers if key.casefold()==value]
        if not matches:
            matches=[key for key,adapter in self.providers.items()
                     if callable(getattr(adapter,'matches_votifier',None)) and adapter.matches_votifier(value)]
        if len(matches)!=1:raise ValueError('unknown-or-ambiguous-provider')
        canonical=matches[0]
        event=VoteEvent(canonical,event.nickname,event.source_timestamp,event.replay_key)
        with self.ledger.connection() as db:
            setting=db.execute('SELECT * FROM vote_provider_settings WHERE provider=?',(event.provider,)).fetchone()
            existing=db.execute('SELECT * FROM vote_events WHERE provider=? AND replay_key=?',
                                (event.provider,event.replay_key)).fetchone()
            if not existing:
                minute=event.source_timestamp-event.source_timestamp%60
                history=event.replay_key.startswith('history:')
                sql=('SELECT * FROM vote_events WHERE provider=? AND normalized_nickname=? '
                     'AND source_timestamp BETWEEN ? AND ?')
                args=[event.provider,event.nickname.lower(),minute,minute+59]
                if not history:
                    sql += " AND replay_key LIKE 'history:%'"
                existing=db.execute(sql,args).fetchone()
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
            rows=db.execute("SELECT * FROM vote_events WHERE status='pending' AND normalized_nickname=? ORDER BY received,source_timestamp,id",
                            (nickname.lower(),)).fetchall()
            for row in rows:
                _,next_at=self.eligibility(db,owner,row['provider'])
                if now<next_at:
                    db.execute("UPDATE vote_events SET owner=?,display_nickname=?,status='cooldown' WHERE id=? AND status='pending'",(owner,display_name,row['id']))
                    continue
                Ledger.entry(db,'vote:'+row['id'],owner,row['actual_reward'])
                db.execute("UPDATE vote_events SET owner=?,display_nickname=?,credited=?,status='credited' WHERE id=? AND status='pending'",
                           (owner,display_name,now,row['id']))
                db.execute('INSERT OR IGNORE INTO vote_notifications(event_id,owner,nickname,reward,created) VALUES(?,?,?,?,?)',
                           (row['id'],owner,display_name,row['actual_reward'],now))
                db.execute('INSERT INTO vote_eligibility VALUES(?,?,?,?,?) ON CONFLICT(owner,provider) DO UPDATE SET last_credited=excluded.last_credited,next_eligible=excluded.next_eligible,event_id=excluded.event_id',
                           (owner,row['provider'],now,now+self.interval(row['provider']),row['id']))
                credited+=row['actual_reward'];votes+=1
        return {'votes':votes,'creditedMinor':credited}

    def notifications(self, limit=20):
        limit=max(1,min(int(limit),100))
        with self.ledger.connection() as db:
            rows=db.execute('''SELECT n.event_id AS id,n.owner,n.nickname,n.reward AS rewardMinor,
                COALESCE(w.balance,0) AS balanceMinor
                FROM vote_notifications n LEFT JOIN wallets w ON w.owner=n.owner
                WHERE n.delivered=0 AND n.due<=? ORDER BY n.created,n.event_id LIMIT ?''',
                (int(time.time()),limit)).fetchall()
            return [dict(row) for row in rows]

    def acknowledge_notification(self, event_id, success=True):
        if not isinstance(event_id,str) or not re.fullmatch(r'[0-9a-f]{32}',event_id):
            raise ValueError('Invalid notification')
        if type(success) is not bool:
            raise ValueError('Invalid notification state')
        with self.ledger.connection() as db:
            if success:
                db.execute('UPDATE vote_notifications SET delivered=1 WHERE event_id=?',(event_id,))
            else:
                db.execute('UPDATE vote_notifications SET due=? WHERE event_id=? AND delivered=0',
                           (int(time.time())+300,event_id))

    def statistics(self, provider=None):
        if provider is not None and provider not in self.providers:
            raise ValueError('Unknown voting provider')
        where='WHERE provider=?' if provider else ''
        args=(provider,) if provider else ()
        now=int(time.time())
        local=time.localtime(now)
        today=int(time.mktime((local.tm_year,local.tm_mon,local.tm_mday,0,0,0,0,0,-1)))
        week=now-7*86400
        month=now-30*86400
        with self.ledger.connection() as db:
            totals=db.execute(f'''SELECT
                SUM(CASE WHEN status='credited' THEN 1 ELSE 0 END) AS total,
                SUM(CASE WHEN status='credited' AND received>=? THEN 1 ELSE 0 END) AS today,
                SUM(CASE WHEN status='credited' AND received>=? THEN 1 ELSE 0 END) AS week,
                SUM(CASE WHEN status='credited' AND received>=? THEN 1 ELSE 0 END) AS month,
                SUM(CASE WHEN status='credited' THEN actual_reward ELSE 0 END) AS rewarded,
                SUM(CASE WHEN status='credited' AND received>=? THEN actual_reward ELSE 0 END) AS rewarded_today,
                SUM(CASE WHEN status='credited' AND received>=? THEN actual_reward ELSE 0 END) AS rewarded_week,
                SUM(CASE WHEN status='credited' AND received>=? THEN actual_reward ELSE 0 END) AS rewarded_month,
                COUNT(DISTINCT CASE WHEN status='credited' THEN owner END) AS unique_total,
                COUNT(DISTINCT CASE WHEN status='credited' AND received>=? THEN owner END) AS unique_today,
                COUNT(DISTINCT CASE WHEN status='credited' AND received>=? THEN owner END) AS unique_week,
                COUNT(DISTINCT CASE WHEN status='credited' AND received>=? THEN owner END) AS unique_month,
                SUM(CASE WHEN status='pending' THEN 1 ELSE 0 END) AS pending,
                COUNT(DISTINCT CASE WHEN status='pending' THEN normalized_nickname END) AS pending_players,
                MIN(CASE WHEN status='credited' THEN received END) AS first_received,
                MAX(CASE WHEN status='credited' THEN received END) AS last_received
                FROM vote_events {where}''',(today,week,month,today,week,month,today,week,month,*args)).fetchone()
            raw_rows=db.execute('''SELECT provider,
                SUM(CASE WHEN status='credited' THEN 1 ELSE 0 END) AS votes,
                SUM(CASE WHEN status='credited' AND received>=? THEN 1 ELSE 0 END) AS today,
                SUM(CASE WHEN status='credited' AND received>=? THEN 1 ELSE 0 END) AS week,
                SUM(CASE WHEN status='credited' AND received>=? THEN 1 ELSE 0 END) AS month,
                SUM(CASE WHEN status='credited' THEN actual_reward ELSE 0 END) AS rewarded,
                SUM(CASE WHEN status='credited' AND received>=? THEN actual_reward ELSE 0 END) AS rewarded_today,
                SUM(CASE WHEN status='credited' AND received>=? THEN actual_reward ELSE 0 END) AS rewarded_week,
                SUM(CASE WHEN status='credited' AND received>=? THEN actual_reward ELSE 0 END) AS rewarded_month,
                COUNT(DISTINCT CASE WHEN status='credited' THEN owner END) AS unique_total,
                COUNT(DISTINCT CASE WHEN status='credited' AND received>=? THEN owner END) AS unique_month,
                SUM(CASE WHEN status='pending' THEN 1 ELSE 0 END) AS pending,
                COUNT(DISTINCT CASE WHEN status='pending' THEN normalized_nickname END) AS pending_players
                FROM vote_events GROUP BY provider''',(today,week,month,today,week,month,month)).fetchall() if provider is None else []
            clean=lambda value: int(value or 0)
            rows=[]
            by_provider={row['provider']:row for row in raw_rows}
            for provider_id in self.providers:
                row=by_provider.get(provider_id)
                if row:
                    rows.append({key:clean(row[key]) if key!='provider' else row[key] for key in row.keys()})
            player_where="WHERE status='credited'" + (' AND provider=?' if provider else '')
            top_players=[dict(row) for row in db.execute(f'''SELECT
                MAX(display_nickname) AS nickname,COUNT(*) AS votes,SUM(actual_reward) AS rewarded,
                MAX(received) AS lastReceived FROM vote_events {player_where}
                GROUP BY owner ORDER BY votes DESC,rewarded DESC,lastReceived DESC LIMIT 10''',args).fetchall()]
            daily_where="WHERE status='credited' AND received>=?" + (' AND provider=?' if provider else '')
            daily_rows=db.execute(f'''SELECT received,owner,actual_reward FROM vote_events {daily_where}
                ORDER BY received''',(today-6*86400,*args)).fetchall()
            daily=[]
            for offset in range(6,-1,-1):
                start=today-offset*86400
                end=start+86400
                matches=[row for row in daily_rows if start<=row['received']<end]
                daily.append({'date':time.strftime('%d.%m',time.localtime(start)),'votes':len(matches),
                              'unique':len({row['owner'] for row in matches}),
                              'rewardedMinor':sum(row['actual_reward'] for row in matches)})
            return {'total':clean(totals['total']),'today':clean(totals['today']),'week':clean(totals['week']),
                    'month':clean(totals['month']),'rewardedMinor':clean(totals['rewarded']),
                    'rewardedTodayMinor':clean(totals['rewarded_today']),
                    'rewardedWeekMinor':clean(totals['rewarded_week']),
                    'rewardedMonthMinor':clean(totals['rewarded_month']),
                    'uniqueTotal':clean(totals['unique_total']),'uniqueToday':clean(totals['unique_today']),
                    'uniqueWeek':clean(totals['unique_week']),'uniqueMonth':clean(totals['unique_month']),
                    'pending':clean(totals['pending']),'pendingPlayers':clean(totals['pending_players']),
                    'firstReceived':clean(totals['first_received']),'lastReceived':clean(totals['last_received']),
                    'providers':rows,'topPlayers':top_players,'daily':daily}


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

    def record_votifier(self, nickname, service_name, timestamp):
        if not re.fullmatch(r'[A-Za-z0-9_]{1,16}',nickname or ''):
            raise ValueError('invalid-fields')
        if not isinstance(service_name,str) or not 1 <= len(service_name) <= 80:
            raise ValueError('invalid-fields')
        text=str(timestamp)
        if not text.isdigit() or int(text)<=0:
            raise ValueError('invalid-fields')
        matches=[provider for provider in self.providers.values() if provider.matches_votifier(service_name)]
        if len(matches)!=1:
            raise ValueError('unknown-votifier-service' if not matches else 'ambiguous-votifier-service')
        provider=matches[0].provider_id
        # Preserve the existing MinecraftRating replay identity across upgrades.
        replay=hashlib.sha256((provider+'\0votifier\0'+service_name+'\0'+nickname.lower()+'\0'+text).encode()).hexdigest()
        return self.repository.accept(VoteEvent(provider,nickname,int(text),replay))
