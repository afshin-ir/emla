import importlib.util,json,sqlite3,tempfile,unittest
from pathlib import Path
P=Path(__file__).resolve().parents[1];spec=importlib.util.spec_from_file_location('engine',P/'engine.py');engine=importlib.util.module_from_spec(spec);spec.loader.exec_module(engine)
class EngineTests(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.entries,cls.blocks,cls.rules=engine.load()
 def test_exact_example_has_real_rule_and_source(self):
  i=self.entries.index('آتش‌فام');r=engine.evaluate(i,self.entries[i],self.blocks,self.rules)
  m=next(x for x in r['matches'] if x['rule_id']=='suffix_fam');self.assertEqual(m['block_id'],'item-336');self.assertIn('آتش‌فام',m['evidence']);self.assertEqual(m['status'],'محتمل')
 def test_lookalike_not_promoted(self):
  i=self.entries.index('آب‌رو');r=engine.evaluate(i,self.entries[i],self.blocks,self.rules)
  self.assertFalse(any(m['rule_id']=='compounds' for m in r['matches']))
 def test_ambiguous_spelling_not_condemned(self):
  entry='اتو / اطو';r=engine.evaluate(0,entry,self.blocks,self.rules)
  self.assertNotEqual(r['status'],'ناسازگار مستند');self.assertTrue(any(m['rule_id']=='multi_flag' and m['status']=='نامشخص' for m in r['matches']));self.assertTrue(any(m['rule_id']=='multiple_table-456' and m['status']=='محتمل' for m in r['matches']))
 def test_resume_idempotence_and_input_integrity(self):
  with tempfile.TemporaryDirectory() as d:
   db=Path(d)/'state.sqlite';out=Path(d)/'out'
   self.assertEqual(engine.run(db,batch=3,limit=7),7);self.assertEqual(engine.run(db,batch=2,limit=10),3);self.assertEqual(engine.run(db,limit=10),0)
   self.assertEqual(engine.report(db,out)['processed'],10)
   con=sqlite3.connect(db);con.execute("UPDATE meta SET value='changed' WHERE key='book.json'");con.commit();con.close()
   with self.assertRaises(ValueError):engine.run(db,limit=11)
   with self.assertRaises(ValueError):engine.report(db,out)
 def test_engine_hash_invalidates_checkpoint(self):
  with tempfile.TemporaryDirectory() as d:
   db=Path(d)/'state.sqlite';engine.run(db,limit=1)
   con=sqlite3.connect(db);con.execute("UPDATE meta SET value='old' WHERE key='engine.py'");con.commit();con.close()
   with self.assertRaises(ValueError):engine.run(db,limit=2)
   with self.assertRaises(ValueError):engine.report(db,Path(d)/'out')
 def test_independent_morphology_cases(self):
  cases=json.loads((P/'tests'/'morphology_cases.json').read_text())
  for case in cases:
   with self.subTest(word=case['word'],rule=case['rule']):
    result=engine.evaluate(0,case['word'],self.blocks,self.rules)
    m=next(x for x in result['matches'] if x['rule_id']==case['rule']);a=m['analysis']
    self.assertEqual(a['components'],case['parts']);self.assertEqual(a['observed_separator'],case['observed'])
    if case.get('exception'):self.assertNotIn('expected_if_hypothesis',a)
    else:self.assertEqual(a['expected_if_hypothesis'],case['expected'])
    if 'exception' in case:self.assertEqual(a['exception_or_lexicalized'],case['exception'])
    self.assertEqual(m['status'],'نامشخص')
 def test_no_speculative_split_for_complex_entries(self):
  for word in ('بی‌پایان / بیپایان','بی‌پایان (صفت)','آتش‌فام‌گونه'):
   with self.subTest(word=word):
    r=engine.evaluate(0,word,self.blocks,self.rules);self.assertFalse(any(m['rule_id']=='morph_bi' for m in r['matches']))
 def test_hamza_table_attests_exact_form(self):
  r=engine.evaluate(0,'ائتلاف',self.blocks,self.rules)
  m=next(x for x in r['matches'] if x['rule_id']=='hamza_table');self.assertEqual(m['example_id'],'table-275');self.assertEqual(m['status'],'محتمل')
 def test_independent_real_dictionary_cases(self):
  cases=json.loads((P/'tests'/'real_cases.json').read_text())
  self.assertEqual(len({x['entry'] for x in cases}),len(cases))
  for case in cases:
   with self.subTest(entry=case['entry']):
    self.assertIn(case['entry'],self.entries);i=self.entries.index(case['entry']);r=engine.evaluate(i,case['entry'],self.blocks,self.rules)
    self.assertEqual(r['entry'],self.entries[i]);self.assertNotEqual(r['status'],'ناسازگار مستند')
    if 'forbidden' in case:self.assertNotIn(case['forbidden'],[m['rule_id'] for m in r['matches']]);continue
    m=next(m for m in r['matches'] if m['rule_id']==case['required']);self.assertEqual(m['block_id'],case['block'])
    if 'example' in case:self.assertEqual(m['example_id'],case['example'])
    if 'preferred' in case:self.assertEqual(list(m['table_match']['preferred']),case['preferred'])
    if 'separator' in case:self.assertEqual(m['analysis']['observed_separator'],case['separator'])
    if 'rule_expected' in case:self.assertEqual(m['analysis']['expected_if_hypothesis'],case['rule_expected'])
    if 'exception' in case:self.assertEqual(m['analysis']['exception_or_lexicalized'],case['exception'])
 def test_inventory_contains_every_source_block(self):
  with tempfile.TemporaryDirectory() as d:
   result=engine.write_book_inventory(Path(d));self.assertEqual(result['blocks'],len(self.blocks))
   with (Path(d)/'book-inventory.jsonl').open(encoding='utf8') as f:records=[json.loads(line) for line in f]
   self.assertEqual({r['id'] for r in records},set(self.blocks))
   for r in records:self.assertEqual(r['pages'],self.blocks[r['id']].get('pages',[]))
 def test_table_parsing_rejects_qualified_or_partial_forms(self):
  for word in ('اتو / اطو (وسیله)','اتو / اطو / چیز','باتوم / باطوم'):
   with self.subTest(word=word):
    r=engine.evaluate(0,word,self.blocks,self.rules)
    self.assertFalse(any(m['rule_id'].startswith('multiple_table-') for m in r['matches']))
 def test_sample_references_and_unique_rows(self):
  sample=json.loads((P/'outputs'/'sample-50.json').read_text())
  self.assertEqual(len(sample['samples']),50);self.assertEqual(len({x['result']['id'] for x in sample['samples']}),50)
  for x in sample['samples']:
   r=x['result'];self.assertEqual(self.entries[r['index']],r['entry'])
   for m in r['matches']:
    self.assertEqual(m['rule_text'],self.blocks[m['block_id']]['text'])
    if 'table_match' in m:
     row=self.blocks[m['example_id']]['rows'][m['table_match']['row']-1]
     self.assertEqual(set(m['table_match']['forms']),{x.strip() for x in row[0].split('/')})
     self.assertEqual(set(m['table_match']['preferred']),{x.strip() for x in row[1].split('/')})
    else:self.assertEqual(m['evidence'],self.blocks[m['example_id']]['text'] if m['status']=='محتمل' else m['rule_text'])
    if m['status']=='محتمل' and m['rule_id']!='hamza_table' and 'table_match' not in m:self.assertIn(r['entry'],[s.strip().strip('.') for s in m['evidence'].split('،')])
    if m['rule_id']=='hamza_table':self.assertTrue(any(r['entry']==c.strip() for row in self.blocks['table-275']['rows'] for c in row[2:]))
if __name__=='__main__':unittest.main()
