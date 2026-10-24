"""Quality gates exercise the authored full corpus and reject realistic content regressions."""
import copy
import sys
import unittest
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import build_word_study_notes as compiler

class StudyResourceQualityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows=compiler.load_cards()
        cls.compiled=compiler.build()
    def altered(self,word,field,value):
        rows=copy.deepcopy(self.rows)
        next(r for r in rows if r['word']==word)[field]=value
        return rows
    def rejects(self,rows):
        with patch.object(compiler,'load_cards',return_value=rows):
            with self.assertRaises((AssertionError,ValueError)):
                compiler.build()
    def test_every_original_word_has_a_distinct_bilingual_card_and_explicit_pos(self):
        cards=self.compiled['studyCards'];profiles=self.compiled['morphology']
        self.assertEqual(1640,len(cards));self.assertEqual(cards.keys(),profiles.keys())
        self.assertEqual(1640,len({r['example'].lower() for r in cards.values()}))
        self.assertTrue(all(r['pos'] and r['translation'] for r in cards.values()))
    def test_wrong_target_example_is_rejected(self):
        self.rejects(self.altered('automatic','example','The new machine starts without human help every morning.'))
    def test_multiple_targets_cannot_create_an_ambiguous_cloze(self):
        self.rejects(self.altered('automatic','example','An automatic door requires an automatic control system.'))
    def test_duplicate_or_unknown_words_cannot_inflate_coverage(self):
        self.rejects(self.rows+[copy.deepcopy(self.rows[0])])
        rows=copy.deepcopy(self.rows);rows[0]['word']='unlisted-placeholder';self.rejects(rows)
    def test_missing_translation_and_repeated_template_sentences_are_rejected(self):
        self.rejects(self.altered('automatic','translation','English only'))
        self.rejects(self.altered('automatic','example','Automatic.'))
    def test_invalid_pos_and_wrong_collocation_are_rejected(self):
        self.rejects(self.altered('automatic','pos','unreviewed'))
        self.rejects(self.altered('automatic','collocation','a different word entirely'))
    def test_school_pos_does_not_leak_rare_nouns_or_root_metadata(self):
        profiles=self.compiled['morphology']
        self.assertNotIn('automatics',profiles['automatic']['text'])
        self.assertNotIn('词根变化',str(profiles));self.assertNotIn('词根/原形',str(profiles))
        self.assertNotIn('过去式',profiles['wind']['forms'])
        self.assertNotIn('现在分词',profiles['training']['forms'])
        self.assertIn('通常不可数',profiles['knowledge']['text'])
    def test_irregular_and_homograph_forms_are_explicitly_distinguished(self):
        profiles=self.compiled['morphology']
        self.assertIn('bases',profiles['basis']['text'])
        self.assertIn('statuses',profiles['status']['text'])
        self.assertIn('founded',profiles['found']['text']);self.assertIn('区别于 find',profiles['found']['text'])
        self.assertIn('lied',profiles['lie']['text']);self.assertIn('lain',profiles['lie']['text'])
        self.assertIn('不构造 goodses',profiles['goods']['text'])
        self.assertIn('单复数同形',profiles['means']['text'])
        self.assertIn('media',profiles['medium']['text']);self.assertNotIn('mediums',profiles['medium']['forms'].values())
        self.assertIn('focuses',profiles['focus']['text'])
        self.assertEqual('more curious',profiles['curious']['forms']['比较级'])
        self.assertEqual('messiest',profiles['messy']['forms']['最高级'])
        self.assertEqual('better',profiles['well']['forms']['比较级'])
        self.assertEqual('sharper',profiles['sharp']['forms']['比较级'])
        self.assertNotIn('more single',profiles['single']['text'])
        self.assertNotIn('程度比较常用 more superior',profiles['superior']['text'])
        for word,profile in profiles.items():
            self.assertEqual('比较级' in profile['forms'],'最高级' in profile['forms'],word)
    def test_missing_source_inflections_require_an_explicit_decision(self):
        with self.assertRaises(ValueError):
            compiler.morphology('example',{'pos':'v'},'',{}, {'uncountable_core':[],'noun_usage':{},'overrides':{},'non_comparing_adjectives':[]})
    def test_compiled_asset_exactly_matches_the_reviewable_source(self):
        import json
        saved=json.loads((compiler.ROOT/'app/src/main/assets/word-study-notes.json').read_text())
        self.assertEqual(self.compiled,saved)

if __name__=='__main__':unittest.main()
