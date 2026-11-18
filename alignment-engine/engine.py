#!/usr/bin/env python3
"""Reproducible, conservative dictionary alignment; Python standard library only."""
import argparse,collections,csv,hashlib,json,re,sqlite3,sys,unicodedata
from pathlib import Path
HERE=Path(__file__).resolve().parent; ROOT=HERE.parent

def read_json(p): return json.loads(p.read_text(encoding='utf-8'))
def digest(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def load():
 d=read_json(ROOT/'dictionary.json');b=read_json(ROOT/'book.json');rules=read_json(HERE/'rules.json')
 entries=d['entries'];blocks={x['id']:x for x in b['blocks']}
 if len(entries)!=d['count'] or len(blocks)!=len(b['blocks']):raise ValueError('Invalid source count or duplicate block ID')
 for r in rules:
  for field in ('rule','examples'):
   if r[field] not in blocks:raise ValueError(f"Missing {r[field]}")
  r['_examples']=set(part.strip().strip('.') for part in blocks[r['examples']]['text'].split('،'))
  if r['strategy']=='hamza_table':r['_table_forms']={cell.strip() for row in blocks[r['examples']]['rows'] for cell in row[2:] if re.fullmatch(r'[\u0600-\u06ff\u200c]+',cell.strip())}
  if r['strategy']=='exact_table_variants':
   table=collections.defaultdict(list)
   for number,row in enumerate(blocks[r['examples']]['rows'],1):
    if len(row)<2 or any(c in ''.join(row[:2]) for c in '()（）؛;،') or not row[1].strip():continue
    forms=tuple(x.strip() for x in row[0].split('/'));preferred=tuple(x.strip() for x in row[1].split('/'))
    if len(forms)<2 or len(set(forms))!=len(forms) or not all(plain_word(x) for x in forms+preferred):continue
    table[frozenset(forms)].append({'row':number,'forms':forms,'preferred':preferred})
   r['_table_variants']=table
 return entries,blocks,rules

def entry_id(i,s):return f"{i+1}:{hashlib.sha256(s.encode()).hexdigest()}"
def plain_word(entry):
 # Mixed alternatives and explanations require a separate parse; never discard them.
 return bool(re.fullmatch(r'[\u0621-\u063a\u0641-\u064a\u066e-\u06d3\u064b-\u065f\u0670\u200c]+',entry))

def visible(s):
 return ''.join(c for c in s if unicodedata.category(c) not in ('Mn','Me'))

def morphology(entry,strategy):
 if not plain_word(entry):return None
 w=visible(entry)
 if strategy=='morph_comparative':
  # A visible boundary is required: e.g. دفتر is not evidence of a suffix.
  m=re.fullmatch(r'(.+)\u200c(ترین|تر)',w)
  if not m:return None
  return {'components':[m.group(1),m.group(2)],'observed_separator':'نیم‌فاصله','expected_if_hypothesis':'نیم‌فاصله','hypothesis':'اگر جزء پایانی پسوند تفضیلی یا عالی باشد؛ ساخت و معنا باید بررسی شوند.'}
 prefixes={'morph_bi':'بی','morph_ham':'هم','morph_nim':'نیم'}
 if strategy in prefixes:
  prefix=prefixes[strategy]
  if not w.startswith(prefix) or len(w)<=len(prefix)+1:return None
  tail=w[len(prefix):];sep='نیم‌فاصله' if tail.startswith('\u200c') else 'پیوسته';base=tail.lstrip('\u200c')
  if not base or '\u200c' in base:return None
  return {'components':[prefix,base],'observed_separator':sep,'hypothesis':'ممکن است جزء نخست پیشوند باشد؛ نقش واژه باید بررسی شود.','expected_if_hypothesis':'نیم‌فاصله'}
 suffixes={'morph_suffix':['آسا','آگین','باره','جات','فام','وش'],'morph_vare':['واره'],'morph_compound':['خانه','نامه'],'morph_var':['وار'],'morph_san':['سان']}
 if strategy not in suffixes:return None
 for suffix in suffixes[strategy]:
  if not w.endswith(suffix):continue
  raw=w[:-len(suffix)];base=raw.rstrip('\u200c')
  if len(base)<2 or '\u200c' in base:continue
  expected='پیوسته' if strategy=='morph_vare' else ('بی‌فاصله' if base[-1] in 'اآدذرزژوؤء' else 'نیم‌فاصله')
  return {'components':[base,suffix],'observed_separator':'نیم‌فاصله' if raw.endswith('\u200c') else 'پیوسته','expected_if_hypothesis':expected,'hypothesis':'ممکن است جزء پایانی پسوند یا پسوندواره باشد؛ ساخت و استثناها باید بررسی شوند.'}
 return None

def evaluate(i,entry,blocks,rules,analyze_variants=True):
 matches=[]
 for r in rules:
  st=r['strategy'];analysis=morphology(entry,st);table_match=None
  if st=='exact_table_variants':
   forms=tuple(x.strip() for x in entry.split('/'))
   if len(forms)>1 and len(set(forms))==len(forms) and all(plain_word(x) for x in forms):
    found=r['_table_variants'].get(frozenset(forms),[])
    if len(found)==1:table_match=found[0]
  hit=(st=='exact_examples' and entry in r['_examples']) or (st=='repeated_zwnj' and '\u200c\u200c' in entry) or (st=='multiple_forms' and bool(re.search(r'\s/\s',entry))) or (st=='hamza_table' and entry in r['_table_forms']) or table_match is not None or analysis is not None
  if not hit:continue
  rule=blocks[r['rule']];example=blocks[r['examples']]
  exact=st in ('exact_examples','hamza_table','exact_table_variants')
  match={'rule_id':r['id'],'category':r['category'],'block_id':rule['id'],'pages':rule.get('pages',[]),'rule_text':rule['text'],'example_id':example['id'],'example_pages':example.get('pages',[]),'evidence':example['text'] if exact else rule['text'],'reason':'صورت دقیق مدخل در فهرست شاهد آمده است؛ انطباق معنا نیازمند بررسی است.' if exact else 'تحلیل ساختی یا نشانۀ ظاهری به‌تنهایی نقش و معنا را اثبات نمی‌کند.','status':'محتمل' if exact else 'نامشخص','confidence':'متوسط' if exact else 'پایین','conditions':r['conditions'],'exceptions':r['exceptions']}
  if table_match:
   match['table_match']=table_match
   match['evidence']=' / '.join(table_match['forms'])+' ← '+' / '.join(table_match['preferred'])
   match['reason']='صورت‌های مدخل با یک ردیف جدول یکسان‌اند؛ پیوند معنایی و قیدهای پیرامون جدول نیازمند بازبینی است.'
  if analysis:
   match['analysis']=analysis
   exception_ids={'morph_bi':['item-193','item-196'],'morph_ham':['item-204'],'morph_compound':['item-339'],'morph_vare':['item-332'],'morph_nim':[],'morph_suffix':[],'morph_var':['item-330'],'morph_san':['item-334'],'morph_comparative':['item-206']}[st]
   match['exception_evidence']=[{'id':key,'text':blocks[key]['text'],'pages':blocks[key].get('pages',[])} for key in exception_ids]
   if st in ('morph_bi','morph_ham','morph_compound','morph_var','morph_san'):
    exception_forms=set()
    for key in exception_ids:
     text=blocks[key]['text'].removeprefix('استثنا:').strip()
     for part in re.split(r'،|/',text):
      part=part.strip().split(' (')[0].strip().strip('.')
      if re.fullmatch(r'[\u0600-\u06ff\u200c]+',part):exception_forms.add(part)
    if st=='morph_bi':exception_forms.update(['بیهوده','بیگانه'])
    match['analysis']['exception_or_lexicalized'] = entry in exception_forms
    if match['analysis']['exception_or_lexicalized']:
     match['analysis'].pop('expected_if_hypothesis',None)
     match['analysis']['hypothesis'] += ' این صورت در شاهد استثنا یا واژگانی‌شدن نیز آمده است.'
  matches.append(match)
 result={'id':entry_id(i,entry),'index':i,'entry':entry,'status':'محتمل' if any(x['status']=='محتمل' for x in matches) else 'نامشخص','confidence':'متوسط' if any(x['status']=='محتمل' for x in matches) else 'پایین','matches':matches}
 # Parse only unambiguous spaced separators, without assuming equal meanings.
 if analyze_variants and re.search(r'\s(?:/|-)\s',entry):
  parts=[p.strip() for p in re.split(r'\s+(?:/|-)\s+',entry)]
  if 1<len(parts)<=5 and all(plain_word(p) for p in parts):
   result['variants']=[{'form':part,'status':child['status'],'matches':child['matches']} for part in parts for child in [evaluate(i,part,blocks,rules,False)]]
   result['variants_note']='صورت‌ها جداگانه بررسی شده‌اند؛ یکسانی معنا یا رابطۀ صورت‌ها فرض نشده است.'
 return result

def run(dbpath,batch=500,limit=None):
 entries,blocks,rules=load();db=sqlite3.connect(dbpath)
 db.execute('CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY,value TEXT NOT NULL)');db.execute('CREATE TABLE IF NOT EXISTS results (idx INTEGER PRIMARY KEY,id TEXT UNIQUE NOT NULL, payload TEXT NOT NULL)')
 hashes={p:digest(ROOT/p) for p in ('dictionary.json','book.json')};hashes['rules.json']=digest(HERE/'rules.json');hashes['engine.py']=digest(HERE/'engine.py');old=dict(db.execute('SELECT key,value FROM meta'))
 if old and old!=hashes:raise ValueError('Source or rules changed; create a new checkpoint database')
 if not old:db.executemany('INSERT INTO meta VALUES (?,?)',hashes.items());db.commit()
 end=min(len(entries),limit if limit is not None else len(entries));count=0
 for start in range(0,end,batch):
  with db:
   for i in range(start,min(start+batch,end)):
    if db.execute('SELECT 1 FROM results WHERE idx=?',(i,)).fetchone():continue
    value=evaluate(i,entries[i],blocks,rules)
    db.execute('INSERT INTO results VALUES (?,?,?)',(i,value['id'],json.dumps(value,ensure_ascii=False)));count+=1
 db.close();return count

def report(dbpath,out):
 entries,blocks,rules=load();db=sqlite3.connect(dbpath);meta=dict(db.execute('SELECT key,value FROM meta'));expected={p:digest(ROOT/p) for p in ('dictionary.json','book.json')};expected.update({'rules.json':digest(HERE/'rules.json'),'engine.py':digest(HERE/'engine.py')});
 if meta!=expected:raise ValueError('Source, rules or engine changed; create a new checkpoint database')
 rows=[json.loads(x[0]) for x in db.execute('SELECT payload FROM results ORDER BY idx')];db.close()
 if len({x['id'] for x in rows})!=len(rows) or any(entries[x['index']]!=x['entry'] for x in rows):raise ValueError('checkpoint mismatch')
 counts=collections.Counter(x['status'] for x in rows);stats={'total':len(entries),'processed':len(rows),'unprocessed':len(entries)-len(rows),'consistent':counts['سازگار'],'inconsistent':counts['ناسازگار مستند'],'possible':counts['محتمل'],'unknown':counts['نامشخص'],'no_rule':sum(not x['matches'] and not any(v['matches'] for v in x.get('variants',[])) for x in rows),'entries_with_variant_analysis':sum('variants' in x for x in rows),'entries_with_variant_matches':sum(any(v['matches'] for v in x.get('variants',[])) for x in rows),'errors':0,'source_sha256':{p:digest(ROOT/p) for p in ('dictionary.json','book.json')},'rules_sha256':digest(HERE/'rules.json'),'engine_sha256':digest(HERE/'engine.py')}
 out.mkdir(parents=True,exist_ok=True)
 with (out/'results.jsonl').open('w',encoding='utf8') as f:
  for x in rows:f.write(json.dumps(x,ensure_ascii=False)+'\n')
 (out/'summary.json').write_text(json.dumps(stats,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
 return stats

def sample(dbpath,out):
 db=sqlite3.connect(dbpath);rows=[json.loads(x[0]) for x in db.execute('SELECT payload FROM results ORDER BY idx')];db.close()
 groups=['فاصله و نیم‌فاصله','پیشوندها و پسوندها','واژه‌های مرکب و ترکیب‌ها','همزه و دیگر قواعد املایی','استثناها، چنداملایی و موارد دشوار'];used=set();chosen=[];deficits={}
 for group in groups:
  pool=[(x,m) for x in rows if x['id'] not in used for m in x['matches'] if m['category']==group]; pool.sort(key=lambda pair:pair[0]['index']);pick=[]
  for k in range(10):
   if not pool:break
   pos=round(k*(len(pool)-1)/9) if len(pool)>1 else 0; candidate=pool[pos]
   if candidate[0]['id'] not in {v[0]['id'] for v in pick}:pick.append(candidate)
  for x,m in pick:used.add(x['id']);chosen.append({'group':group,'result':x,'selected_rule':m['rule_id']})
  if len(pick)<10:deficits[group]=10-len(pick)
 out.mkdir(parents=True,exist_ok=True);(out/'sample-50.json').write_text(json.dumps({'method':'نمونۀ طبقه‌بندی‌شده از خروجی موتور؛ آزمون مستقل دقت نیست','deficits':deficits,'samples':chosen},ensure_ascii=False,indent=2)+'\n',encoding='utf8')
 with (out/'sample-50.md').open('w',encoding='utf8') as f:
  f.write('# نمونهٔ تطبیق\n\nاین نمونه از یافته‌های خود موتور انتخاب شده و ارزیابی مستقل دقت نیست. یکسانی ظاهری یا شاهد مثال برای اثبات معنای مدخل کافی نیست.\n\n')
  for item in chosen:
   x=item['result'];m=next(m for m in x['matches'] if m['rule_id']==item['selected_rule']);f.write(f"- **{x['entry']}** — {item['group']}؛ {m['status']}؛ بند [{m['block_id']}](https://afshin-ir.github.io/emla/#{m['block_id']})، صفحۀ {m['pages']}؛ {m['reason']}\n")
  if deficits:f.write('\nکمبود گروه‌ها: '+str(deficits)+'\n')
 return len(chosen),deficits

def write_book_inventory(out):
 # Catalog every source block; references do not imply full implementation.
 _,blocks,rules=load();rows=[]
 for block in blocks.values():
  as_rule=[r['id'] for r in rules if r['rule']==block['id']]
  as_example=[r['id'] for r in rules if r['examples']==block['id']]
  rows.append({'id':block['id'],'kind':block['kind'],'section':block.get('section',''),'pages':block.get('pages',[]),'referenced_as_rule':as_rule,'referenced_as_example':as_example,'coverage':'ارجاع محدود در موتور' if as_rule else ('فقط شاهد' if as_example else 'بدون پیاده‌سازی مستقیم')})
 out.mkdir(parents=True,exist_ok=True)
 with (out/'book-inventory.jsonl').open('w',encoding='utf8') as f:
  for x in rows:f.write(json.dumps(x,ensure_ascii=False)+'\n')
 return {'blocks':len(rows),'referenced_as_rule':sum(bool(x['referenced_as_rule']) for x in rows),'no_direct_implementation':sum(not x['referenced_as_rule'] for x in rows)}

def main():
 p=argparse.ArgumentParser();p.add_argument('--db',type=Path,default=HERE/'alignment.sqlite3');p.add_argument('--out',type=Path,default=HERE/'outputs');p.add_argument('--batch',type=int,default=500);p.add_argument('--limit',type=int);a=p.parse_args()
 if a.batch<1 or (a.limit is not None and a.limit<0):p.error('invalid batch or limit')
 n=run(a.db,a.batch,a.limit);stats=report(a.db,a.out);cnt,deficits=sample(a.db,a.out);inventory=write_book_inventory(a.out);print(json.dumps({'new':n,'summary':stats,'sample':cnt,'deficits':deficits,'book_inventory':inventory},ensure_ascii=False))
if __name__=='__main__':main()
