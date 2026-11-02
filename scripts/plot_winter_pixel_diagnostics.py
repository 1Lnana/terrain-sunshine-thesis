"""Draw saved diagnostic CSV records; no terrain or solar calculations."""
from pathlib import Path
import csv
import math
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'user_runs' / 'winter_pixel_diagnostics_001'
FONT = 'C:/Windows/Fonts/msyh.ttc'
BOLD = 'C:/Windows/Fonts/msyhbd.ttc'


def read(path):
    with path.open(encoding='utf-8') as stream:
        return list(csv.DictReader(stream))


methods = [('fixed_1min', '逐分钟参考'), ('adaptive_30min', '原方法'),
           ('solar_30min', '只调整间隔'), ('local_30min', '只做局部细化'),
           ('combined_30min', '间隔＋局部细化')]
samples = {name: read(SOURCE / name / 'samples.csv') for name, _ in methods}
psd = {name: {(r['row'], r['col']): float(r['psd_minutes'])
              for r in read(SOURCE / name / 'psd.csv')} for name, _ in methods}
errors = read(SOURCE / 'interval_errors.csv')
cases = [('162', '226', 245, 335, '三段阴影没被查询到，累计多算18分钟'),
         ('189', '213', 215, 250, '短日照没被查询到，9分钟被算成0'),
         ('162', '228', 420, 470, '采样位置改变，有些阴影被发现、有些被漏掉')]
image = Image.new('RGB', (2200, 1550), 'white')
draw = ImageDraw.Draw(image)
ink, muted, light, shade, red = '#172b40', '#536477', '#d99a12', '#28649a', '#c43838'


def text(x, y, value, size=28, color=ink, bold=False):
    draw.text((x, y), value, font=ImageFont.truetype(BOLD if bold else FONT, size), fill=color)


text(55, 25, '冬季误差来自哪里？三个真实像元的明暗状态与实际查询位置', 43, bold=True)
draw.ellipse((60, 105, 76, 121), fill=light)
text(90, 94, '查询结果：明亮', 26)
draw.ellipse((380, 105, 396, 121), fill=shade)
text(410, 94, '查询结果：阴影', 26)
draw.rectangle((700, 100, 740, 125), outline=red, width=2)
text(755, 94, '红框：有误差的积分区间；上方数字为多算（＋）或漏算（－）分钟', 26)
for index, (row, col, lo, hi, title) in enumerate(cases):
    base = 170 + index * 420
    text(55, base, f'行{row}，列{col}  |  {title}', 31, bold=True)
    x0, x1 = 530, 2110
    xpos = lambda t: x0 + (t - lo) / (hi - lo) * (x1 - x0)
    ticks = sorted({lo, hi, *range(math.ceil(lo/10)*10, hi, 10)})
    for tick in ticks:
        x = xpos(tick)
        draw.line((x, base+65, x, base+320), fill='#e5e9ee', width=1)
        label = str(tick); f = ImageFont.truetype(FONT, 22)
        draw.text((x-draw.textlength(label, font=f)/2, base+330), label, font=f, fill=muted)
    for lane, (method, label) in enumerate(methods):
        y = base + 90 + lane * 52
        text(55, y-17, f'{label}：{psd[method][(row,col)]:g}分钟', 28)
        draw.line((x0, y, x1, y), fill='#d8dfe7', width=2)
        for sample in samples[method]:
            t = float(sample['minutes_since_sunrise'])
            if (sample['row'],sample['col']) != (row,col) or not lo <= t <= hi:
                continue
            x = xpos(t); color = light if sample['state']=='1' else shade
            draw.ellipse((x-5,y-5,x+5,y+5), fill=color)
        for error in errors:
            if (error['row'],error['col'],error['method']) != (row,col,method):
                continue
            delta = float(error['error_minutes'])
            if not math.isfinite(delta):
                continue
            a,b = float(error['start_minute']),float(error['end_minute'])
            draw.rectangle((xpos(a),y-13,xpos(b),y+13), outline=red, width=2)
            value=f'{delta:+g}';f=ImageFont.truetype(FONT,21)
            draw.text(((xpos(a)+xpos(b))/2-draw.textlength(value,font=f)/2,y-39),value,font=f,fill=red)
    text(880, base+365, '距几何日出的分钟数（不是钟表时刻）', 24, muted)
text(55, 1450, '圆点是实际查询，空白处未查询；每行分钟数为该像元全天累计值。参考也只是分钟采样。', 25, muted)
text(55, 1490, '来源：user_runs/winter_pixel_diagnostics_001 中的用户实测记录；本脚本只绘图，不重新计算遮挡。', 25, muted)
output = SOURCE / 'sampling_timeline.png'
image.save(output)
print(f'TIMELINE_PLOT_SAVED={output}')
