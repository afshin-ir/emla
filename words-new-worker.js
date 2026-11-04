const PARTS=Array.from({length:231},(_,i)=>new URL("./words-new-data/part-"+String(i).padStart(3,"0")+".txt",self.location.href).href);
"use strict";
const normalize=s=>String(s??"").normalize("NFKC").replace(/[يى]/g,"ی").replace(/ك/g,"ک").replace(/[ۀة]/g,"ه").replace(/[\u064b-\u065f\u0670ـ]/g,"").toLowerCase().replace(/[^\p{L}\p{N}_]/gu,"");
const splitTerms=s=>String(s??"").split(/[,،;؛]/).map(x=>x.trim()).filter(Boolean);
const splitEnglishTerms=s=>{const raw=String(s??"").trim();return /,\s*(?:and|or)\s+/i.test(raw)?[raw]:splitTerms(raw);};
const wordTokens=s=>String(s??"").match(/[\p{L}\p{M}\p{N}_]+(?:\u200c[\p{L}\p{M}\p{N}_]+)*/gu)||[];
let entries=[];
let indexedBases=new Set();
function collectIndexedBases(rows){
 const suffixes=new Map(),plain=new Set();
 for(const row of rows){for(const field of [row[0],row[3]])for(const term of splitTerms(field)){const key=normalize(term),m=String(term).trim().match(/^(.*?)([0-9۰-۹٠-٩]+)$/u);if(m&&m[1].trim()){const base=normalize(m[1]);if(!suffixes.has(base))suffixes.set(base,new Set());suffixes.get(base).add(m[2]);}else if(key)plain.add(key);}}
 indexedBases=new Set([...suffixes.entries()].filter(([base,nums])=>nums.size>1||plain.has(base)).map(([base])=>base));
}
const stripIndex=s=>{const raw=String(s??"").trim(),m=raw.match(/^(.*?)(\s*[0-9۰-۹٠-٩]+)$/u);if(!m)return raw;const base=m[1].trim();return /\s[0-9۰-۹٠-٩]+$/u.test(raw)||indexedBases.has(normalize(base))?base:raw;};
const hasIndexSuffix=term=>{const m=String(term??"").trim().match(/^(.*?)(\s*[0-9۰-۹٠-٩]+)$/u);return Boolean(m&&m[1].trim()&&indexedBases.has(normalize(m[1].trim()))&&!/\s[0-9۰-۹٠-٩]+$/u.test(term));};
function visiblePersianTerms(raw){const terms=splitTerms(raw),hasPlain=terms.some(term=>!hasIndexSuffix(term)),chosen=hasPlain?terms.filter(term=>!hasIndexSuffix(term)):terms,out=[],seen=new Set();for(const term of chosen){const clean=stripIndex(term),key=normalize(clean);if(key&&!seen.has(key)){seen.add(key);out.push(clean);}}return out;}
function makeEntry(raw){
 const e=raw;
 const rawWordTerms=splitTerms(e[0]);e._displayWordTerms=visiblePersianTerms(e[0]);e._hiddenWordTerms=rawWordTerms.filter(term=>!e._displayWordTerms.some(visible=>normalize(stripIndex(visible))===normalize(stripIndex(term)))).map(term=>normalize(stripIndex(term)));
 e._displayable=Boolean(String(e[0]||"").trim()&&String(e[3]||"").trim());e._refTerms=[];
 e._rawWordTerms=splitTerms(e[0]).map(normalize);e._rawEnglishTerms=splitEnglishTerms(e[3]).map(normalize);
 e._wordTerms=splitTerms(e[0]).map(x=>normalize(stripIndex(x)));
 e._englishTerms=splitEnglishTerms(e[3]).map(x=>normalize(stripIndex(x)));
 e._subject=normalize(e[2]);e._ref=normalize(e[1]);e._definition=normalize(e[4]);e._notes=normalize(e[8]);
 e._compact=normalize(e[5]+" "+e[6]);e._all=normalize([e[0],e[1],e[2],e[3],e[4],e[5],e[6],e[8]].join(" "));
 return e;
}
function linkReferenceRows(list){
 const englishIndex=new Map(),persianIndex=new Map();
 const add=(map,key,e)=>{if(!key)return;if(!map.has(key))map.set(key,[]);map.get(key).push(e);};
 for(const e of list){if(!e._displayable)continue;for(const term of e._englishTerms)add(englishIndex,term,e);for(const term of e._wordTerms)add(persianIndex,term,e);}
 const choose=(index,key,source)=>{const bucket=index.get(key)||[],available=bucket.filter(target=>target!==source);if(!available.length)return null;const sameBook=available.filter(target=>!(source[7]||[]).length||!(target[7]||[]).length||(source[7]||[]).some(n=>(target[7]||[]).includes(n)));const pool=sameBook.length?sameBook:available;return pool.length===1?pool[0]:null;};
 for(const source of list){if(source._displayable)continue;
  const rawEnglish=String(source[5]||""),arrow=rawEnglish.match(/\s*(?:→|->)\s*/);
  if(arrow){const at=arrow.index,left=rawEnglish.slice(0,at).replace(/^\*/g,"").trim(),right=rawEnglish.slice(at+arrow[0].length).trim(),target=choose(englishIndex,normalize(right),source);if(target&&left)target._refTerms.push(normalize(left));continue;}
  const rawTarget=String(source[1]||"").trim(),persianArrow=rawTarget.match(/^←\s*(.+)$/);
  if(persianArrow&&(source[0]||source[6])){const target=choose(persianIndex,normalize(stripIndex(persianArrow[1])),source);if(target)for(const alias of splitTerms(source[0]||source[6]))target._refTerms.push(normalize(alias));}
 }
 for(const e of list){e._refTerms=[...new Set(e._refTerms.filter(Boolean))];e._refBaseTerms=[...new Set(e._refTerms.map(x=>normalize(stripIndex(x))).filter(Boolean))];}
}
async function load(){
 try{
  if(!("DecompressionStream" in self))throw new Error("مرورگر این فایل فشرده را باز نمی‌کند.");
  postMessage({type:"progress",message:"در حال آماده‌سازی فهرست واژگان…"});
  const encoded=(await Promise.all(PARTS.map(async path=>{const response=await fetch(path);if(!response.ok)throw new Error("بارگیری بخشی از داده‌ها ناموفق بود.");return response.text()}))).join("");
  const binary=Uint8Array.from(atob(encoded),c=>c.charCodeAt(0));
  const stream=new Blob([binary]).stream().pipeThrough(new DecompressionStream("gzip"));
  const raw=JSON.parse(await new Response(stream).text());collectIndexedBases(raw);entries=raw.map(makeEntry);linkReferenceRows(entries);
  const volumeCounts=new Map(),subjects=new Set();let displayableCount=0;
  for(const e of entries){if(!e._displayable)continue;displayableCount++;for(const n of e[7]||[])volumeCounts.set(n,(volumeCounts.get(n)||0)+1);if(e[2])subjects.add(e[2]);}
  postMessage({type:"ready",count:displayableCount,volumes:[...volumeCounts.keys()].sort((a,b)=>a-b),volumeCounts:[...volumeCounts],subjects:[...subjects].sort((a,b)=>a.localeCompare(b,"fa")),indexedBases:[...indexedBases]});
 }catch(error){postMessage({type:"error",message:error.message||"بارگذاری فهرست ناموفق بود."})}
}
function exactTerm(e,q){return e._wordTerms.includes(q)||e._englishTerms.includes(q)}
function selected(e,v,s){return(!v||(e[7]||[]).includes(v))&&(!s||normalize(e[2])===s)}
function matchEntry(e,q,tokens,exactOnly=false){
 if(!q)return exactOnly?null:{score:8,type:"all",exact:false};
 if(/[0-9۰-۹٠-٩]+$/u.test(q))return e._rawWordTerms.concat(e._rawEnglishTerms,e._refTerms).includes(q)?{score:0,type:"direct",exact:true}:null;
 const head=e._wordTerms.concat(e._englishTerms);
 if(exactOnly){const titleTerms=splitTerms(e[0]).concat(splitTerms(e[3])).map(term=>wordTokens(stripIndex(term)).map(normalize)),titleWords=titleTerms.flat(),headExact=head.includes(q);if(headExact)return {score:0,type:"direct",exact:true};if(titleTerms.some(words=>words[0]===q))return {score:1,type:"direct",exact:false};if(titleWords.includes(q))return {score:2,type:"direct",exact:false};if(e._refBaseTerms.includes(q))return {score:3,type:"direct",exact:false};return null;}
 if(head.includes(q))return {score:0,type:"direct",exact:true};
 if(head.some(x=>x.startsWith(q)))return {score:1,type:"direct",exact:false};
 if(head.some(x=>x.includes(q))||e._compact.includes(q))return {score:2,type:"direct",exact:false};
 if(e._refBaseTerms.includes(q))return {score:3,type:"direct",exact:true};
 if(e._refBaseTerms.some(x=>x.startsWith(q)))return {score:4,type:"direct",exact:false};
 return null;
}
function search(m){
 const raw=String(m.query||"").trim(),q=normalize(/[0-9۰-۹٠-٩]+$/u.test(raw)?raw:stripIndex(raw)),tokens=raw.split(/\s+/).map(normalize).filter(Boolean),volume=Number(m.volume)||0,subject=normalize(m.subject||""),exactOnly=Boolean(m.exactOnly);
 if(!q&&!volume&&!subject){postMessage({type:"results",id:m.id,total:0,page:m.page||0,rows:[],groups:{}});return;}
 const found=[];for(const e of entries){if(!e._displayable||!selected(e,volume,subject))continue;const match=matchEntry(e,q,tokens,exactOnly);if(match)found.push({entry:e,score:match.score,matchType:match.type,exact:match.exact});}
 found.sort((a,b)=>a.score-b.score||String(a.entry[0]||a.entry[3]).localeCompare(String(b.entry[0]||b.entry[3]),"fa"));
 const groups={direct:0,reference:0,details:0,all:0};for(const r of found)groups[r.matchType]=(groups[r.matchType]||0)+1;
 const page=Number(m.page)||0,pageSize=30,start=page*pageSize;
 postMessage({type:"results",id:m.id,total:found.length,page,pageSize,rows:found.slice(start,start+pageSize),groups});
}
onmessage=event=>{const m=event.data||{};if(m.type==="init")load();else if(m.type==="search")search(m)};
