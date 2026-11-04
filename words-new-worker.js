"use strict";
const DATA_VERSION="single-gzip-2";
const DATA_URL=new URL("./words-new-data.json.gz?v="+DATA_VERSION,self.location.href).href;
const INDEX_DB_NAME="emla-words-index";
const INDEX_DB_VERSION=1;
const INDEX_STORE="prepared-index";
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
function openIndexDatabase(){
 return new Promise((resolve,reject)=>{
  if(!("indexedDB" in self)){reject(new Error("IndexedDB is unavailable"));return;}
  const request=indexedDB.open(INDEX_DB_NAME,INDEX_DB_VERSION);
  request.onupgradeneeded=()=>{if(!request.result.objectStoreNames.contains(INDEX_STORE))request.result.createObjectStore(INDEX_STORE);};
  request.onsuccess=()=>resolve(request.result);
  request.onerror=()=>reject(request.error||new Error("Could not open the local index cache"));
 });
}
async function readCachedIndex(){
 const db=await openIndexDatabase();
 return new Promise((resolve,reject)=>{
  const tx=db.transaction(INDEX_STORE,"readonly"),request=tx.objectStore(INDEX_STORE).get(DATA_VERSION);
  request.onsuccess=()=>resolve(request.result||null);
  request.onerror=()=>reject(request.error||new Error("Could not read the local index cache"));
  tx.oncomplete=()=>db.close();
  tx.onerror=()=>{db.close();reject(tx.error||new Error("Could not read the local index cache"));};
  tx.onabort=()=>{db.close();reject(tx.error||new Error("Could not read the local index cache"));};
 });
}
async function saveCachedIndex(record){
 const db=await openIndexDatabase();
 return new Promise((resolve,reject)=>{
  const tx=db.transaction(INDEX_STORE,"readwrite"),store=tx.objectStore(INDEX_STORE);
  store.clear();
  store.put(record,DATA_VERSION);
  tx.oncomplete=()=>{db.close();resolve();};
  tx.onerror=()=>{db.close();reject(tx.error||new Error("Could not save the local index cache"));};
  tx.onabort=()=>{db.close();reject(tx.error||new Error("Could not save the local index cache"));};
 });
}
function subjectTerms(value){let raw=String(value??"").trim();if(raw.startsWith("[")&&raw.endsWith("]"))raw=raw.slice(1,-1);return raw.split(/[,،;؛]/u).map(term=>term.trim()).filter(term=>term&&!term.startsWith("←"));}
function readyMetadata(){
 const volumeCounts=new Map(),subjects=new Set();let displayableCount=0;
 for(const e of entries){if(!e._displayable)continue;displayableCount++;for(const n of e[7]||[])volumeCounts.set(n,(volumeCounts.get(n)||0)+1);for(const term of subjectTerms(e[2]))subjects.add(term);}
 return {type:"ready",count:displayableCount,volumes:[...volumeCounts.keys()].sort((a,b)=>a-b),volumeCounts:[...volumeCounts],subjects:[...subjects].sort((a,b)=>a.localeCompare(b,"fa")),indexedBases:[...indexedBases]};
}
async function load(){
 try{
  const cached=await readCachedIndex().catch(()=>null);
  if(cached&&cached.dataVersion===DATA_VERSION&&Array.isArray(cached.entries)){
   entries=cached.entries;indexedBases=new Set(cached.indexedBases||[]);
   postMessage({...readyMetadata(),cached:true});
   return;
  }
  postMessage({type:"progress",message:"در حال آماده‌سازی فهرست واژگان…"});
  if(!("DecompressionStream" in self))throw new Error("مرورگر این فایل فشرده را باز نمی‌کند.");
  const response=await fetch(DATA_URL);if(!response.ok)throw new Error("بارگیری فهرست واژگان ناموفق بود.");
  const stream=new Blob([await response.arrayBuffer()]).stream().pipeThrough(new DecompressionStream("gzip"));
  const raw=JSON.parse(await new Response(stream).text());collectIndexedBases(raw);entries=raw.map(makeEntry);linkReferenceRows(entries);
  const metadata=readyMetadata();
  const record={dataVersion:DATA_VERSION,entries,indexedBases:[...indexedBases],metadata};
  saveCachedIndex(record).catch(()=>{});
  postMessage(metadata);
 }catch(error){postMessage({type:"error",message:error.message||"بارگذاری فهرست ناموفق بود."})}
}
function exactTerm(e,q){return e._wordTerms.includes(q)||e._englishTerms.includes(q)}
function selected(e,v,s){return(!v||(e[7]||[]).includes(v))&&(!s||subjectTerms(e[2]).some(term=>normalize(term)===s))}
function matchEntry(e,q,tokens,exactOnly=false){
 if(!q)return exactOnly?null:{score:8,type:"all",exact:false};
 if(/[0-9۰-۹٠-٩]+$/u.test(q))return e._rawWordTerms.concat(e._rawEnglishTerms,e._refTerms).includes(q)?{score:0,type:"direct",exact:true}:null;
 const primaryEnglish=e._englishTerms[0]||"",alternateEnglish=e._englishTerms.slice(1);
 const primaryTerms=e._wordTerms.concat(primaryEnglish?[primaryEnglish]:[]);
 const primaryWords=primaryTerms.flatMap(term=>wordTokens(term).map(normalize));
 const exactPrimary=e._wordTerms.includes(q)||primaryEnglish===q,exactAlternate=alternateEnglish.includes(q);
 if(exactOnly){
  const titleTerms=splitTerms(e[0]).concat(primaryEnglish?[primaryEnglish]:[]).map(term=>wordTokens(stripIndex(term)).map(normalize)),titleWords=titleTerms.flat();
  if(exactPrimary)return {score:0,type:"direct",exact:true};
  if(titleTerms.some(words=>words[0]===q))return {score:1,type:"direct",exact:false};
  if(titleWords.includes(q))return {score:2,type:"direct",exact:false};
  if(exactAlternate)return {score:3,type:"direct",exact:true};
  if(e._refBaseTerms.includes(q))return {score:4,type:"direct",exact:false};
  return null;
 }
 if(exactPrimary)return {score:0,type:"direct",exact:true};
 if(primaryTerms.some(term=>term.startsWith(q))||primaryWords.some(word=>word.startsWith(q)))return {score:1,type:"direct",exact:false};
 if(primaryTerms.some(term=>term.includes(q))||primaryWords.includes(q))return {score:2,type:"direct",exact:false};
 if(exactAlternate)return {score:3,type:"direct",exact:true};
 if(alternateEnglish.some(term=>term.startsWith(q)))return {score:4,type:"direct",exact:false};
 if(alternateEnglish.some(term=>term.includes(q))||e._compact.includes(q))return {score:5,type:"direct",exact:false};
 if(e._refBaseTerms.includes(q))return {score:6,type:"direct",exact:true};
 if(e._refBaseTerms.some(term=>term.startsWith(q)))return {score:7,type:"direct",exact:false};
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
