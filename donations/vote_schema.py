"""Small versioned migrations inside the caller's existing wallet transaction."""
import time


def migrate(db, component, version, change):
    db.execute('CREATE TABLE IF NOT EXISTS vote_schema_migrations(component TEXT NOT NULL,version INTEGER NOT NULL,applied INTEGER NOT NULL,PRIMARY KEY(component,version))')
    if db.execute('SELECT 1 FROM vote_schema_migrations WHERE component=? AND version=?',(component,version)).fetchone():
        return
    change(db)
    db.execute('INSERT INTO vote_schema_migrations VALUES(?,?,?)',(component,version,int(time.time())))
