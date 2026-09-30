"""Checks exact source coverage and the content required by offline word cards."""
import json
from pathlib import Path
root = Path(__file__).resolve().parents[1]
source = set((root / 'vocabulary-source.txt').read_text().splitlines())
rows = json.loads((root / 'app/src/main/assets/vocabulary.json').read_text())['words']
words = {w['word'] for w in rows}
assert len(words) == len(rows), 'Duplicate words'
assert source <= words, f'Missing source words: {source-words}'
for w in rows:
    for key in ('word', 'meaning', 'phonetic', 'partOfSpeech', 'example', 'exampleTranslation'):
        assert w.get(key), f'Missing {key}: {w["word"]}'
print(f'PASS: all {len(source)} source words retained; {len(rows)} complete offline word cards')
