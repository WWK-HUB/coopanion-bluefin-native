"""Deterministic sprite slicing/anchor normalization; original artwork is retained."""
from pathlib import Path
import json
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy import ndimage

ROOT=Path(__file__).parent
OUT=ROOT/'bluefin-native'/'assets'
OUT.mkdir(exist_ok=True, parents=True)
records={}

def cell(sheet, box, name, scale=.88):
    im=Image.open(ROOT/'source'/sheet).convert('RGBA').crop(tuple(map(int,box)))
    a=np.array(im)
    labels,n=ndimage.label(a[:,:,3]>24)
    counts=np.bincount(labels.ravel()); counts[0]=0
    main=int(counts.argmax())
    # Reject disconnected capture speckles, preserve deliberate expression symbols.
    keep=(counts>=max(110,counts[main]*.003));keep[0]=False
    edge=np.unique(np.concatenate([labels[0],labels[-1],labels[:,0],labels[:,-1]]))
    for k in edge:
        if k!=main:keep[k]=False
    a[:,:,3]=np.where(keep[labels],a[:,:,3],0)
    ys,xs=np.where(labels==main)
    cx=(xs.min()+xs.max())/2; bottom=ys.max()+1
    im=Image.fromarray(a)
    size=(round(im.width*scale),round(im.height*scale))
    im=im.resize(size,Image.Resampling.LANCZOS)
    canvas=Image.new('RGBA',(512,512))
    offset=(round(256-cx*scale),round(488-bottom*scale))
    canvas.alpha_composite(im,offset)
    canvas.save(OUT/(name+'.png'))
    records[name]={'source':sheet,'crop':box,'scale':scale,'offset':offset}

names=['neutral','talk','closed','wave_seed','smug','sit_happy','side','back']
for i,name in enumerate(names):
    x=i%4*362;y=i//4*543
    cell('base.png',(x,y,x+362,y+543),name)
names=['excited','happy','shy','thinking','angry','sad','cry','love']
for i,name in enumerate(names):
    x=i%4*362;y=i//4*543
    cell('emotions.png',(x,y,x+362,y+543),name)
for name,box in {
 'sit':(0,0,347,480),'prone':(347,0,720,480),
 'sleep':(720,0,1090,480),'stretch':(1090,0,1448,480),
 'jump':(0,480,350,1086),'crouch':(350,480,707,1086),
 'lift_seed':(707,480,1040,1086),'fall':(1040,480,1448,1086)
}.items(): cell('poses.png',box,name)
for i in range(8):
    x=i%4*418;y=i//4*470.5
    cell('walk.png',(x,y,x+418,y+470.5),'walk_'+str(i),1.03)

generated=ROOT/'source'/'supplement.png'
if generated.exists():
    im=Image.open(generated);w,h=im.size
    # One common scale for each four-frame sequence, never individually fit frames.
    boundaries=[0,330,615,931,h]
    for row,prefix in enumerate(['lift','dizzy','bow','wave']):
        scale=1.38
        for col in range(4):
            cell('supplement.png',(col*w/4,boundaries[row],(col+1)*w/4,boundaries[row+1]),prefix+'_'+str(col),scale)

generated2=ROOT/'source'/'supplement2.png'
if generated2.exists():
    im=Image.open(generated2);w,h=im.size
    for row,prefix in enumerate(['nod','shake','run','dance']):
        scale=440/(h/4*.87)
        for col in range(4):cell('supplement2.png',(col*w/4,row*h/4,(col+1)*w/4,(row+1)*h/4),prefix+'_'+str(col),scale)

font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',17)
items=list(records)
contact=Image.new('RGB',(8*200,((len(items)+7)//8)*225),'#e9edf5')
d=ImageDraw.Draw(contact)
for i,name in enumerate(items):
    x=i%8*200;y=i//8*225
    im=Image.open(OUT/(name+'.png'));im.thumbnail((200,200))
    contact.paste(im,(x,y),im);d.text((x+8,y+202),name,font=font,fill='#243858')
contact.save(ROOT/'preview'/'all-frames.jpg')
(ROOT/'source'/'asset-provenance.json').write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding='utf-8')
Image.open(OUT/'neutral.png').resize((256,256),Image.Resampling.LANCZOS).save(OUT/'thumb.png')
print('Normalized frames:',len(records))
