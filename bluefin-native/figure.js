/** Bluefin Native 1.0 — 64 painted keyframes + native Coopanion API 2 physics.
 * No network, storage, dependencies or application modifications.
 */
const NS='http://www.w3.org/2000/svg';
const loop=(prefix,t,fps=8,n=4)=>`${prefix}_${Math.floor(t*fps)%n}`;
const clip=(v,a,b)=>Math.max(a,Math.min(b,v));
const ordered=(frames,k)=>frames[Math.min(frames.length-1,Math.floor(clip(k,0,.99999)*frames.length))];
export const EXPRESSIONS=['neutral','happy','wink','love','shy','surprised','angry','sad','sleepy','thinking','smug','pout','worried','determined','flustered','scared','excited','cry','confused'];
export const MOTIONS=['stand','jump','hop','look','turn','nod','shake','spin','sit','sleep','dizzy','walk','run','wave','bow','shiver','flap','dance'];
export const ASSETS=[
 'neutral','talk','closed','wave_seed','smug','sit_happy','side','back',
 'excited','happy','shy','thinking','angry','sad','cry','love',
 'sit','prone','sleep','stretch','jump','crouch','lift_seed','fall',
 ...Array.from({length:8},(_,i)=>`walk_${i}`),
 ...['lift','dizzy','bow','wave','nod','shake','run','dance'].flatMap(p=>Array.from({length:4},(_,i)=>`${p}_${i}`))
];
const facePose={neutral:'neutral',happy:'happy',wink:'talk',love:'love',shy:'shy',surprised:'jump',angry:'angry',sad:'sad',sleepy:'closed',thinking:'thinking',smug:'smug',pout:'angry',worried:'sad',determined:'smug',flustered:'shy',scared:'sad',excited:'excited',cry:'cry',confused:'thinking',listening:'neutral',content:'sit',bowing:'bow_2',waking:'stretch',squeeze:'closed',run:'run_0',dragged:'lift_1',dizzy:'dizzy_1',sleep:'sleep'};

/** Painting selection is deterministic and independently inspectable in the gallery. */
export function selectFrame(fr){
 const t=fr.t||0,mt=fr.modeT||0,g=fr.gesture;
 let pose=facePose[fr.face]||'neutral',mirror=false;
 // Physics states take priority over expression and conversational gestures.
 if(fr.mode==='drag') pose=loop('lift',mt,7);
 else if(fr.mode==='dizzy') pose=mt<.12?'fall':mt<2.4?loop('dizzy',mt,7):ordered(['sit','shake_0','shake_2','crouch','neutral'],(mt-2.4)/.6);
 else if(fr.mode==='air') pose=fr.thrown||fr.face==='dragged'?'fall':g?.kind==='flap'?loop('wave',mt,11):'jump';
 else if(fr.mode==='crouch') pose='crouch';
 else if(fr.mode==='land') pose=mt<.22?'crouch':'neutral';
 else if(g?.kind==='spin'){
   pose=ordered(['neutral','side','back','side','neutral'],g.k);
   mirror=g.k>.65&&g.k<.85;
 }else if(g?.kind==='nod')pose=ordered(['nod_0','nod_1','nod_2','nod_1','nod_0','nod_1','nod_2','nod_3'],g.k);
 else if(g?.kind==='shake')pose=ordered(['shake_1','shake_0','shake_1','shake_2','shake_3','shake_0','shake_2','shake_3'],g.k);
 else if(g?.kind==='bow')pose=ordered(['bow_0','bow_1','bow_2','bow_2','bow_2','bow_3','bow_0'],g.k);
 else if(g?.kind==='wave')pose=ordered(['wave_0','wave_1','wave_2','wave_3','wave_2','wave_3','wave_2','wave_1','wave_0'],g.k);
 else if(g?.kind==='hug')pose=ordered(['sit','sit_happy','love','love','sit_happy'],g.k);
 else if(g?.kind==='prone')pose=ordered(['sit','prone','prone','prone','sit'],g.k);
 else if(g?.kind==='stretch')pose=ordered(['sit','stretch','stretch','sit_happy'],g.k);
 else if(fr.mode==='walk'){
   const i=Math.floor(mt*9)%4;
   pose=`walk_${i+(fr.facing<0?4:0)}`;
   mirror=fr.facing<0; // original leftward strip, compensate the kit's stage reflection
 }else if(fr.mode==='run')pose=loop('run',mt,13);
 else if(fr.mode==='dance')pose=loop('dance',mt,5);
 else if(fr.mode==='sleep')pose=mt<.18?'sit':mt<.4?'prone':'sleep';
 else if(fr.mode==='wake')pose=fr.face==='surprised'?ordered(['sit','jump','jump','crouch','neutral'],mt/.9):ordered(['sleep','prone','sit','stretch','stretch','crouch','neutral'],mt/1.8);
 else if(fr.mode==='sit')pose=mt<.12?'crouch':fr.face==='happy'?'sit_happy':fr.face==='love'?'love':'sit';
 else if(fr.mode==='look')pose=mt<.85?'shake_0':mt<1.75?'shake_2':'neutral';
 else if(g?.kind==='shiver')pose=Math.floor(t*12)%3?'sad':'shy';
 else if(g?.kind==='flap')pose=loop('wave',g.k*1.4,10);
 else if(Math.abs(fr.facing??1)<.8)pose='side';
 else if(fr.face==='neutral'&&fr.talk>.12)pose=Math.floor(t*11)%3?'talk':'neutral';
 else if(fr.face==='sleepy')pose=Math.floor(t*1.4)%3?'closed':'sit_happy';
 else if(fr.face==='happy'&&Math.floor(t*3)%4===0)pose='talk';
 else if(fr.face==='excited'&&Math.floor(t*4)%3===0)pose='happy';
 else if(fr.face==='neutral'&&(fr.blink||0)>.65)pose='closed';
 return {pose,mirror};
}

/** Local row mesh adds breathing and gaze to painted poses; it never substitutes for action frames. */
export function paint(ctx,images,fr){
 const {pose,mirror}=selectFrame(fr),im=images[pose];
 if(!im)throw new Error(`Missing painted frame: ${pose}`);
 ctx.clearRect(0,0,512,512);ctx.save();
 if(mirror){ctx.translate(512,0);ctx.scale(-1,1);}
 const t=fr.t||0;
 const calm=['idle','look','sit','sleep'].includes(fr.mode)&&!fr.gesture;
 const breath=calm?Math.sin(t*(fr.mode==='sleep'?1.7:2.4)):0;
 const gaze=pose==='neutral'||pose==='talk'?clip(fr.look?.[0]||0,-5,5):0;
 const tremble=(fr.face==='scared'||fr.gesture?.kind==='shiver')?Math.sin(t*55)*1.1:0;
 for(let y=0;y<512;y+=4){
   const head=Math.max(0,1-Math.abs(y-185)/145);
   const body=Math.max(0,1-Math.abs(y-345)/140);
   const dx=gaze*.55*head+tremble*head;
   const dy=breath*(head*.7-body*.6);
   ctx.drawImage(im,0,y,512,4,dx,y+dy,512,4.4);
 }
 if(fr.face==='wink'&&['talk','neutral'].includes(pose)){
   // Copy the closed eye from the supplied painted blink pose; keep the other eye open.
   ctx.save();ctx.beginPath();ctx.ellipse(297,236,25,19,0,0,Math.PI*2);ctx.clip();
   ctx.drawImage(images.closed,0,0);ctx.restore();
 }
 if(['worried','scared','flustered'].includes(fr.face)){
   const y=215+Math.sin(t*5)*5;
   ctx.fillStyle='#83c9f6';ctx.strokeStyle='#4265a1';ctx.lineWidth=2;
   ctx.beginPath();ctx.moveTo(373,y);ctx.bezierCurveTo(357,y+20,357,y+29,372,y+30);ctx.bezierCurveTo(387,y+29,383,y+18,373,y);ctx.fill();ctx.stroke();
 }
 ctx.restore();
 return pose;
}

export async function createBluefin(base,{kit,host,loadImage,asset}){
 const images=Object.fromEntries(await Promise.all(ASSETS.map(async n=>[n,await loadImage(asset(`assets/${n}.png`))])));
 const doc=host.root.ownerDocument;
 const group=doc.createElementNS(NS,'g');group.setAttribute('class','bluefin-native');
 const fo=doc.createElementNS(NS,'foreignObject');
 fo.setAttribute('x','0');fo.setAttribute('y','12');fo.setAttribute('width','256');fo.setAttribute('height','256');fo.style.overflow='visible';
 const canvas=doc.createElementNS('http://www.w3.org/1999/xhtml','canvas');canvas.width=canvas.height=512;canvas.style.cssText='display:block;width:256px;height:256px;pointer-events:none';
 fo.appendChild(canvas);group.appendChild(fo);
 const ctx=canvas.getContext('2d');
 let previousMode='idle',thrown=false,currentPose='neutral';
 const lying=()=>['sleep','prone'].includes(currentPose);
 const sitting=()=>['sit','sit_happy','stretch','love','crouch'].includes(currentPose)||currentPose.startsWith('dizzy_');
 const figure={
   draw(parent,face,fr){
     if(fr.mode==='air'&&previousMode!=='air')thrown=previousMode==='drag';
     if(fr.mode!=='air')thrown=false;
     previousMode=fr.mode;
     if(group.parentNode!==parent){parent.textContent='';parent.appendChild(group);}
     currentPose=paint(ctx,images,{...fr,thrown});canvas.dataset.pose=currentPose;canvas.dataset.mode=fr.mode;
   },
   gestures:['nod','shake','spin','wave','bow','flap','shiver'],
   groupTilt(mode,tilt,lean){return ['dizzy','sleep','sit','dance'].includes(mode)?0:mode==='drag'?tilt*.4:tilt+lean;},
   get anchors(){const top=lying()?143:sitting()?90:28;return{gaze:[128,top+90],tear:[112,top+110],tears:[[110,top+110],[148,top+110]],z:[203,top+5],hearts:[92,170,top],bubble:[128,top-15]};},
   get extent(){return lying()?[36,143,223,256]:sitting()?[43,90,223,256]:[48,28,217,256];},
   get hits(){return lying()?[[100,212,48],[170,227,43]]:sitting()?[[128,162,65],[128,220,49]]:[[128,110,72],[128,200,57]];},
   colors:{z:'#79a9e9'},
 };
 const body=kit.createBody(host,{figure,css:':root{--skin-eye:#79a9e9;--heart:#ff739a;--tear:#79c9fa}',words:{hug:{motion:{seconds:2.8,face:'love'}},prone:{motion:{seconds:3,face:'happy'}},stretch:{motion:{seconds:2.4,face:'happy'}}}});
 // Exposed only as DOM data in the sandbox: useful for the shipped preview and visual QA.
 return body;
}
