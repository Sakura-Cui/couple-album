"""Create synthetic, non-private media for the Android instrumentation test.

QA-only dependencies: Pillow and imageio-ffmpeg. Output is ignored by Git.
"""
from pathlib import Path
import subprocess

import imageio_ffmpeg
from PIL import Image, ImageDraw

root = Path(__file__).resolve().parents[1]
output = root / '.tools' / 'qa-fixtures'
output.mkdir(parents=True, exist_ok=True)
picture = Image.new('RGB', (1280, 960), '#f7e2e9')
draw = ImageDraw.Draw(picture)
draw.ellipse((820, 100, 1030, 310), fill='#f6ba81')
draw.polygon([(0, 760), (430, 260), (860, 960), (0, 960)], fill='#78998b')
draw.polygon([(470, 960), (970, 390), (1280, 710), (1280, 960)], fill='#a2b8a8')
# A valid ~36 MiB image crosses two 16 MiB upload chunk boundaries.
picture.resize((4096, 3072)).save(output / 'qa-photo.png', compress_level=0)
subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), '-y', '-f', 'lavfi', '-i',
    'testsrc2=size=480x320:rate=24', '-t', '8', '-c:v', 'libx264',
    '-pix_fmt', 'yuv420p', '-movflags', '+faststart', str(output / 'qa-video.mp4')], check=True)
