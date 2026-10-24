"""Verify original-resource identity and genuine per-word supplementary contexts."""
import hashlib,json,re
from pathlib import Path
root=Path(__file__).resolve().parents[1]
assets=root/'app/src/main/assets'
pins=json.loads((root/'config/original-resource-pins.json').read_text())
assert hashlib.sha256((assets/'vocabulary.json').read_bytes()).hexdigest()==pins['vocabulary_sha256']
assert hashlib.sha256((assets/'pronunciation/manifest.json').read_bytes()).hexdigest()==pins['audio_manifest_sha256']
audio=sorted((assets/'pronunciation').glob('*.mp3'));digest=hashlib.sha256()
for path in audio:digest.update(path.name.encode()+b'\0'+hashlib.sha256(path.read_bytes()).digest())
assert len(audio)==pins['audio_count']==1640
assert digest.hexdigest()==pins['audio_aggregate_sha256']
manifest=json.loads((assets/'pronunciation/manifest.json').read_text())['entries']
assert len({entry['word'] for entry in manifest})==1640
assert all((assets/'pronunciation'/entry['file']).is_file() for entry in manifest)
words=json.loads((assets/'vocabulary.json').read_text())['words']
extra=json.loads((assets/'vocab-enrichment.json').read_text())['words']
assert set(extra)=={w['word'] for w in words}
for word in words:
    token=re.compile(r'(?i)(?<![a-z])'+re.escape(word['word'])+r'(?![a-z])')
    examples=extra[word['word']]['examples']
    assert all(e['attribution'].strip() and e['text'].strip() for e in examples)
    texts={e['text'] for e in examples if len(token.findall(e['text']))==1}
    if len(token.findall(word['example']))==1:texts.add(word['example'])
    assert len(texts)>=2,word['word']
assert (assets/'ECDICT-UPSTREAM-LICENSE.txt').is_file()
assert (assets/'ENRICHMENT-ATTRIBUTION.txt').is_file()
print('PASS: original vocabulary/IPA/examples and all 1640 original audio files are byte-identical; each word has >=2 attributed offline contexts')
from build_word_study_notes import build
compiled=json.loads((assets/'word-study-notes.json').read_text())
assert compiled==build(),'Rebuild word-study-notes.json from its reviewed source files'
assert len(compiled['morphology'])==1640
assert compiled['coverage']['explicitMorphologyReviews']==264
old=set(re.findall(r'"([a-z-]+)" to WordNote', (root/'app/src/main/java/com/tanhaowen/contextenglish/adaptive/WordNotes.kt').read_text()))
print('Reviewed high-school cards:',len(compiled['studyCards']),'of 1640; effective collocations:',len(old|compiled['studyCards'].keys()),'of 1640')
print('Morphology: 1376 ECDICT records + 264 explicit word-by-word usage reviews. Original dictionary/audio remain unchanged.')
