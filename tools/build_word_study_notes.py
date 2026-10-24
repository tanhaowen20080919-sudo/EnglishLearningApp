"""Compile explicitly reviewed content; never manufacture coverage or modify the original dictionary."""
import csv, json, re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def build():
    base=json.loads((ROOT/'app/src/main/assets/vocabulary.json').read_text())['words']
    original={w['word']:w for w in base}
    extra=json.loads((ROOT/'app/src/main/assets/vocab-enrichment.json').read_text())['words']
    reviewed=json.loads((ROOT/'content/reviewed-morphology.json').read_text())['records']
    missing={w for w in original if not extra[w]['forms']}
    if set(reviewed)!=missing: raise ValueError('Morphology review must exactly cover original empty exchange records')
    rows=list(csv.DictReader((ROOT/'content/word-study-notes.tsv').open(),delimiter='\t'))
    notes={}
    for row in rows:
        word=row['word'];assert word in original and word not in notes,word
        assert all(row.get(k,'').strip() for k in ['meaning','collocation','example','translation']),word
        assert re.search('[\u4e00-\u9fff]',row['meaning']) and re.search('[\u4e00-\u9fff]',row['translation']),word
        assert 6<=len(row['example'].split())<=25,(word,row['example'])
        token=re.compile(r'(?i)(?<![a-z])'+re.escape(word)+r'(?![a-z])')
        variants=[word]+re.findall(r'[A-Za-z]+(?:-[A-Za-z]+)*',extra[word]['forms'])
        assert any(re.search(r'(?i)(?<![a-z])'+re.escape(v)+r'(?![a-z])',row['collocation']) for v in variants),word
        assert token.search(row['example']),word
        assert not re.search(r'\b(?:Tom|Mary|John)\b',row['example']),word
        notes[word]={k:row[k].strip() for k in ['meaning','collocation','example','translation']}
        notes[word]['originalMeaning']=original[word]['meaning']
        notes[word]['provenance']='Original EnglishLearningApp educational text; Codex-assisted language review; not an official exam quotation or independent teacher certification'
    forms={word:{'text':reviewed[word]['note'],'basis':'explicit-usage-review','reference':reviewed[word]['reference'],'kind':reviewed[word]['kind']} if word in reviewed else
        {'text':extra[word]['forms'].replace('复数 ', '名词复数（仅名词义） '),'basis':'ECDICT exchange data','reference':'https://github.com/skywind3000/ECDICT','kind':'attested-forms'} for word in original}
    assert len(forms)==1640 and all(x['text'].strip() for x in forms.values())
    return {'format':1,'coverage':{'originalWords':1640,'originalStudyCards':len(notes),'morphologyNotes':len(forms),'explicitMorphologyReviews':len(reviewed)},
        'limitations':['Only listed study cards have reviewed high-school core senses, bilingual examples and collocations; unlisted words keep the original dictionary and attributed corpora.','Morphology notes describe common school usage, not every rare, dialectal or technical inflection.'],
        'studyCards':notes,'morphology':forms}
if __name__=='__main__':
    result=build();out=ROOT/'app/src/main/assets/word-study-notes.json';out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print('Compiled verified coverage:',result['coverage'])
