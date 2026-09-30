"""Best-effort recovery of callbacks from MinecraftRating's public last-votes table."""
from datetime import datetime, timedelta, timezone
from html.parser import HTMLParser
import re
from urllib.request import Request, urlopen

from vote_providers import VoteEvent


class _LastVotesParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.in_heading = False
        self.heading = []
        self.capture_table = False
        self.in_cell = False
        self.cell = []
        self.row = []
        self.rows = []

    def handle_starttag(self, tag, attrs):
        if tag == 'h3':
            self.in_heading = True
            self.heading = []
        elif tag == 'table' and 'Последние 10 голосов' in ''.join(self.heading):
            self.capture_table = True
        elif self.capture_table and tag == 'tr':
            self.row = []
        elif self.capture_table and tag == 'td':
            self.in_cell = True
            self.cell = []

    def handle_data(self, data):
        if self.in_heading:
            self.heading.append(data)
        if self.in_cell:
            self.cell.append(data)

    def handle_endtag(self, tag):
        if tag == 'h3':
            self.in_heading = False
        elif self.capture_table and tag == 'td':
            self.in_cell = False
            self.row.append(' '.join(''.join(self.cell).split()))
        elif self.capture_table and tag == 'tr' and len(self.row) == 2:
            self.rows.append(tuple(self.row))
        elif self.capture_table and tag == 'table':
            self.capture_table = False


class MinecraftRatingHistory:
    MOSCOW = timezone(timedelta(hours=3))
    DATE = re.compile(r'(\d{2})\.(\d{2})\.(\d{4}),\s*в\s*(\d{2}):(\d{2})\Z')
    NICK = re.compile(r'[A-Za-z0-9_]{1,16}\Z')

    def __init__(self, url, timeout=15):
        self.url, self.timeout = url, timeout

    def fetch(self):
        request = Request(self.url, headers={'User-Agent':'VaultTracker voting recovery/1.0'})
        with urlopen(request, timeout=self.timeout) as response:
            if response.status != 200:
                raise RuntimeError('history-http-status')
            data = response.read(1_000_001)
            if len(data) > 1_000_000:
                raise RuntimeError('history-response-too-large')
        return self.parse(data.decode('utf-8'))

    @classmethod
    def parse(cls, html):
        parser = _LastVotesParser()
        parser.feed(html)
        events=[]
        for nickname, date_text in parser.rows:
            match=cls.DATE.fullmatch(date_text)
            if not cls.NICK.fullmatch(nickname) or not match:
                continue
            day,month,year,hour,minute=map(int,match.groups())
            timestamp=int(datetime(year,month,day,hour,minute,tzinfo=cls.MOSCOW).timestamp())
            replay=f'history:{nickname.lower()}:{timestamp}'
            events.append(VoteEvent('minecraft_rating',nickname,timestamp,replay))
        return events
