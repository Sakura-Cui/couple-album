import fs from 'node:fs/promises';
import path from 'node:path';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
const sharp=createRequire(import.meta.url)('sharp');
const root=path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const specs=[
['video-atlas',[['video-boy',0,0,768,1024],['video-girl',768,0,768,1024]]],
['image-atlas',[['image-boy',0,0,768,512],['image-boy-complete',768,0,768,512],['image-girl-wait',0,512,768,512],['image-girl-smile',768,512,768,512]]],
['queue-atlas',[['queue-boy',0,0,768,512],['queue-cart',768,0,768,512],['queue-girl-wait',0,512,768,512],['queue-girl-smile',768,512,768,512]]]
];
const result={};
for(const [atlas,cells] of specs){
 for(const [key,left,top,width,height] of cells){
 const src=path.join(root,'source/atlases',atlas+'.png');
 const cell=await sharp(src).extract({left,top,width,height}).png().toBuffer();
 const b=await sharp(cell).trim({threshold:8}).extend({top:4,bottom:4,left:4,right:4,background:{r:0,g:0,b:0,alpha:0}}).png().toBuffer();
 await fs.writeFile(path.join(root,'source/originals',key+'.png'),b);
 const m=await sharp(b).metadata();result[key]={width:m.width,height:m.height};
 }
}
await fs.writeFile(path.join(root,'source/atlas-extraction.json'),JSON.stringify(result,null,2));
console.log(result);
