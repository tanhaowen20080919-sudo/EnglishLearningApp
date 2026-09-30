"""Exercise the actual migration SQL against a v1 fixture using SQLite."""
import sqlite3,re,json
from pathlib import Path
root=Path(__file__).resolve().parents[1]
text=(root/'app/src/main/java/com/tanhaowen/contextenglish/data/EnglishDatabase.kt').read_text()
db=sqlite3.connect(':memory:')
for sql in re.findall(r'"""\s*(CREATE TABLE .*?)\s*"""',text,re.S): db.execute(sql)
db.execute("INSERT INTO words(word,phonetic,meaning,context_meaning,example,state,weak,seen_count,correct_count,next_review_at) VALUES('avoid','x','避免','避免','example','LEARNING',1,7,3,1234)")
columns=re.search(r'listOf\((.*?)\)\.forEach \{ db.execSQL\("ALTER TABLE words',text,re.S).group(1)
for definition in re.findall(r'"([^"]+)"',columns):db.execute('ALTER TABLE words ADD COLUMN '+definition)
upgrade=text[text.index('private fun upgradeV2'):text.index('private fun importAssets')]
for sql in re.findall(r'db.execSQL\("([^"$]+)"\)',upgrade):db.execute(sql)
assert db.execute('SELECT state,weak,seen_count,correct_count,next_review_at,familiarity FROM words').fetchone()==('LEARNING',1,7,3,1234,30)
rows=json.loads((root/'app/src/main/assets/vocabulary.json').read_text())['words']
for word in rows:db.execute('INSERT OR IGNORE INTO words(word,phonetic,meaning,context_meaning,example) VALUES(?,?,?,?,?)',(word['word'],word['phonetic'],word['meaning'],word['meaning'],word['example']))
assert db.execute('SELECT COUNT(*) FROM words').fetchone()[0]==1640
assert db.execute("SELECT correct_count FROM words WHERE word='avoid'").fetchone()[0]==3
print('PASS: v1 migration preserves state and review records; full vocabulary coexists with old data')
