#!/usr/bin/env python3
"""Run or resume the matching checkpoint for this exact code and source snapshot."""
import argparse,hashlib,subprocess,sys
from pathlib import Path
HERE=Path(__file__).resolve().parent;ROOT=HERE.parent

def checkpoint_id():
 h=hashlib.sha256()
 for p in (ROOT/'dictionary.json',ROOT/'book.json',HERE/'rules.json',HERE/'engine.py'):
  h.update(p.name.encode('utf8'));h.update(b'\0');h.update(hashlib.sha256(p.read_bytes()).digest())
 return h.hexdigest()

def main():
 p=argparse.ArgumentParser();p.add_argument('--checkpoint-dir',type=Path,default=HERE/'.checkpoints');p.add_argument('--out',type=Path,default=HERE/'outputs');p.add_argument('--batch',type=int,default=500);a=p.parse_args()
 if a.batch<1:p.error('--batch must be positive')
 a.checkpoint_dir.mkdir(parents=True,exist_ok=True);db=a.checkpoint_dir/(checkpoint_id()+'.sqlite3')
 print(f'Checkpoint: {db}',flush=True)
 return subprocess.call([sys.executable,str(HERE/'engine.py'),'--db',str(db),'--out',str(a.out),'--batch',str(a.batch)])
if __name__=='__main__':raise SystemExit(main())
