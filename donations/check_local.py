"""Read-only on-host diagnosis; never prints credentials or player records."""
import http.client
import json
from pathlib import Path
import time

def main():
    folder=Path(__file__).resolve().parent
    config=json.loads((folder/'config.local.json').read_text(encoding='utf-8-sig'))
    report=[]
    for path,body in [('/wallet',{'owner':'00000000-0000-0000-0000-000000000000'}),('/premium-tasks',{}),('/donation-audit',{'page':0})]:
        connection=http.client.HTTPConnection('127.0.0.1',int(config.get('port',18763)),timeout=12)
        started=time.monotonic()
        try:
            connection.request('POST',path,json.dumps(body),{'Authorization':'Bearer '+config['api_key'],'Content-Type':'application/json'})
            response=connection.getresponse()
            payload=json.loads(response.read())
            report.append(f'{path}: HTTP {response.status}, {time.monotonic()-started:.2f}s, JSON object={isinstance(payload,dict)}')
        except Exception as error:
            report.append(f'{path}: {type(error).__name__}, {time.monotonic()-started:.2f}s')
        finally: connection.close()
    text='\n'.join(report)
    (folder/'check-local-result.txt').write_text(text,encoding='utf-8')
    print(text)

if __name__=='__main__':main()
