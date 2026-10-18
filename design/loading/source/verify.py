"""Inspect exports without modifying image content."""
from pathlib import Path
from PIL import Image
import json
root=Path(__file__).resolve().parent.parent
checks=[]
for theme in ['video','image','queue','app']:
    frames=list((root/theme/'frames').rglob('*.png'))
    assert len(frames)==46,(theme,len(frames))
    states=list((root/theme/'states').glob('*.png'))
    assert len(states)==6
    for p in frames+states+[root/theme/'loading@3x.png',root/theme/'complete@3x.png']:
        with Image.open(p) as im:
            assert im.size==(960,300),(p,im.size)
            assert im.mode=='RGBA',(p,im.mode)
            alpha=im.getchannel('A')
            assert alpha.getextrema()==(0,255),(p,alpha.getextrema())
            bounds=alpha.getbbox()
            assert bounds and bounds[0]>=4 and bounds[1]>=3 and bounds[2]<=956 and bounds[3]<=296,(p,bounds)
            assert all(alpha.getpixel(q)==0 for q in [(0,0),(959,0),(0,299),(959,299)])
    doc=json.loads((root/theme/'lottie/loading.json').read_text())
    assert (doc['w'],doc['h'],doc['ip'],doc['op'],doc['fr'])==(960,300,0,125,30)
    assert len({v['ind'] for v in doc['layers']})==len(doc['layers'])
    ids={a['id'] for a in doc['assets']}
    assert all(l['refId'] in ids for l in doc['layers'])
    assert all((root/theme/'lottie'/a['u']/a['p']).is_file() for a in doc['assets'])
    embedded=json.loads((root/theme/'lottie/loading-embedded.json').read_text())
    assert all(a['e']==1 and a['p'].startswith('data:image/png;base64,') for a in embedded['assets'])
    checks.append({'theme':theme,'pngFrames':46,'states':6,'canvas':[960,300],'alpha':'RGBA, transparent corners, opaque foreground','bounds':'all rendered frames inside 4 px horizontal / 3 px vertical minimum margin','lottie':'unique layers; all images resolve; frame range verified'})
for p in (root/'shared/characters').glob('*.png'):
    with Image.open(p) as im:
        assert im.mode=='RGBA'
        assert im.getchannel('A').getextrema()[0]==0 and im.getchannel('A').getextrema()[1]>=250
        assert im.getpixel((0,0))[3]==0
report={'checks':checks,'android':'not integrated into the App; target-device acceptance remains with integrator','imageEditing':'Original RGBA character art preserved; exports only resample for target size and compose separate layers.'}
(root/'qa/export-checks.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
print(json.dumps(report,ensure_ascii=False))
