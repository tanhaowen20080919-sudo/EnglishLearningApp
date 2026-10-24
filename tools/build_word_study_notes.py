"""Compile authored school-sense cards and POS-scoped morphology; preserve the original dictionary."""
import csv, json, re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
POS={'n','v','adj','adv','prep','conj','pron','det','modal'}
FIELDS=('meaning','collocation','example','translation')

def load_cards():
    rows=[]
    for path in sorted((ROOT/'content').glob('word-study-notes*.tsv')):
        with path.open() as stream:
            for row in csv.DictReader(stream,delimiter='\t'):
                if None in row or any(v is None for v in row.values()): raise ValueError(f'Malformed TSV in {path}: {row.get("word")}')
                rows.append(row)
    return rows

def morphology(word, row, raw, reviewed, policy):
    pos=set(row['pos'].split(','));slots={}
    for part in raw.split('；'):
        label,_,value=part.partition(' ')
        if value:slots[label]=value
    selected={k:v for k,v in slots.items() if
        ('v' in pos and k in ('第三人称单数','现在分词','过去式','过去分词')) or
        ('n' in pos and k=='复数' and word not in policy['uncountable_core'] and word not in policy['noun_usage']) or
        (pos & {'adj','adv'} and k in ('比较级','最高级'))}
    parts=[];review=reviewed.get(word)
    kind=review['kind'] if review else 'pos-scoped'
    if word in policy['overrides']:
        parts=[policy['overrides'][word]]
    else:
        if word in policy['noun_usage']:
            parts.append(policy['noun_usage'][word])
            if word in {'statistics','data','media','means','customs','goods','staff','crew','public'}:kind='agreement'
        elif word in policy['uncountable_core'] and 'n' in pos:
            parts.append('本卡名词常用义通常不可数，不机械加复数；具体次数/种类等另义须结合语境。');kind='uncountable'
        if review:parts.append(review['note'])
        if 'n' in pos and not any(k=='复数' for k in selected) and not parts:
            raise ValueError('Noun requires an explicit countability/number decision: '+word)
        if 'v' in pos:
            missing={'第三人称单数','现在分词','过去式','过去分词'}-selected.keys()
            if missing:raise ValueError('Verb requires explicit missing slots: '+word+' '+str(missing))
        if selected:parts.append('；'.join(k+' '+v for k,v in selected.items())+'。')
        if pos & {'adj','adv'} and not any(k in selected for k in ['比较级','最高级']):
            if word in policy['non_comparing_adjectives']:
                parts.append('本卡关系/分类等常用义不机械加 -er/-est；程度比较须根据具体语境。')
                if not review:kind='relational'
            elif 'adj' in pos and not review:
                parts.append(f'程度比较常用 more {word} / most {word}，不机械添加 -er/-est。')
            elif 'adv' in pos and not review:
                parts.append('副词用法不采用名词复数或动词变位；不机械加 -er/-est。')
        if not parts:parts.append('本卡为'+row['pos']+'用法，无名词复数或普通动词变位。')
    text=' '.join(dict.fromkeys(parts))
    assert text.strip() and '词根变化' not in text and '词根/原形' not in text,word
    return {'text':text,'kind':kind,'basis':'school-sense POS adaptation + explicit usage review',
            'reference':review['reference'] if review else 'https://github.com/skywind3000/ECDICT',
            'selectedPos':sorted(pos),'forms':selected}

def build():
    original={w['word']:w for w in json.loads((ROOT/'app/src/main/assets/vocabulary.json').read_text())['words']}
    extra=json.loads((ROOT/'app/src/main/assets/vocab-enrichment.json').read_text())['words']
    reviewed=json.loads((ROOT/'content/reviewed-morphology.json').read_text())['records']
    policy=json.loads((ROOT/'content/morphology-policy.json').read_text())
    assert set(reviewed)=={w for w in original if not extra[w]['forms']}
    notes={};forms={};sentences=set()
    for row in load_cards():
        word=row['word'];assert word in original and word not in notes,word
        assert all(row.get(k,'').strip() for k in FIELDS),word
        assert row['pos'] and set(row['pos'].split(','))<=POS,word
        assert re.search('[\u4e00-\u9fff]',row['meaning']) and re.search('[\u4e00-\u9fff]',row['translation']),word
        assert 6<=len(row['example'].split())<=25,(word,row['example'])
        token=re.compile(r'(?i)(?<![a-z])'+re.escape(word)+r'(?![a-z])')
        variants=[word]+re.findall(r'[A-Za-z]+(?:-[A-Za-z]+)*',extra[word]['forms'])
        assert any(re.search(r'(?i)(?<![a-z])'+re.escape(v)+r'(?![a-z])',row['collocation']) for v in variants),(word,row['collocation'])
        assert len(token.findall(row['example']))==1,(word,'exactly one target required')
        assert not re.search(r'\b(?:Tom|Mary|John)\b',row['example']),word
        normalized=re.sub(r'[^a-z]','',row['example'].lower());assert normalized not in sentences,word;sentences.add(normalized)
        assert row['example'].endswith(('.','?','!')),word
        notes[word]={k:row[k].strip() for k in FIELDS}
        notes[word].update(pos=row['pos'],originalMeaning=original[word]['meaning'],provenance='Original EnglishLearningApp school-level educational text; authored and language-checked with Codex; not an official exam quotation or teacher certification')
        forms[word]=morphology(word,row,extra[word]['forms'],reviewed,policy)
    assert set(notes)==set(forms)==set(original),'Every original word needs its own reviewed card/profile'
    return {'format':2,'coverage':{'originalWords':len(original),'originalStudyCards':len(notes),'morphologyNotes':len(forms),'explicitMorphologyReviews':len(reviewed),'posAdaptedProfiles':len(forms)},
            'limitations':['Original school-level examples, not official exam texts. Expert final checks and homograph/audio sense identification are recorded separately.','Only selected school senses/POS determine the shown morphology; rare/dialectal/technical inflections are not asserted.'],
            'studyCards':notes,'morphology':forms}
if __name__=='__main__':
    result=build();out=ROOT/'app/src/main/assets/word-study-notes.json';out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print('Compiled verified coverage:',result['coverage'])
