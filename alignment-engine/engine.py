#!/usr/bin/env python3
"""Reproducible, conservative dictionary alignment; Python standard library only."""
import argparse,collections,csv,hashlib,json,re,sqlite3,sys
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
 return entries,blocks,rules

def entry_id(i,s):return f"{i+1}:{hashlib.sha256(s.encode()).hexdigest()}"
def evaluate(i,entry,blocks,rules):
 matches=[]
 for r in rules:
  st=r['strategy'];hit=(st=='exact_examples' and entry in r['_examples']) or (st=='repeated_zwnj' and '\u200c\u200c' in entry) or (st=='multiple_forms' and bool(re.search(r'\s/\s',entry)))
  if not hit:continue
  rule=blocks[r['rule']];example=blocks[r['examples']]
  exact=st=='exact_examples'
  matches.append({'rule_id':r['id'],'category':r['category'],'block_id':rule['id'],'pages':rule.get('pages',[]),'rule_text':rule['text'],'example_id':example['id'],'example_pages':example.get('pages',[]),'evidence':example['text'] if exact else rule['text'],'reason':'صورت دقیق مدخل در فهرست مثال‌های بند شاهد آمده است؛ انطباق معنا نیازمند بررسی است.' if exact else 'نشانۀ ظاهری شناسایی شد؛ ساخت، معنا و استثناها بررسی نشده‌اند.','status':'محتمل' if exact else 'نامشخص','confidence':'متوسط' if exact else 'پایین','conditions':r['conditions'],'exceptions':r['exceptions']})
 return {'id':entry_id(i,entry),'index':i,'entry':entry,'status':'محتمل' if any(x['status']=='محتمل' for x in matches) else 'نامشخص','confidence':'متوسط' if any(x['status']=='محتمل' for x in matches) else 'پایین','matches':matches}

def run(dbpath,batch=500,limit=None):
 entries,blocks,rules=load();db=sqlite3.connect(dbpath)
 db.execute('CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY,value TEXT NOT NULL)');db.execute('CREATE TABLE IF NOT EXISTS results (idx INTEGER PRIMARY KEY,id TEXT UNIQUE NOT NULL, payload TEXT NOT NULL)')
 hashes={p:digest(ROOT/p) for p in ('dictionary.json','book.json')};hashes['rules.json']=digest(HERE/'rules.json');old=dict(db.execute('SELECT key,value FROM meta'))
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
 entries,blocks,rules=load();db=sqlite3.connect(dbpath);rows=[json.loads(x[0]) for x in db.execute('SELECT payload FROM results ORDER BY idx')];db.close()
 if len({x['id'] for x in rows})!=len(rows) or any(entries[x['index']]!=x['entry'] for x in rows):raise ValueError('checkpoint mismatch')
 counts=collections.Counter(x['status'] for x in rows);stats={'total':len(entries),'processed':len(rows),'unprocessed':len(entries)-len(rows),'consistent':counts['سازگار'],'inconsistent':counts['ناسازگار مستند'],'possible':counts['محتمل'],'unknown':counts['نامشخص'],'no_rule':sum(not x['matches'] for x in rows),'errors':0,'source_sha256':{p:digest(ROOT/p) for p in ('dictionary.json','book.json')},'rules_sha256':digest(HERE/'rules.json')}
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

def main():
 p=argparse.ArgumentParser();p.add_argument('--db',type=Path,default=HERE/'alignment.sqlite3');p.add_argument('--out',type=Path,default=HERE/'outputs');p.add_argument('--batch',type=int,default=500);p.add_argument('--limit',type=int);a=p.parse_args()
 if a.batch<1 or (a.limit is not None and a.limit<0):p.error('invalid batch or limit')
 n=run(a.db,a.batch,a.limit);stats=report(a.db,a.out);cnt,deficits=sample(a.db,a.out);print(json.dumps({'new':n,'summary':stats,'sample':cnt,'deficits':deficits},ensure_ascii=False))
if __name__=='__main__':main()
