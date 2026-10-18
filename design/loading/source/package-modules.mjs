import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {THEMES,state,CHARACTER_ROLES,SPRITE_SIZES,characterObjects} from './scene.mjs';
const root=path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const descriptions={
video:'双人托腮趴姿、翘脚、胶片轨道；从两侧靠近，在中央亲吻并弹出播放爱心。',
image:'男生直立举拍立得，女生背手等待；完成时放下相机、亲脸颊。照片逐张显色。',
queue:'男生伸臂踮脚推爱心礼物小车，女生双手胸前期待；完成时小车退至旁边、男生亲脸颊。七颗心依次点亮。',
app:'复用已完成的背手前倾人物；毛玻璃胶囊与樱花节点，完成亲脸颊并弹出爱心。'
};
for(const theme of THEMES){
 const base=path.join(root,theme),r=CHARACTER_ROLES[theme];
 for(const dir of ['characters/loading','characters/complete','effects','props'])await fs.mkdir(path.join(base,dir),{recursive:true});
 for(const [key,name] of [[r.boy,'loading/boy'],[r.girlWait,'loading/girl'],[r.boyComplete,'complete/boy'],[r.girlComplete,'complete/girl']])
 await fs.copyFile(path.join(root,'shared/characters',key+'@3x.png'),path.join(base,'characters',name+'@3x.png'));
 if(r.cart)await fs.copyFile(path.join(root,'shared/characters',r.cart+'@3x.png'),path.join(base,'props/cart@3x.png'));
 for(const file of await fs.readdir(path.join(root,'shared/effects')))await fs.copyFile(path.join(root,'shared/effects',file),path.join(base,'effects',file));
 await fs.writeFile(path.join(base,'characters/placement.json'),JSON.stringify({canvas:[960,300],roles:r,spriteSizes:Object.fromEntries(Object.values(r).map(k=>[k,SPRITE_SIZES[k]])),start:characterObjects(theme,0),midpoint:characterObjects(theme,.5),at100Percent:characterObjects(theme,1),completed:characterObjects(theme,1,.8),motionSource:'../../source/scene.mjs',note:'位置、透明度使用 characterObjects；请勿把完成状态的位置作为整个进度阶段的终点。'},null,2));
 await fs.writeFile(path.join(base,'README.md'),`# ${theme} · App Loading v3
${descriptions[theme]}

推荐入口：lottie/loading-embedded.json，自包含图层，无需另拷贴图。
0–100 帧由真实进度 seek；业务成功后播放 100–124 帧一次，0.8 秒。
100% 等待态没有成功爱心。失败、取消不能触发完成段。

characters/ 是本款独立男女 PNG；props/ 是独立道具（仅队列小车）。
layers/ 是轨道、填充、装饰 PNG/SVG；effects/ 是独立特效。
source/ 是人物为位图层、轨道为矢量层的可编辑 SVG。
frames/ 为 21 张进度帧与 25 张完成帧。
states/100@3x.png 是真实 100% 等待态；complete-fx 是完成段最终状态。
画布 960×300 RGBA；建议显示 320×100 dp。适配细节见包根目录 README。
`);
}
await fs.mkdir(path.join(root,'app-ready/assets/loading'),{recursive:true});
for(const theme of THEMES)await fs.copyFile(path.join(root,theme,'lottie/loading-embedded.json'),path.join(root,'app-ready/assets/loading',theme+'.json'));
await fs.copyFile(path.join(root,'integration/SakuraLoading.kt'),path.join(root,'app-ready/SakuraLoading.kt'));
console.log('Four standalone modules and app-ready assets assembled');
