"""Check audit traceability only; presence of references is NOT proof of feature completion."""
import json,re
from pathlib import Path
root=Path(__file__).resolve().parents[1]
doc=json.loads((root/'docs/audit/acceptance.json').read_text())
source=(root/'docs/audit/original-requirements.txt').read_text().splitlines()
assert len(source)==doc['paragraph_count']==204
assert set(doc['source_coverage'])=={str(i) for i in range(1,len(source)+1)}
ids={row['id'] for row in doc['items']}
assert len(ids)==len(doc['items'])==318
for p,coverage in doc['source_coverage'].items():
    assert coverage['kind'] in ('structure','requirements')
    if coverage['kind']=='requirements':assert coverage['ids'] and set(coverage['ids'])<=ids
for row in doc['items']:
    assert row['criterion'].strip()
    assert row['initial_status'] in ('已完整实现','部分实现','未实现')
    assert row['final_status'] in ('已完整实现','部分实现','未实现')
    assert row['evidence'] and row['tests']
    for ref in row['evidence']+row['tests']:
        text=(root/ref['file']).read_text()
        assert ref['symbol'] in text,(row['id'],ref['file'],ref['symbol'])
        assert (ref.get('logic') or ref.get('assertions','')).strip()
    if row['final_status']!='已完整实现':assert row['blockers']
table=(root/'docs/V1_REQUIREMENTS_AUDIT.md').read_text()
assert set(re.findall(r'^\| (R\d{3}) \|',table,re.M))==ids
assert len(re.findall(r'^\| R\d{3} \|',table,re.M))==318
assert '尚不能声明原始 v1.0 规格全部完成' in table
print('PASS: all 204 source paragraphs and table cells map to 318 reviewed criteria with code/test references; known gaps remain explicit')
print('This verifies document traceability, not runtime feature completion.')
