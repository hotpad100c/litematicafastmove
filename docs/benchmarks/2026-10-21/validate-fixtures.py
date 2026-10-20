from pathlib import Path
import gzip,struct,io,json
class Reader:
 def __init__(self,p):self.f=io.BytesIO(gzip.decompress(p.read_bytes()))
 def r(self,n):return self.f.read(n)
 def v(self,s):return struct.unpack('>'+s,self.r(struct.calcsize('>'+s)))[0]
 def text(self):return self.r(self.v('H')).decode('utf8')
 def p(self,t):
  if t in (1,2,3,4,5,6):return self.v({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[t])
  if t==8:return self.text()
  if t==7:return self.r(self.v('i'))
  if t==9:
   k=self.v('B');return [self.p(k) for _ in range(self.v('i'))]
  if t==10:
   d={}
   while (k:=self.v('B')):n=self.text();d[n]=self.p(k)
   return d
  if t in (11,12):return [self.v('i' if t==11 else 'q') for _ in range(self.v('i'))]
 def load(self):k=self.v('B');self.text();return self.p(k)
r=Path(__file__).parent;out=[]
for f in json.loads((r/'fixtures/manifest.json').read_text()):
 d=Reader(r/'fixtures'/(f['name']+'.litematic')).load();v=d['Regions']['Test'];pal=v['BlockStatePalette'];a=[x&((1<<64)-1) for x in v['BlockStates']];bits=max(2,(len(pal)-1).bit_length());mask=(1<<bits)-1;sx,sy,sz=f['size'];nn=0;invalid=0;isolated=True
 for y in range(sy):
  for z in range(sz):
   for x in range(sx):
    i=(y*sz+z)*sx+x;j,sh=divmod(i*bits,64);idx=(a[j]>>sh)&mask;invalid+=int(idx>=len(pal));nn+=int(idx!=0)
    assert (idx!=0)==('solid' in f['name'] or (x+y+z)%2==0)
 assert nn==f['nonair'] and invalid==0 and d['Metadata']['TotalBlocks']==nn
 out.append({'name':f['name'],'decoded_nonair':nn,'invalid_palette_ids':invalid,'pattern_checked_every_position':True})
(r/'fixtures/independent-validation.json').write_text(json.dumps(out,indent=2),'utf8');print('independent fixture validation passed',len(out))
