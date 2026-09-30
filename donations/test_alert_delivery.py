import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from ledger import Ledger

class AlertDeliveryTests(unittest.TestCase):
    def test_legacy_acknowledgement_also_completes_recipient_delivery(self):
        with tempfile.TemporaryDirectory() as directory:
            ledger=Ledger(Path(directory)/'ledger.sqlite')
            with ledger.connection() as db:
                db.execute("INSERT INTO alerts VALUES('a','Payment',0)")
            self.assertEqual(2,len(ledger.recipient_alerts([11,22])))
            ledger.acknowledge('a')
            self.assertEqual([],ledger.recipient_alerts([11,22]))

    def test_successful_recipient_is_not_repeated_after_failure_and_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            ledger=Ledger(Path(directory)/'ledger.sqlite')
            with ledger.connection() as db:
                db.execute("INSERT INTO alerts VALUES('a','Payment',0)")
            self.assertEqual(2,len(ledger.recipient_alerts([11,22])))
            ledger.acknowledge_recipient('a',11)
            ledger.acknowledge_recipient('a',22,False)
            ledger=Ledger(ledger.path)
            self.assertEqual([],ledger.recipient_alerts([11,22]))
            with patch('ledger.time.time',return_value=10**12):
                self.assertEqual([22],[a['recipient'] for a in ledger.recipient_alerts([11,22])])
            ledger.acknowledge_recipient('a',22)
            self.assertEqual([],ledger.recipient_alerts([11,22]))
            self.assertEqual([],ledger.alerts())

    def test_failed_recipient_does_not_block_new_alerts_or_revoked_root(self):
        with tempfile.TemporaryDirectory() as directory:
            ledger=Ledger(Path(directory)/'ledger.sqlite')
            with ledger.connection() as db:
                db.executemany('INSERT INTO alerts VALUES(?,?,0)',[(str(i),'Payment') for i in range(25)])
            first=ledger.recipient_alerts([11])
            for item in first: ledger.acknowledge_recipient(item['id'],11,False)
            self.assertEqual(5,len(ledger.recipient_alerts([11])))
            self.assertEqual([],ledger.recipient_alerts([]))
            self.assertTrue(all(a['recipient']==22 for a in ledger.recipient_alerts([22])))

if __name__=='__main__':unittest.main()
