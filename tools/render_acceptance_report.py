"""Format the reviewer-assigned requirement evidence. This does not decide implementation status."""
from pathlib import Path
from collections import Counter
import hashlib,json
ROOT=Path(__file__).resolve().parents[1]

def main():
    path=ROOT/'docs/audit/acceptance.json';doc=json.loads(path.read_text())
    def link(ref):
        file=ROOT/ref['file'];lines=file.read_text().splitlines()
        matches=[i for i,line in enumerate(lines,1) if ref['symbol'] in line]
        if not matches: raise ValueError('Evidence symbol missing: '+ref['file']+' :: '+ref['symbol'])
        label=Path(ref['file']).name+'::'+ref['symbol'].replace('private ','').replace('suspend ','').replace('open ','')
        return f'[{label}](../{ref["file"]}#L{matches[0]})'
    def clean(value):return value.replace('|','｜').replace('\n',' ')
    initial=Counter(row['initial_status'] for row in doc['items'])
    final=Counter(row['final_status'] for row in doc['items'])
    lines=['# v1.0 原始需求逐项实现审计与验收表','',
        '**结论：尚不能声明原始 v1.0 规格全部完成。** 功能修复已落实到代码，词条资源覆盖和真机验收缺口保留为“部分实现”；不会用构建/测试成功覆盖这些缺口。','',
        f'完整读取原始 DOCX 正文全部 22 章、{doc["paragraph_count"]} 个段落（含表格），拆为 {len(doc["items"])} 条验收项。原文 SHA-256：`{doc["source_document_sha256"]}`。',
        '原文逐段见 [完整提取文本](audit/original-requirements.txt)；结构段与验收项的全量映射见 [机器可读清单](audit/acceptance.json)。章节、表头有结构映射，未删除原文。“后续可扩展七选五”按原文验收扩展点，不冒称已有七选五题库。','',
        f'首轮基线：`{doc["baseline_commit"]}`；回滚分支：`{doc["rollback_branch"]}`。具体首轮缺口见 [第一轮审计](V1_AUDIT_FIRST_PASS.md)。','',
        '| 轮次 | 已完整实现 | 部分实现 | 未实现 |','|---|---:|---:|---:|',
        f'| 首轮 | {initial["已完整实现"]} | {initial["部分实现"]} | {initial["未实现"]} |',
        f'| 当前复核 | {final["已完整实现"]} | {final["部分实现"]} | {final["未实现"]} |','',
        '最新词条跟进见 [code10 词条复查](V1_LEXICAL_UPDATE.md)。上一正式 code9 的执行结果保存在清单 previous_execution_code9；R061 因发现词义排序质量问题而收紧为部分实现。','',
        '## 验证边界','']
    for value in doc['evidence_policy'].values():lines.append('- '+value)
    lines+=['','执行记录：','', '```json',json.dumps(doc['execution'],ensure_ascii=False,indent=2),'```','',
        '表中“实现位置与逻辑”列列出生产文件、函数及其实际作用；“测试”列描述实际断言而非仅列名称。外部AI响应在自动化测试中使用契约夹具，生产路径仍为真实HTTPS客户端。测试/构建不是完成判据的替代品。','',
        '## 完整验收表','',
        '| 编号 | 原始需求 / 验收项 | 实现位置与实际逻辑 | 测试方式 | 首轮状态 | 最终状态 / 剩余缺口 |',
        '|---|---|---|---|---|---|']
    for row in doc['items']:
        source=', '.join(f'[P{n:03d}](audit/original-requirements.txt#L{n})' for n in row['source_paragraphs'])
        evidence='<br>'.join(link(e)+'：'+e['logic'] for e in row['evidence'])
        tests='<br>'.join(link(t)+'：'+t['assertions'] for t in row['tests'])
        status=row['final_status']+('：'+'；'.join(row.get('blockers',[])) if row.get('blockers') else '')
        lines.append('| '+' | '.join(map(clean,[row['id'],source+' '+row['criterion'],evidence,tests,row['initial_status'],status]))+' |')
    lines+=['','## 未完成项及真实限制','']
    for row in doc['items']:
        if row['final_status']!='已完整实现':lines.append('- **'+row['id']+' '+row['criterion']+'**：'+'；'.join(row['blockers']))
    lines+=['','固定正式证书 SHA-256 为 `e13229f272357c3d270ae903c1a93bf3b902138282cf24aabe9e8e172d87fbf1`；applicationId 保持 `com.tanhaowen.contextenglish`。本轮没有生成或替换 Release keystore。','']
    (ROOT/'docs/V1_REQUIREMENTS_AUDIT.md').write_text('\n'.join(lines))
    print('Rendered',len(doc['items']),'reviewed checks;',dict(final))
if __name__=='__main__':main()
