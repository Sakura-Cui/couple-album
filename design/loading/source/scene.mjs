// Editable vector layers and deterministic layout. All coordinates are 3x (960 x 300).
export const THEMES = ['video', 'image', 'queue', 'app'];
export const CHARACTER_ROLES = {
 video:{boy:'video-boy',boyComplete:'video-boy',girlWait:'video-girl',girlComplete:'video-girl'},
 image:{boy:'image-boy',boyComplete:'image-boy-complete',girlWait:'image-girl-wait',girlComplete:'image-girl-smile'},
 queue:{boy:'queue-boy',boyComplete:'boy',girlWait:'queue-girl-wait',girlComplete:'queue-girl-smile',cart:'queue-cart'},
 app:{boy:'boy',boyComplete:'boy',girlWait:'girl-wait',girlComplete:'girl-smile'}
};
// Exported raster dimensions preserve each new sprite's aspect ratio.
export const SPRITE_SIZES = {
 'video-boy':[299,210], 'video-girl':[302,210],
 'image-boy':[161,216], 'image-boy-complete':[143,216],
 'image-girl-wait':[151,216], 'image-girl-smile':[153,216],
 'queue-boy':[183,215], 'queue-cart':[166,166],
 'queue-girl-wait':[160,216], 'queue-girl-smile':[162,216],
 'boy':[180,216], 'girl-wait':[144,216], 'girl-smile':[144,216]
};
export const SIZE = {width:960,height:300};
export const GEOMETRY = {boy:{w:180,h:216,y:20,start:20,end:669,mouth:[154,88]},girl:{w:144,h:216,y:24,start:792,cheek:[31,84],mouth:[45,82]},track:{x:161,y:244,w:662,h:34}};
export const clamp = p => Number.isFinite(p) ? Math.max(0,Math.min(1,p)) : 0;
export const defs = `<defs>
<linearGradient id="glass" x2="0" y2="1"><stop stop-color="#fff" stop-opacity=".85"/><stop offset=".45" stop-color="#f9e9ee" stop-opacity=".40"/><stop offset="1" stop-color="#d6cbd6" stop-opacity=".6"/></linearGradient>
<linearGradient id="pink" x2="0" y2="1"><stop stop-color="#ffd4dc"/><stop offset=".45" stop-color="#ff94b2"/><stop offset="1" stop-color="#ffc2ab"/></linearGradient>
<linearGradient id="peach"><stop stop-color="#ffa6c1"/><stop offset=".6" stop-color="#ffb7c8"/><stop offset="1" stop-color="#ffd1b6"/></linearGradient>
<linearGradient id="shine" x2="0" y2="1"><stop stop-color="#fff" stop-opacity=".85"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></linearGradient>
<radialGradient id="gold"><stop stop-color="#fffbe7"/><stop offset=".5" stop-color="#ffe6ad" stop-opacity=".8"/><stop offset="1" stop-color="#ffe6ad" stop-opacity="0"/></radialGradient>
</defs>`;
export function svg(body,w=960,h=300) {return `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">${defs}${body}</svg>`;}
export function heart(x,y,size=34,lit=true){return `<g transform="translate(${x} ${y}) scale(${size/40})"><path d="M20 36C15 31 1 23 1 12C1 1 15-1 20 8C25-1 39 1 39 12C39 23 25 31 20 36Z" fill="url(#${lit?'pink':'glass'})" stroke="#fff" stroke-opacity=".95" stroke-width="1.8"/><path d="M6 12C6 6 12 4 16 8" fill="none" stroke="#fff" stroke-opacity=".8" stroke-width="2.4" stroke-linecap="round"/></g>`;}
export function sakura(x,y,size=28){return `<g transform="translate(${x} ${y}) scale(${size/40})">${Array.from({length:5},(_,i)=>`<path transform="rotate(${i*72})" d="M0 1C-13-7-10-19-3-18L0-14L3-18C10-19 13-7 0 1" fill="#ffdbE6" stroke="#fff7f9" stroke-width="1.5"/>`).join('')}<circle r="4" fill="#ffbdcb"/><circle r="1.8" fill="#fff2cf"/></g>`;}
export function petal(x,y,size=14,rot=0){return `<path transform="translate(${x} ${y}) rotate(${rot}) scale(${size/20})" d="M0 0C-14-10-10-21 0-17C13-20 13-6 0 0" fill="url(#pink)" stroke="#fff2f5" stroke-width="1"/>`;}
export function sparkle(x,y,size=12){return `<g transform="translate(${x} ${y})"><circle r="${size}" fill="url(#gold)"/><path d="M0 ${-size*.65}Q1-1 ${size*.65} 0Q1 1 0 ${size*.65}Q-1 1 ${-size*.65} 0Q-1-1 0 ${-size*.65}" fill="#fff8e2"/></g>`;}
function polaroid(x,colored,i){const fill=colored?['#ffc7b8','#f8aaca','#f7b8ca','#b7dcea'][i%4]:'#e9e8ec';return `<g transform="translate(${x} 242)"><rect x="-21" y="-3" width="42" height="37" rx="3" fill="#c4a2ae" opacity=".16"/><rect x="-21" y="-6" width="42" height="37" rx="2" fill="#fff9fa" fill-opacity="${colored?'.97':'.6'}" stroke="#fff" stroke-opacity=".9"/><rect x="-17" y="-2" width="34" height="25" rx="1" fill="${fill}" opacity="${colored?1:.45}"/>${colored?(i%4===0?`<circle cx="8" cy="6" r="5" fill="#fff2ca"/><path d="M-17 19L-5 9L8 20L17 12V23H-17Z" fill="#dd9eae"/>`:i%4===1?sakura(0,11,13):i%4===2?heart(-9,1,18):`<circle cx="8" cy="5" r="4" fill="#fff6d4"/><path d="M-17 20Q0 6 17 19V23H-17Z" fill="#c6d7c8"/>`):'<path d="M-17 20L-5 10L6 20L17 13V23H-17Z" fill="#cdcbd2" opacity=".45"/>'}</g>`;}
export function staticLayers(theme){
 const {x,y,w,h}=GEOMETRY.track;
 let track=`<rect x="${x-3}" y="${y+3}" width="${w+6}" height="${h}" rx="19" fill="#d8a5b5" opacity=".16"/><rect x="${x}" y="${y}" width="${w}" height="${h}" rx="17" fill="url(#glass)" stroke="#ffd5e2" stroke-width="2"/><rect x="${x+3}" y="${y+3}" width="${w-6}" height="${h-6}" rx="14" fill="none" stroke="#fff" stroke-opacity=".85" stroke-width="1.5"/>`;
 let fill=`<rect x="${x+3}" y="${y+3}" width="${w-6}" height="${h-6}" rx="14" fill="url(#peach)"/><rect x="${x+5}" y="${y+4}" width="${w-10}" height="11" rx="6" fill="url(#shine)"/>`;
 let decor='';
 // Tiny highlights stay inside the rail, distinct from completion-only effects.
 for(let i=0;i<9;i++) decor+=sparkle(x+26+i*(w-52)/8,y+17+(i%2?5:-4),i%3===0?5:3);

 const plate=`<rect x="${x-12}" y="236" width="${w+24}" height="48" rx="24" fill="#dba6bb" opacity=".17"/><rect x="${x-12}" y="233" width="${w+24}" height="48" rx="24" fill="url(#glass)" stroke="#efbacd" stroke-width="1.8"/><rect x="${x-8}" y="237" width="${w+16}" height="39" rx="20" fill="none" stroke="#fff" stroke-opacity=".85" stroke-width="1.6"/>`;
 if(theme==='video'){
  track+=`<rect x="${x}" y="${y-7}" width="${w}" height="6" rx="3" fill="#ffc0d4"/><rect x="${x}" y="${y+h+1}" width="${w}" height="6" rx="3" fill="#ffc0d4"/>`;
  for(let i=0;i<40;i++)for(const yy of [y-5,y+h+2])track+=`<rect x="${x+8+i*16.3}" y="${yy}" width="8" height="3" rx=".8" fill="#fff5f8"/>`;
  decor=`<circle cx="492" cy="261" r="13" fill="url(#pink)" stroke="#fff" stroke-width="1.4"/><path d="M488 254L499 261L488 268Z" fill="#fff"/>`;
 }
 if(theme==='image'){track=plate;fill='';for(let i=0;i<8;i++){track+=polaroid(x+20+i*(w-40)/7,false,i);fill+=polaroid(x+20+i*(w-40)/7,true,i);}}
 if(theme==='queue'){track=plate;fill='';for(let i=0;i<7;i++){let hx=x+i*(w-38)/6;track+=heart(hx,y-2,38,false);fill+=heart(hx,y-2,38,true);}}
 // Theme accents stay outside the information-bearing fill and photo/heart slots.
 if(theme==='app'){
  decor+=petal(x-10,y+20,17,-45)+petal(x+w+11,y+14,14,38);
  decor+=`<path d="M${x+20} ${y+7}H${x+95}M${x+w-95} ${y+26}H${x+w-20}" stroke="#fff" stroke-opacity=".7" stroke-width="2" stroke-linecap="round"/>`;
 }else{
  decor+=sakura(x-11,y+15,28)+sakura(x+w+13,y+14,23);
  decor+=petal(x+133,233,13,-38)+petal(x+w-146,287,12,44);
  if(theme==='video')decor+=heart(x+34,228,15)+heart(x+w-46,276,13);
  if(theme==='image')decor+=petal(x+250,286,10,-26)+petal(x+w-255,232,10,55);
 }
 if(theme!=='app'){
  for(const [dx,dy,sz] of [[61,-3,19],[222,38,17],[415,-3,19],[597,38,18]]) decor+=sakura(x+dx,y+dy,sz);
  decor+=petal(x+329,y-1,15,55)+petal(x+515,y+40,12,-42);
 }
 for(const xx of [x+94,x+w-90])decor+=sparkle(xx,theme==='app'?y+15:234,5);
 return {track,fill,decor};
}
const lerp=(a,b,t)=>a+(b-a)*t;
const smooth=t=>{t=clamp(t);return t*t*(3-2*t);};
export function state(theme,p=0,completeTime=null){
 p=clamp(p);const finished=completeTime!==null;if(finished)p=1;
 const t=finished?Math.max(0,Math.min(.8,completeTime)):0;
 const settle=smooth(t/.4),face=smooth((t-.22)/.18);
 let bx=lerp(20,669,p),by=20,gx=792,gy=24;
 let bw=180,bh=216,gw=144,gh=216,completeBoyX=bx,completeBoyY=by;
 if(theme==='video'){
  bx=lerp(22,196,p)+3*settle;gx=lerp(636,443,p)-3*settle;
  by=29;gy=45;bw=299;bh=210;gw=302;gh=210;
  completeBoyX=bx;completeBoyY=by;
 }else if(theme==='image'){
  bx=lerp(24,673,p);by=20;gx=782;gy=24;bw=161;gw=151;
  completeBoyX=lerp(bx,681,settle);completeBoyY=lerp(by,10,settle);
 }else if(theme==='queue'){
  bx=lerp(24,451,p);by=23+(!finished?2*Math.sin(p*14*Math.PI):0);
  gx=780;gy=24;bw=183;bh=215;gw=160;
  completeBoyX=lerp(bx,651,settle);completeBoyY=20;
 }else {completeBoyX=bx;completeBoyY=by;}
 const delay=theme==='queue'?.42:.25;
 const ht=finished?Math.max(0,t-delay):0;
 const heartScale=ht===0?0:ht<.16?1.18*(1-Math.pow(1-ht/.16,3)):ht<.3?1.18-.18*(ht-.16)/.14:1;
 return {p,t,finished,bx,by,gx,gy,bw,bh,gw,gh,completeBoyX,completeBoyY,
 face,settle,heartScale,heartX:theme==='video'?483:807,heartY:30-7*Math.min(ht/.3,1),
 cartX:finished?lerp(616,510,settle):bx+165,cartY:74,
 lit:Math.floor((theme==='queue'?7:8)*p+1e-8)};
}
export function characterObjects(theme,p,t=null){
 const s=state(theme,p,t),r=CHARACTER_ROLES[theme];
 const obj=(name,key,x,y,opacity=1)=>({name,key,x,y,w:SPRITE_SIZES[key][0],h:SPRITE_SIZES[key][1],opacity});
 const out=[];
 if(r.cart)out.push(obj('queue-cart',r.cart,s.cartX,s.cartY));
 if(r.girlWait===r.girlComplete)out.push(obj('girl-wait',r.girlWait,s.gx,s.gy));
 else{
  out.push(obj('girl-wait',r.girlWait,s.gx,s.gy,1-s.face));
  out.push(obj('girl-complete',r.girlComplete,s.gx,s.gy,s.face));
 }
 if(r.boy===r.boyComplete)out.push(obj('boy',r.boy,s.bx,s.by));
 else{
  const fade=theme==='queue'?s.settle:s.face;
  const x=theme==='queue'?s.completeBoyX:s.bx;
  out.push(obj('boy',r.boy,x,s.by,1-fade));
  out.push(obj('boy-complete',r.boyComplete,s.completeBoyX,s.completeBoyY,fade));
 }
 return out;
}
export function fillRects(theme,p){
 const {x,w}=GEOMETRY.track;
 if(theme==='video')return [[x,230,w*p/2,56],[x+w-w*p/2,230,w*p/2,56]];
 if(theme==='queue'||theme==='image'){
  const n=theme==='queue'?7:8,count=Math.floor(n*p+1e-8);
  return Array.from({length:count},(_,i)=>theme==='queue'?[x+i*(w-38)/6-1,230,40,56]:[x+20+i*(w-40)/7-23,230,46,56]);
 }
 return [[x,230,w*p,56]];
}
export function makeScene(theme,p,completeTime,assets){
 const s=state(theme,p,completeTime),parts=staticLayers(theme),{x,y,w}=GEOMETRY.track;
 const clip=fillRects(theme,s.p).map(([x,y,w,h])=>`<rect x="${x}" y="${y}" width="${w}" height="${h}"/>`).join('');
 let body=`<g id="track">${parts.track}</g><clipPath id="progress-clip">${clip}</clipPath><g id="progress-fill" clip-path="url(#progress-clip)">${parts.fill}</g><g id="track-decoration">${parts.decor}</g>`;
 if(theme==='app')body+=`<g id="sakura-node">${sakura(x+w*s.p,y+17,25)}</g>`;
 for(const o of characterObjects(theme,p,completeTime))body+=`<image id="${o.name}" x="${o.x}" y="${o.y}" width="${o.w}" height="${o.h}" opacity="${o.opacity}" href="${assets[o.key]}"/>`;
 if(s.heartScale>0){
  body+=`<g id="completion-heart" transform="translate(${s.heartX} ${s.heartY}) scale(${s.heartScale})">${heart(-17,-17,34)}${theme==='video'?'<path d="M-4 -7L6 0L-4 7Z" fill="#fff"/>':''}</g>`;
 }
 const fxOpacity=s.finished?Math.sin(Math.PI*clamp((s.t-.3)/.5)):0;
 if(theme!=='app'&&fxOpacity>0)body+=`<g id="completion-petals" opacity="${fxOpacity}">${petal(s.heartX-43-s.t*18,68+s.t*24,12,-35+s.t*50)}${petal(s.heartX+39+s.t*14,58+s.t*29,10,35+s.t*60)}${sparkle(s.heartX-18,60,10)}</g>`;
 return svg(body);
}
