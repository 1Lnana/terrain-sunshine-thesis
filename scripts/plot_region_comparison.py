"""Render verified regional CSV as a chart; never runs sunshine experiments."""
import csv
import json
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

run, out = Path(sys.argv[1]), Path(sys.argv[2])
with (run / 'summary.csv').open(encoding='utf-8-sig', newline='') as f:
    rows = list(csv.DictReader(f))
metadata = json.loads((run / 'data_metadata.json').read_text(encoding='utf-8'))
region_key = metadata.get('region', 'qinling')
region = {'huabei_plain': '华北平原', 'jianghuai_hills': '江淮丘陵', 'qinling': '秦岭山地'}.get(region_key, region_key)
config = dict(line.split('=', 1) for line in (run/'config.properties').read_text(encoding='utf-8-sig').splitlines()
              if line and not line.startswith('#'))
horizon = any(r['method'].startswith('horizon_') for r in rows)
lookup = {r['method']: r for r in rows}
local, original = lookup['guardlocal_30min'], lookup['adaptive_30min']
query_saving = 100*(1-int(local['rayCalls'])/int(original['rayCalls']))
time_saving = 100*(1-float(local['runtime_sec'])/float(original['runtime_sec']))
if not horizon:
    with (run / 'fixed_1min_r1/intervals.csv').open(encoding='utf-8-sig', newline='') as f:
        intervals = list(csv.DictReader(f))
    single = sum(r['category'] == 'SINGLE' for r in intervals)
names = {'fixed_1min': '逐分钟参考', 'fixed_5min': '固定5分钟',
         'fixed_10min': '固定10分钟', 'adaptive_30min': '原自适应',
         'guardlocal_30min': '局部细化＋补查', 'guardcombined_30min': '高度角组合＋补查',
         'horizon_1deg': '地平线1°', 'horizon_2deg': '地平线2°'}
image = Image.new('RGB', (1800, 1060), '#f0f6fc')
d = ImageDraw.Draw(image)
def text(x, y, label, size=25, color='#143757', bold=False):
    font = ImageFont.truetype('C:/Windows/Fonts/msyhbd.ttc' if bold else 'C:/Windows/Fonts/msyh.ttc', size)
    d.text((x, y), label, fill=color, font=font)

text(65, 35, region+('｜地平线与自适应：单日完整成本' if horizon else '｜六方法精度与耗时对照'), 44, bold=True)
text(65, 110, f"{config['rows']}×{config['cols']} · {config['date']} · {int(rows[0]['valid_pixels']):,}个有效像元", 27)
colors = ['#8da5ba', '#f7b35b', '#ee9354', '#4283be', '#079c79', '#479faf']
for panel, (left, right, title, field) in enumerate([
    (55, 875, '完整计算耗时：越短越快', 'runtime_sec'),
    (925, 1745, '平均绝对误差：越短越准', 'MAE_min')]):
    d.rounded_rectangle((left, 180, right, 805), radius=22, fill='white')
    text(left+25, 205, title, 30, bold=True)
    x0, width = left+245, 390
    maximum = max(float(r[field]) for r in rows)
    for i, r in enumerate(rows):
        y = 285+i*76; value=float(r[field])
        text(left+20, y, names[r['method']], 22)
        d.line((x0, y+38, x0+width, y+38), fill='#dee8ef', width=1)
        end=x0+width*value/maximum
        if value > 0: d.rectangle((x0,y+3,end,y+29),fill=colors[i])
        if horizon and panel == 0 and r['method'].startswith('horizon_'):
            # 色深部分为建表成本，位置取三次中总耗时居中的同一次，避免相加不同中位数。
            runtimes = [json.loads((run/(r['method']+f'_r{j}')/'runtime.json').read_text(encoding='utf-8')) for j in (1,2,3)]
            measured = sorted(runtimes, key=lambda v: v['compute_sec'])[len(runtimes)//2]
            split = x0+width*measured['preprocess_sec']/maximum
            d.rectangle((x0,y+3,split,y+29),fill='#375b80')
        if panel == 0:
            lo=x0+width*float(r['runtime_min_sec'])/maximum
            hi=x0+width*float(r['runtime_max_sec'])/maximum
            d.line((lo,y+16,hi,y+16), fill='#193b51', width=2)
            for x in (lo, hi): d.line((x,y+10,x,y+22),fill='#193b51',width=2)
        text(x0+width+15,y,f'{value:.3f}',23)
        if horizon and panel == 1: text(x0+width+15,y+27,f"最大{float(r['MAX_abs_error_min']):g}",18)
    text(left+25, 758, '中位数及范围／秒；深蓝部分为建表' if horizon and panel == 0 else
         '三次中位数；黑线为最小—最大范围；单位：秒' if panel == 0 else '相对本区逐分钟参考，单位：分钟', 21)
d.rounded_rectangle((55, 830, 1745, 1005), radius=22, fill='#e1f3ea')
text(85,850,'单日自适应更快、平均误差更小；地平线1°最大误差更低' if horizon else
     f'局部细化候选较原自适应：查询少约{query_saving:.0f}%，耗时少约{time_saving:.0f}%',31,bold=True)
adaptive = [lookup[m] for m in ('adaptive_30min','guardlocal_30min','guardcombined_30min')]
match = all(float(r['MAX_abs_error_min']) == 0 for r in adaptive)
guard = sum(int(r['guard_queries']) for r in adaptive)
text(85,902,'地平线总成本包含预处理；表可跨日期复用，不能据此判断多日计算胜负。' if horizon else
     f'单段日照{single:,}点；自适应累计与参考全部一致：'+('是' if match else '否')+f'；补查新增查询：{guard}。',25)
text(85,946,'这里比较的是本次基础地平线实现；分钟参考不是真值。' if horizon else
     '0误差只指累计时长一致，不代表连续真值或逐时刻状态完全一致。',23)
text(65,1022,'来源：'+run.as_posix()+'/summary.csv；原记录已核验。',19)
out.parent.mkdir(parents=True, exist_ok=True)
image.save(out)
print('PLOT_SAVED=' + str(out.resolve()))
