import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const themes=['video','image','queue','app'];
const copy={
video:['视频加载','双人趴姿 · 胶片轨道 · 中央亲吻'],
image:['图片加载','直立拍照 · 照片显色 · 放下相机亲脸颊'],
queue:['队列等待','踮脚推小车 · 七颗心 · 胸前期待'],
app:['通用加载','背手前倾 · 樱花毛玻璃 · 爱心弹出']
};
let markup='';
for(const theme of themes){
 const data=await fs.readFile(path.join(root,theme,'lottie/loading-embedded.json'),'utf8');
 markup+=`<section><div class="card-head"><h2>${copy[theme][0]}</h2><span>320 × 100</span></div><p>${copy[theme][1]}</p><div class="stage" id="${theme}"></div><script type="application/json" id="data-${theme}">${data}</script></section>`;
}
await fs.writeFile(path.join(root,'preview/index.html'),`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Sakura Romance · App Loading v3</title>
<style>
*{box-sizing:border-box}body{margin:0;padding:36px 24px;background:#fff3f7;color:#614152;font:14px/1.6 system-ui}main{max-width:900px;margin:auto}h1{font-size:26px;letter-spacing:-.5px;margin:0}header>p{color:#957783;margin:6px 0 22px}.controls{display:flex;align-items:center;gap:10px;flex-wrap:wrap;padding:16px;background:#fff9fc;border:1px solid #efd5e0;border-radius:16px;margin-bottom:24px}button,a{font:inherit;padding:7px 12px;border:1px solid #e8bfd1;background:white;border-radius:9px;color:#744c60;cursor:pointer;text-decoration:none}button.primary{background:#89526b;border-color:#89526b;color:white}input{width:160px;accent-color:#a7587c}output{min-width:40px;font-variant-numeric:tabular-nums}.cards{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px}section{padding:20px 18px 24px;background:#fffcfe;border:1px solid #efd8e1;border-radius:20px;box-shadow:0 6px 24px #b9809c0a}.card-head{display:flex;align-items:center;justify-content:space-between;gap:10px}h2{font-size:17px;margin:0}.card-head span{font-size:11px;color:#a58a96}section p{font-size:12px;color:#967483;margin:4px 0 20px}.stage{width:320px;height:100px;margin:auto;background:var(--stage,#fff);border-radius:12px;overflow:hidden}.stage svg{display:block}.large main{max-width:1040px}.large .cards{grid-template-columns:1fr}.large .stage{width:960px;max-width:100%;height:auto;aspect-ratio:3.2}body[data-bg="dark"]{--stage:#2d2735}body[data-bg="grid"] .stage{background:conic-gradient(#efe8ee 25%,#fff 0 50%,#efe8ee 0 75%,#fff 0) 0 0/14px 14px}footer{font-size:12px;color:#947884;margin:22px 0}@media(max-width:800px){.cards{grid-template-columns:1fr}main{max-width:440px}body{padding:22px 12px}}@media(max-width:365px){section{padding:16px 0}.stage{max-width:100%;height:auto;aspect-ratio:3.2}.card-head,section p{padding:0 12px}}
</style>
<main><header><h1>樱花之恋 · Loading</h1><p>四种用途，同一对恋人。默认按 App 实际尺寸展示。</p></header><div class="controls"><label for="progress">预览进度</label><input id="progress" type="range" min="0" max="100" step=".1" value="50"><output id="value">50%</output><button id="complete" class="primary">完成</button><button id="reset">重置</button><button id="size">放大查看</button><button id="background">切换背景</button></div><div class="cards">${markup}</div><footer>人物随真实进度靠近；业务成功后播放完成动作。背景和页面文字仅用于预览，导出的素材为透明背景。<br>v3 · 双人趴姿、直立拍照、推爱心小车、背手前倾。</footer></main><script src="vendor/lottie.min.js"></script><script>
window.animations={};window.readyCount=0;window.previewErrors=[];
for(const name of ${JSON.stringify(themes)}){
 const a=lottie.loadAnimation({container:document.getElementById(name),renderer:'svg',loop:false,autoplay:false,animationData:JSON.parse(document.getElementById('data-'+name).textContent),rendererSettings:{preserveAspectRatio:'xMidYMid meet'}});
 a.addEventListener('DOMLoaded',()=>{window.readyCount++;a.goToAndStop(50,true)});
 a.addEventListener('data_failed',e=>window.previewErrors.push(String(e)));
 window.animations[name]=a;
}
const slider=document.getElementById('progress');let completionRequest=0;
window.seek=f=>{completionRequest++;for(const a of Object.values(window.animations))a.goToAndStop(f,true)};
slider.oninput=()=>{document.getElementById('value').textContent=slider.value+'%';window.seek(Number(slider.value))};
document.getElementById('reset').onclick=()=>{slider.value=0;slider.oninput()};
document.getElementById('complete').onclick=()=>{
 slider.value=100;document.getElementById('value').textContent='100%';
 window.seek(100);const token=++completionRequest,start=performance.now();
 const tick=now=>{if(token!==completionRequest)return;const f=100+Math.min(24,(now-start)/800*24);for(const a of Object.values(window.animations))a.goToAndStop(f,true);if(f<124)requestAnimationFrame(tick)};
 requestAnimationFrame(tick);
};
document.getElementById('size').onclick=()=>{const large=document.body.classList.toggle('large');document.getElementById('size').textContent=large?'实际尺寸':'放大查看'};
let bg=0;document.getElementById('background').onclick=()=>document.body.dataset.bg=['grid','dark','light'][bg++%3];
</script></html>`);
console.log('App-size v3 preview ready');
