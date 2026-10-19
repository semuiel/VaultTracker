"""Calendar ranking and atomic monthly prizes, sharing the existing wallet ledger."""
from datetime import datetime, timedelta, timezone
import time
from ledger import Ledger
from vote_schema import migrate
from itertools import groupby


class MonthlyVotes:
    def __init__(self, ledger, config):
        self.ledger=ledger
        voting=config.get('voting',{})
        offset=voting.get('calendar_utc_offset_minutes',420)
        if type(offset) is not int or not -720<=offset<=840:
            raise ValueError('Invalid voting calendar offset')
        self.zone=timezone(timedelta(minutes=offset))
        reward=voting.get('monthly_reward_minor',30000)
        policy=voting.get('monthly_tie_policy','full')
        self.validate(reward,policy)
        with ledger.connection() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS vote_month_settings(id INTEGER PRIMARY KEY CHECK(id=1),reward INTEGER NOT NULL,policy TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS vote_month_results(month TEXT PRIMARY KEY,reward INTEGER NOT NULL,policy TEXT NOT NULL,closed INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS vote_month_ranks(month TEXT NOT NULL,owner TEXT NOT NULL,nickname TEXT NOT NULL,votes INTEGER NOT NULL,
                    PRIMARY KEY(month,owner));
                CREATE TABLE IF NOT EXISTS vote_month_prizes(month TEXT NOT NULL,kind TEXT NOT NULL,owner TEXT NOT NULL,rank INTEGER NOT NULL,amount INTEGER NOT NULL,
                    PRIMARY KEY(month,kind,owner));
            ''')
            db.execute('INSERT OR IGNORE INTO vote_month_settings(id,reward,policy) VALUES(1,?,?)',(reward,policy))
        with ledger.connection() as db:
            def top_three(tx):
                tx.execute('ALTER TABLE vote_month_settings ADD COLUMN reward2 INTEGER NOT NULL DEFAULT 20000')
                tx.execute('ALTER TABLE vote_month_settings ADD COLUMN reward3 INTEGER NOT NULL DEFAULT 10000')
                # Historical results were single-winner settlements. Never back-pay them.
                tx.execute('ALTER TABLE vote_month_results ADD COLUMN reward2 INTEGER NOT NULL DEFAULT 0')
                tx.execute('ALTER TABLE vote_month_results ADD COLUMN reward3 INTEGER NOT NULL DEFAULT 0')
            migrate(db,'monthly_top_three',1,top_three)

    @staticmethod
    def validate(reward,policy):
        if type(reward) is not int or not 0<=reward<=100_000_000 or policy not in ('full','split'):
            raise ValueError('Invalid monthly prize settings')

    def settings(self):
        with self.ledger.connection() as db:
            r=db.execute('SELECT * FROM vote_month_settings WHERE id=1').fetchone()
            return {'rewardMinor':r['reward'],'prizesMinor':[r['reward'],r['reward2'],r['reward3']],'tiePolicy':r['policy']}

    def update(self,actor,reward=None,policy=None,place=1):
        if not isinstance(actor,str) or not actor or len(actor)>128:
            raise ValueError('Invalid actor')
        if type(place) is not int or place not in (1,2,3):raise ValueError('Invalid prize place')
        key={1:'reward',2:'reward2',3:'reward3'}[place]
        with self.ledger.connection() as db:
            old=db.execute('SELECT * FROM vote_month_settings WHERE id=1').fetchone()
            reward=old[key] if reward is None else reward
            policy=old['policy'] if policy is None else policy
            self.validate(reward,policy)
            for field,value in [(key,reward),('policy',policy)]:
                if old[field]!=value:
                    db.execute('INSERT INTO vote_settings_audit(actor,provider,setting,old_value,new_value,created) VALUES(?,?,?,?,?,?)',
                               (actor,'monthly',field,str(old[field]),str(value),int(time.time())))
            db.execute('UPDATE vote_month_settings SET '+key+'=?,policy=? WHERE id=1',(reward,policy))
        return self.settings()

    def month(self,now=None):
        return datetime.fromtimestamp(time.time() if now is None else now,self.zone).strftime('%Y-%m')

    def bounds(self,month):
        start=datetime.strptime(month,'%Y-%m').replace(tzinfo=self.zone)
        if start.strftime('%Y-%m')!=month:
            raise ValueError('Invalid month')
        end=start.replace(year=start.year+1,month=1) if start.month==12 else start.replace(month=start.month+1)
        return int(start.timestamp()),int(end.timestamp())

    def top(self,month=None,page=0):
        month=month or self.month();start,end=self.bounds(month)
        with self.ledger.connection() as db:
            closed=db.execute('SELECT * FROM vote_month_results WHERE month=?',(month,)).fetchone()
            if closed:
                source='SELECT owner,nickname,votes FROM vote_month_ranks WHERE month=?';args=(month,)
            else:
                source="SELECT owner,MAX(display_nickname) AS nickname,COUNT(*) AS votes FROM vote_events WHERE status='credited' AND credited>=? AND credited<? GROUP BY owner"
                args=(start,end)
            count=db.execute('SELECT COUNT(*) FROM ('+source+')',args).fetchone()[0]
            pages=max(1,(count+9)//10);page=max(0,min(int(page),pages-1))
            ranked='SELECT owner,nickname,votes,RANK() OVER(ORDER BY votes DESC) AS rank FROM ('+source+')'
            rows=[dict(r) for r in db.execute(ranked+' ORDER BY votes DESC,owner LIMIT 10 OFFSET ?',(*args,page*10))]
            return {'month':month,'page':page,'pages':pages,'rows':rows,'closed':bool(closed)}

    def close_month(self,month,now=None):
        now=int(time.time() if now is None else now);start,end=self.bounds(month)
        if end>now:
            raise ValueError('Month has not ended')
        with self.ledger.connection() as db:
            if db.execute('SELECT 1 FROM vote_month_results WHERE month=?',(month,)).fetchone():
                return False
            settings=db.execute('SELECT * FROM vote_month_settings WHERE id=1').fetchone()
            db.execute('INSERT INTO vote_month_results(month,reward,policy,closed,reward2,reward3) VALUES(?,?,?,?,?,?)',
                       (month,settings['reward'],settings['policy'],now,settings['reward2'],settings['reward3']))
            db.execute("INSERT INTO vote_month_ranks SELECT ?,owner,MAX(display_nickname),COUNT(*) FROM vote_events WHERE status='credited' AND credited>=? AND credited<? GROUP BY owner",(month,start,end))
            ranked=db.execute('SELECT owner,votes FROM vote_month_ranks WHERE month=? ORDER BY votes DESC,owner',(month,)).fetchall()
            rank=1
            for _,group in groupby(ranked,key=lambda row:row['votes']):
                winners=list(group)
                if rank>3:break
                reward=settings[{1:'reward',2:'reward2',3:'reward3'}[rank]]
                kind='leader' if rank==1 else 'place'+str(rank)
                for index,winner in enumerate(winners):
                    amount=reward if settings['policy']=='full' else reward//len(winners)+(index<reward%len(winners))
                    db.execute('INSERT INTO vote_month_prizes VALUES(?,?,?,?,?)',(month,kind,winner['owner'],rank,amount))
                    if amount:
                        Ledger.entry(db,'vote-month:'+month+':'+kind+':'+winner['owner'],winner['owner'],amount)
                rank+=len(winners)
            return True

    def settle(self,now=None):
        now=int(time.time() if now is None else now)
        with self.ledger.connection() as db:
            first=db.execute("SELECT MIN(credited) FROM vote_events WHERE status='credited'").fetchone()[0]
        if first is None:
            return
        month=self.month(first);current=self.month(now)
        while month<current:
            self.close_month(month,now)
            month=self.month(self.bounds(month)[1])
