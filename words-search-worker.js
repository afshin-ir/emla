'use strict';
const normalize=s=>String(s??'').normalize('NFKC').replace(/[يى]/g,'ی').replace(/ك/g,'ک').replace(/[ۀة]/g,'ه').replace(/[\u064b-\u065f\u0670ـ]/g,'').toLowerCase().replace(/[^\p{L}\p{N}_]/gu,'');
let entries=[];
function addIndex(index,key,entry){const bucket=index.get(key);if(bucket)bucket.push(entry);else index.set(key,[entry])}
function sameContext(a,b){if(a._subjectKey!==b._subjectKey)return false;const av=a[4]||[],bv=b[4]||[];return!av.length||!bv.length||av.some(n=>bv.includes(n))}
function splitArrow(value){const m=String(value||'').match(/\s*(?:→|->)\s*/);if(!m)return null;const i=m.index;return[String(value).slice(0,i).trim(),String(value).slice(i+m[0].length).trim()]}
function englishTerms(value){return String(value||'').split(/[,،;؛]/).map(normalize).filter(Boolean)}
function findUnique(index,source,term){const bucket=index.get(source._subjectKey+'\u0000'+term)||[];let hit=null;for(const e of bucket){if(e===source||!sameContext(source,e))continue;if(hit&&hit!==e)return null;hit=e}return hit}
function linkCrossReferences(list){
 const englishIndex=new Map(),persianIndex=new Map();
 for(const e of list){e[10]=[];e[11]=[];e[12]=[];e._subjectKey=normalize(e[2]);if(!e[0])continue;
  const pk=normalize(e[0]);if(pk)addIndex(persianIndex,e._subjectKey+'\u0000'+pk,e);
  for(const term of new Set(englishTerms(e[1])))addIndex(englishIndex,e._subjectKey+'\u0000'+term,e)
 }
 for(const source of list){
  if(!source[0]&&source[1]&&splitArrow(source[1])){
   const [from,to]=splitArrow(source[1]),target=findUnique(englishIndex,source,normalize(to));if(!target)continue;
   target[10].push({text:source[1],dir:'ltr'});target[11].push(normalize(from),normalize(to));target[12].push(normalize(from));target[7]+=' '+normalize(source[1]);source._linkedCrossReference=true
  }else if(source[0]&&/^\s*←/.test(String(source[3]||''))){
   const to=String(source[3]).replace(/^\s*←\s*/,'').trim(),target=findUnique(persianIndex,source,normalize(to));if(!target)continue;
   const display=source[0]+(source[1]?' ('+source[1]+')':'');
   target[10].push({text:display,dir:'auto'});target[11].push(normalize(source[0]),normalize(source[1]),normalize(to));if(source[1])target[12].push(normalize(source[1]));target[7]+=' '+normalize([source[0],source[1],source[3]].filter(Boolean).join(' '));source._linkedCrossReference=true
  }
 }
 for(const e of list){e[10]=e[10].filter((r,i,a)=>a.findIndex(x=>x.text===r.text)===i);e[11]=[...new Set(e[11].filter(Boolean))];e[12]=[...new Set(e[12].filter(Boolean))]}
}
function resultScore(e,phrase,tokens){
 const main=[e[8],e[9]].filter(Boolean),fields=[e[8],e[9],e[13]].filter(Boolean);
 if(e[8]===phrase)return 0;
 if(e[9]===phrase)return 1;
 if(main.some(x=>x.startsWith(phrase)))return 2;
 if(fields.some(x=>x.includes(phrase)))return 3;
 if(e[12]?.some(x=>x===phrase))return 4;
 if(e[11]?.some(x=>x===phrase))return 5;
 if(tokens.length&&fields.some(field=>tokens.every(token=>field.includes(token))))return 6;
 if(tokens.length>1&&tokens.every(token=>e[7]?.includes(token)))return 7;
 if(tokens.length===1&&e[7]?.includes(tokens[0]))return 8;
 if(e[11]?.some(x=>x.startsWith(phrase)))return 9;
 return 99
}
function matchType(e,phrase,tokens){
 if(!phrase)return'all';
 if(e[8]?.includes(phrase)||e[9]?.includes(phrase))return'direct';
 if(e[11]?.some(x=>x.includes(phrase)))return'reference';
 if(e[13]?.includes(phrase)||tokens.every(t=>e[13]?.includes(t)))return'details';
 return'details'
}
function selected(e,v,s){return(!v||(e[4]||[]).includes(v))&&(!s||e[2]===s)}
function distanceWithin(a,b,limit){
 if(Math.abs(a.length-b.length)>limit)return limit+1;
 let prev=Array.from({length:b.length+1},(_,i)=>i);
 for(let i=1;i<=a.length;i++){
  const row=[i];let min=i;
  for(let j=1;j<=b.length;j++){const value=Math.min(row[j-1]+1,prev[j]+1,prev[j-1]+(a[i-1]===b[j-1]?0:1));row.push(value);if(value<min)min=value}
  if(min>limit)return limit+1;prev=row
 }
 return prev[b.length]
}
function findSuggestions(query,v,subject){
 const target=normalize(query);if(target.length<4)return[];
 const limit=target.length>=8?2:1,candidates=[];
 for(const e of entries){if(e._linkedCrossReference||!selected(e,v,subject))continue;
  const terms=[e[0],...String(e[1]||'').split(/[,،;؛]/)].filter(Boolean);
  let best=null;
  for(const term of terms){const value=normalize(term);if(!value||Math.abs(value.length-target.length)>limit)continue;
   const distance=distanceWithin(target,value,limit);if(distance===0||distance>limit)continue;
   if(!best||distance<best.distance)best={entry:e,term,distance}
  }
  if(best)candidates.push(best)
 }
 candidates.sort((a,b)=>a.distance-b.distance||Math.abs(normalize(a.term).length-target.length)-Math.abs(normalize(b.term).length-target.length));
 return candidates.slice(0,3)
}
async function load(baseUrl){
 if(!('DecompressionStream'in self))throw new Error('مرورگر از بازکردن فایل فشرده پشتیبانی نمی‌کند.');
 const shards=await Promise.all(Array.from({length:4},async(_,index)=>{
  const i=index+1,response=await fetch(new URL('approved-words-'+i+'.json.gz',baseUrl));if(!response.ok)throw new Error('فهرست واژگان دریافت نشد.');
  const stream=new Blob([await response.arrayBuffer()]).stream().pipeThrough(new DecompressionStream('gzip'));
  const data=JSON.parse(await new Response(stream).text());postMessage({type:'progress',loaded:i,total:4});return data
 }));
 entries=shards.flat();const volumeCounts=new Map(),subjects=new Set();
 for(const e of entries){e[7]=normalize([e[0],e[1],e[2],e[3],e[5],e[6]].filter(Boolean).join(' '));e[8]=normalize(e[0]);e[9]=normalize(e[1]);e[13]=normalize([e[2],e[3],e[5],e[6]].filter(Boolean).join(' '));if(e[2])subjects.add(e[2]);for(const n of e[4]||[])volumeCounts.set(n,(volumeCounts.get(n)||0)+1)}
 linkCrossReferences(entries);
 const volumes=[...volumeCounts.keys()].sort((a,b)=>a-b),subjectList=[...subjects].sort((a,b)=>a.localeCompare(b,'fa'));
 postMessage({type:'ready',count:entries.length,volumes,volumeCounts:[...volumeCounts],subjects:subjectList})
}
function search(message){
 const {id,query='',volume=0,subject='',page=0,pageSize=30}=message,raw=query.trim(),tokens=raw.split(/\s+/).map(normalize).filter(Boolean),phrase=tokens.join(''),v=Number(volume)||0;
 if(!phrase&&!v&&!subject){postMessage({type:'results',id,total:0,page,rows:[],groups:{},suggestions:[]});return}
 const found=[];
 for(const e of entries){if(e._linkedCrossReference||!selected(e,v,subject))continue;
  if(!phrase){found.push([e,8,'all']);continue}
  const score=resultScore(e,phrase,tokens);if(score<99)found.push([e,score,matchType(e,phrase,tokens)])
 }
 found.sort((a,b)=>a[1]-b[1]);
 const suggestions=phrase&&!found.length?findSuggestions(raw,v,subject):[];
 const groups={direct:0,reference:0,details:0,all:0};
 for(const [, ,type] of found)groups[type]=(groups[type]||0)+1;
 const start=page*pageSize,rows=found.slice(start,start+pageSize).map(([entry,,matchType])=>({entry,matchType}));
 postMessage({type:'results',id,total:found.length,page,rows,groups,suggestions})
}
onmessage=event=>{const m=event.data||{};if(m.type==='init')load(m.baseUrl).catch(error=>postMessage({type:'error',message:error.message}));else if(m.type==='search')search(m)};