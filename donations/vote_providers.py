"""Provider adapters. They authenticate and normalize callbacks; they never touch balances."""
from dataclasses import dataclass
import hashlib
import hmac
import re


@dataclass(frozen=True)
class VoteEvent:
    provider: str
    nickname: str
    source_timestamp: int
    replay_key: str


class VoteProvider:
    provider_id = ''
    display_name = ''

    def __init__(self, config):
        self.config = config

    def parse(self, fields):
        raise NotImplementedError


class MinecraftRatingProvider(VoteProvider):
    provider_id = 'minecraft_rating'
    display_name = 'MinecraftRating'
    _nickname = re.compile(r'[A-Za-z0-9_]{1,16}\Z')

    def parse(self, fields):
        # Official promotion.php: POST username, ip, timestamp, signature;
        # signature = SHA1(username + timestamp + SECRET_KEY), success body = "ok".
        try:
            username = fields['username']
            source_ip = fields['ip']
            timestamp_text = fields['timestamp']
            signature = fields['signature']
        except (KeyError, TypeError):
            raise ValueError('missing-fields') from None
        if not all(isinstance(value, str) and value for value in (username, source_ip, timestamp_text, signature)):
            raise ValueError('missing-fields')
        if not self._nickname.fullmatch(username) or not timestamp_text.isdigit() or int(timestamp_text) <= 0:
            raise ValueError('invalid-fields')
        secret = self.config.get('secret', '')
        if not isinstance(secret, str) or not secret:
            raise RuntimeError('provider-not-configured')
        expected = hashlib.sha1((username + timestamp_text + secret).encode('utf-8')).hexdigest()
        if not hmac.compare_digest(signature.lower().encode('ascii', 'ignore'), expected.encode('ascii')):
            raise ValueError('invalid-signature')
        # MinecraftRating supplies no event ID. The signed nickname and timestamp
        # are its strongest stable event identity. A later timestamp remains a new vote.
        replay = hashlib.sha256((self.provider_id + '\0' + username.lower() + '\0' + timestamp_text).encode()).hexdigest()
        return VoteEvent(self.provider_id, username, int(timestamp_text), replay)


def configured_providers(config):
    settings = config.get('voting', {}).get('providers', {})
    return {provider.provider_id: provider for provider in (MinecraftRatingProvider(settings.get('minecraft_rating', {})),)}
