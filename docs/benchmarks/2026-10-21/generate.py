from pathlib import Path
import struct,gzip,json,time
r=Path(__file__).parent;out=r/'fixtures';out.mkdir(exist_ok=True)
def name(s):b=s.encode();return struct.pack('>H',len(b))+b
def tag(t,n,v):return bytes([t])+name(n)+v
def integer(n,v):return tag(3,n,struct.pack('>i',v))
def lng(n,v):return tag(4,n,struct.pack('>q',v))
def string(n,v):return tag(8,n,name(v))
def comp(n,v):return tag(10,n,b''.join(v)+b'\0')
def xyz(n,x,y,z):return comp(n,[integer('x',x),integer('y',y),integer('z',z)])
fixtures=[]
for label,sx,sy,sz,kind in [('solid-small',64,32,64,'solid'),('solid-large',128,64,128,'solid'),('faces-small',64,32,64,'checker'),('faces-large',128,64,128,'checker')]:
 names=["air","stone"] if kind=="solid" else ["air","stone","cobblestone","dirt","andesite","iron_block","oak_planks","white_concrete","gold_block"];bits=max(2,(len(names)-1).bit_length());total=sx*sy*sz;arr=[0]*((total*bits+63)//64);nonair=0;rand=12345
 for y in range(sy):
  for z in range(sz):
   for x in range(sx):
    i=(y*sz+z)*sx+x;rand=(rand*1664525+1013904223)&0xffffffff;v=1 if kind=="solid" else (1+((rand>>16)%8) if (x+y+z)%2==0 else 0)
    arr[(i*bits)//64]|=v<<((i*bits)%64);nonair+=int(v!=0)
 palette=tag(9,'BlockStatePalette',bytes([10])+struct.pack('>i',len(names))+b''.join(comp('',[string('Name','minecraft:'+n)])[3:] for n in names))
 # Unnamed list compound payload is content+TAG_End (omit tag id/name header).
 region=comp('Test',[xyz('Position',0,0,0),xyz('Size',sx,sy,sz),palette,tag(12,'BlockStates',struct.pack('>i',len(arr))+b''.join(struct.pack('>Q',a) for a in arr)),tag(9,'Entities',bytes([10])+struct.pack('>i',0)),tag(9,'TileEntities',bytes([10])+struct.pack('>i',0))])
 metadata=comp('Metadata',[string('Name',label),string('Author','LFM isolated performance fixture'),string('Description',kind),xyz('EnclosingSize',sx,sy,sz),integer('RegionCount',1),integer('TotalVolume',total),integer('TotalBlocks',nonair),lng('TimeCreated',0),lng('TimeModified',0)])
 raw=comp('',[integer('Version',6),integer('SubVersion',1),integer('MinecraftDataVersion',3700),metadata,comp('Regions',[region])]);p=out/(label+'.litematic');p.write_bytes(gzip.compress(raw,mtime=0))
 faces=2*(sx*sy+sx*sz+sy*sz) if kind=='solid' else nonair*6
 fixtures.append({'name':label,'size':[sx,sy,sz],'volume':total,'nonair':nonair,'expected_faces':faces,'expected_indices':faces*6,'expected_vertex_bytes':faces*128,'expected_sections':(sx//16)*(sy//16)*(sz//16),'expected_columns':(sx//16)*(sz//16),'file_bytes':p.stat().st_size})
(out/'manifest.json').write_text(json.dumps(fixtures,indent=2),'utf8');print(json.dumps(fixtures,indent=2))
