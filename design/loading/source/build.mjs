import fs from 'node:fs/promises';
import path from 'node:path';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
import {THEMES,CHARACTER_ROLES,SPRITE_SIZES,characterObjects,svg,heart,sakura,petal,sparkle,makeScene,state,staticLayers,GEOMETRY} from './scene.mjs';
const require=createRequire(import.meta.url); const sharp=require('sharp');
const root=path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const write=async(p,data)=>{await fs.mkdir(path.dirname(path.join(root,p)),{recursive:true});await fs.writeFile(path.join(root,p),data);};
const png=async(body)=>sharp(Buffer.from(body)).png().toBuffer();
const dataUri=b=>'data:image/png;base64,'+b.toString('base64');
const imageBuffers={},uris={};
for(const key of [...new Set(Object.values(CHARACTER_ROLES).flatMap(Object.values))]){
 const b=await sharp(path.join(root,'source/originals',key+'.png')).resize(...SPRITE_SIZES[key],{fit:'fill'}).png().toBuffer();
 await write('shared/characters/'+key+'@3x.png',b);imageBuffers[key]=b;uris[key]=dataUri(b);
}
const effects={heart:svg(heart(4,4,40),48,48),'video-heart':svg(heart(4,4,40)+'<path d="M21 15L31 22L21 29Z" fill="#fff"/>',48,48),sakura:svg(sakura(24,24,39),48,48),petal:svg(petal(24,34,25,15),48,48),sparkle:svg(sparkle(24,24,19),48,48)};
for(const [key,source] of Object.entries(effects)){await write('shared/effects/'+key+'.svg',source);const b=await png(source);imageBuffers[key]=b;await write('shared/effects/'+key+'@3x.png',b);}
const constant=k=>({a:0,k});
const prop=(values,hold=false)=>({a:1,k:values.map(([t,v],i)=>({t,s:Array.isArray(v)?v:[v],...(i<values.length-1?(hold?{h:1}:{o:{x:.333,y:.333},i:{x:.667,y:.667},e:Array.isArray(values[i+1][1])?values[i+1][1]:[values[i+1][1]]}):{})}))});
function rect(x,y,w,h){return {i:[[0,0],[0,0],[0,0],[0,0]],o:[[0,0],[0,0],[0,0],[0,0]],v:[[x,y],[x+w,y],[x+w,y+h],[x,y+h]],c:true};}
async function lottie(theme){
 const parts=staticLayers(theme),assets=[],roles=CHARACTER_ROLES[theme]; const imgs=Object.fromEntries([...new Set([...Object.values(roles),'heart','video-heart','sakura','petal','sparkle'])].map(key=>[key,imageBuffers[key]]));
 for(const key of ['track','fill','decor']){imgs[key]=await png(svg(parts[key]));}
 for(const [id,b] of Object.entries(imgs)){let m=await sharp(b).metadata();assets.push({id,w:m.width,h:m.height,u:'images/',p:id+'.png',e:0});await write(theme+'/lottie/images/'+id+'.png',b);}
 let index=0;
 function layer(name,ref,position=[0,0,0],scale=[100,100,100],opacity=100){return {ddd:0,ind:++index,ty:2,nm:name,refId:ref,sr:1,ks:{o:constant(opacity),r:constant(0),p:constant(position),a:constant([0,0,0]),s:constant(scale)},ao:0,ip:0,op:125,st:0,bm:0};}
 const track=layer('track','track'),fill=layer('progress-fill','fill'),decor=layer('track-decoration','decor');
 const samples=Array.from({length:125},(_,f)=>({f,s:state(theme,Math.min(f,100)/100,f<100?null:(f-100)/30),objects:characterObjects(theme,Math.min(f,100)/100,f<100?null:(f-100)/30)}));
 const people=samples[0].objects.map((o,n)=>{
  const l=layer(o.name,o.key);
  l.ks.p=prop(samples.map(v=>[v.f,[v.objects[n].x,v.objects[n].y,0]]));
  l.ks.o=prop(samples.map(v=>[v.f,v.objects[n].opacity*100]));
  return l;
 });
 const h=layer('completion-heart',theme==='video'?'video-heart':'heart');
 h.ks.a=constant([24,24,0]);
 h.ks.p=prop(samples.map(v=>[v.f,[v.s.heartX,v.s.heartY,0]]));
 h.ks.s=prop(samples.map(v=>[v.f,[v.s.heartScale*34/48*100,v.s.heartScale*34/48*100,100]]));
 const {x,y,w}=GEOMETRY.track;
 const mask=(name,fn,hold=false)=>({inv:false,mode:'a',nm:name,pt:{a:1,k:Array.from({length:101},(_,f)=>({t:f,s:[fn(f/100)],...(f===100?{}:hold?{h:1}:{o:{x:.333,y:.333},i:{x:.667,y:.667},e:[fn((f+1)/100)]})}))},o:constant(100),x:constant(0)});
 if(theme==='video')fill.masksProperties=[mask('left-fill',p=>rect(x,230,w*p/2,56)),mask('right-fill',p=>rect(x+w-w*p/2,230,w*p/2,56))];
 else if(theme==='queue'||theme==='image'){
  const n=theme==='queue'?7:8;const start=theme==='queue'?x-1:x-3;const step=theme==='queue'?(w-38)/6:(w-40)/7;const unit=theme==='queue'?40:46;
  fill.masksProperties=[mask('completed-items',p=>{const count=Math.floor(p*n+1e-8);return rect(start,230,count?step*(count-1)+unit:0,56);},true)];
  fill.masksProperties[0].pt.k=Array.from({length:n+1},(_,count)=>({t:count*100/n,s:[rect(start,230,count?step*(count-1)+unit:0,56)],...(count<n?{h:1}:{})}));
 }else fill.masksProperties=[mask('linear-fill',p=>rect(x,235,w*p,50))];
 fill.hasMask=true;
 const layers=[h,...people.reverse()];
 if(theme==='app'){const node=layer('sakura-node','sakura',[x,y+17,0],[25/48*100,25/48*100,100]);node.ks.a=constant([24,24,0]);node.ks.p=prop([[0,[x,y+17,0]],[100,[x+w,y+17,0]],[124,[x+w,y+17,0]]]);layers.push(node);}
 else {
  for(let n=0;n<2;n++){const fx=layer('completion-petal-'+n,'petal');fx.ks.a=constant([24,24,0]);fx.ks.s=constant([27,27,100]);fx.ks.p=prop(Array.from({length:25},(_,i)=>{const s=state(theme,1,i/30);return [100+i,[s.heartX+(n?39:-43)+(n?14:-18)*s.t,60+s.t*26,0]];}));fx.ks.o=prop([[0,0],[109,0],[116.5,100],[124,0]]);fx.ks.r=prop([[100,-35],[124,35]]);layers.unshift(fx);}
  const gl=layer('completion-sparkle','sparkle');gl.ks.a=constant([24,24,0]);gl.ks.p=constant([theme==='video'?469:789,60,0]);gl.ks.s=constant([45,45,100]);gl.ks.o=prop([[0,0],[109,0],[116.5,100],[124,0]]);layers.unshift(gl);
 }
 layers.push(decor,fill,track);
 const doc={v:'5.12.2',fr:30,ip:0,op:125,w:960,h:300,nm:'Sakura Romance '+theme,ddd:0,assets,layers,markers:[{tm:0,cm:'progress-controlled-0-100',dr:100},{tm:100,cm:'complete-once',dr:24}]};
 await write(theme+'/lottie/loading.json',JSON.stringify(doc));
 const embedded=structuredClone(doc);for(const a of embedded.assets){a.p=dataUri(imgs[a.id]);a.u='';a.e=1;}await write(theme+'/lottie/loading-embedded.json',JSON.stringify(embedded));
}
for(const theme of THEMES){
 const parts=staticLayers(theme);
 for(const [key,body] of Object.entries(parts)){await write(theme+'/layers/'+key+'.svg',svg(body));await write(theme+'/layers/'+key+'@3x.png',await png(svg(body)));}
 for(const [label,p,t] of [['loading',.5,null],['complete',1,.8]]){const source=makeScene(theme,p,t,uris);await write(theme+'/source/'+label+'.svg',source);await write(theme+'/'+label+'@3x.png',await png(source));}
 const rows=[];
 for(const [name,p,t] of [['000',0,null],['025',.25,null],['050',.5,null],['075',.75,null],['100',1,null],['complete-fx',1,.8]]){const b=await png(makeScene(theme,p,t,uris));await write(theme+'/states/'+name+'@3x.png',b);rows.push({input:b,top:rows.length*300,left:0});}
 await write(theme+'/preview@3x.png',await sharp({create:{width:960,height:1800,channels:4,background:{r:0,g:0,b:0,alpha:0}}}).composite(rows).png().toBuffer());
 for(let i=0;i<=20;i++)await write(theme+'/frames/progress/'+String(i*5).padStart(3,'0')+'.png',await png(makeScene(theme,i/20,null,uris)));
 for(let i=0;i<=24;i++)await write(theme+'/frames/complete/'+String(i).padStart(3,'0')+'.png',await png(makeScene(theme,1,i/30,uris)));
 await lottie(theme);
 console.log('Built '+theme);
}
await write('manifest.json',JSON.stringify({version:3,canvas:{width:960,height:300,displayWidth:320,displayHeight:100,scale:3},themes:THEMES,progress:{range:[0,1],lottieFrame:'progress * 100',durationIsNotProgress:true},completion:{lottieFrames:[100,124],fps:30,durationSeconds:.8,loop:false},queue:{count:7,input:'completedCount / totalCount; use known queue state only'},spriteSizes:SPRITE_SIZES,geometry:GEOMETRY,characters:CHARACTER_ROLES,source:'source/scene.mjs',idle:'Hold progress and optionally breathe +-1 px at display size; do not advance unknown progress.',alpha:'RGBA; no backdrop; retained alpha from generated character sources.'},null,2));
console.log('DONE');
