"""Read saved evidence and render the cumulative research gallery; never runs Java.

Pillow is used because the existing project environment has a NumPy/Matplotlib
binary mismatch. All plotted numbers come from saved CSV/JSON, not imagegen.
"""
import csv
import hashlib
import html
import json
import math
import statistics
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs' / '实验展示_2026-10-30'
W, H = 2000, 1300
NAVY, TEAL, BLUE, ORANGE, RED = '#15354b', '#008f83', '#387bb5', '#d78b35', '#cb5361'
GRAY, BG, LIGHT = '#64788a', '#eef3f6', '#dce6ec'
PALETTE = ['#9dabb7', '#e6ad68', '#d3863e', BLUE, TEAL, '#7e6bb0']
FONTS = {}
SOURCES = {}
FIGURES = []
CHECKS = []


def font(size=26, bold=False):
    key = (size, bold)
    if key not in FONTS:
        FONTS[key] = ImageFont.truetype('C:/Windows/Fonts/msyhbd.ttc' if bold else 'C:/Windows/Fonts/msyh.ttc', size)
    return FONTS[key]


def source(path):
    path = Path(path)
    if not path.is_absolute():
        path = ROOT / path
    raw = path.read_bytes()
    SOURCES[path.relative_to(ROOT).as_posix()] = hashlib.sha256(raw).hexdigest()
    return raw


def csv_rows(path):
    return list(csv.DictReader(source(path).decode('utf-8-sig').splitlines()))


def load_json(path):
    return json.loads(source(path).decode('utf-8-sig'))


def summary(name):
    run = Path('user_runs') / name
    rows = csv_rows(run / 'summary.csv')
    for r in rows:
        if 'runtime_sec' not in r:
            continue
        times = []
        for i in range(1, int(r['repeats']) + 1):
            f = ROOT / run / (r['method'] + '_r' + str(i)) / 'runtime.json'
            if f.exists():
                times.append(load_json(f)['compute_sec'])
        if times:
            assert len(times) == int(r['repeats'])
            assert abs(statistics.median(times) - float(r['runtime_sec'])) < 2e-8, (name, r['method'])
            CHECKS.append({'run': name, 'method': r['method'], 'runtime_median_matches': True})
    return {r['method']: r for r in rows}


def val(row, key='runtime_sec'):
    return float(row[key])


def label(method):
    return {'fixed_1min': '固定1分钟', 'fixed_5min': '固定5分钟', 'fixed_10min': '固定10分钟',
            'fixed_20min': '固定20分钟', 'fixed_30min': '固定30分钟', 'adaptive_30min': '原自适应V1',
            'plainlocal_30min': '普通局部补算', 'guardlocal_30min': '条件细化＋补查',
            'guardcombined_30min': '高度角组合＋补查', 'binarylocal_30min': '二分细化＋补查',
            'block16binarylocal_30min': '16格空间＋二分', 'block16fixed_1min': '16格空间＋固定1分',
            'horizon_1deg': '地平线1°', 'horizon_2deg': '地平线2°', 'solar_30min': '高度角采样',
            'local_30min': '条件局部细化', 'combined_30min': '高度角＋局部细化'}.get(method, method)


class Sheet:
    def __init__(self, title, subtitle, number):
        self.im = Image.new('RGB', (W, H), BG)
        self.d = ImageDraw.Draw(self.im)
        self.d.rectangle((0, 0, W, 176), fill=NAVY)
        self.text(55, 22, '研究证据图谱  /  ' + number + '     ·     2026.10.30', 22, '#a8cfcd')
        self.text(55, 58, title, 44, 'white', True)
        self.text(55, 122, subtitle, 23, '#d4e0e8')

    def text(self, x, y, text, size=26, color=NAVY, bold=False):
        self.d.text((x, y), str(text), fill=color, font=font(size, bold))

    def box(self, bounds, title=None):
        self.d.rounded_rectangle(bounds, radius=22, fill='white')
        if title:
            self.text(bounds[0] + 25, bounds[1] + 20, title, 29, bold=True)

    def lines(self, x, y, lines, size=24, color=NAVY, step=39):
        for i, line in enumerate(lines):
            self.text(x, y + i * step, line, size, color)

    def footer(self, sources, note):
        self.d.line((55, 1174, 1945, 1174), fill=LIGHT, width=2)
        self.text(55, 1189, note, 21, GRAY)
        for i, line in enumerate(sources):
            self.text(55, 1225 + 28 * i, line, 18, GRAY)

    def save(self, slug, title, category, sources, takeaway):
        path = OUT / (slug + '.png')
        self.im.save(path, dpi=(240, 240))
        FIGURES.append({'file': path.name, 'title': title, 'category': category,
                        'sources': sources, 'takeaway': takeaway})


def bars(s, bounds, title, labels, values, colors=None, unit='秒', ranges=None, digits=3):
    x, y, x2, y2 = bounds
    s.box(bounds, title)
    n = len(values)
    start_y = y + 94
    rowh = min(80, (y2 - start_y - 50) / max(n, 1))
    x0, maxw = x + 272, x2 - x - 427
    maximum = max([1e-12] + list(values) + ([r[1] for r in ranges] if ranges else [])) * 1.05
    for i, (name, value) in enumerate(zip(labels, values)):
        yy = start_y + i * rowh
        s.text(x + 22, yy - 2, name, 22)
        s.d.rounded_rectangle((x0, yy + 1, x0 + maxw, yy + 26), radius=5, fill='#f0f4f6')
        end = x0 + maxw * value / maximum
        color = colors[i] if colors else PALETTE[i % len(PALETTE)]
        if value > 0:
            s.d.rounded_rectangle((x0, yy + 1, max(x0 + 2, end), yy + 26), radius=5, fill=color)
        if ranges:
            lo, hi = [x0 + maxw * t / maximum for t in ranges[i]]
            s.d.line((lo, yy + 14, hi, yy + 14), fill=NAVY, width=2)
            for xx in [lo, hi]:
                s.d.line((xx, yy + 8, xx, yy + 20), fill=NAVY, width=2)
        s.text(x2 - 140, yy - 4, f'{value:,.{digits}f}', 24, color, True)
    s.text(x + 24, y2 - 39, unit, 19, GRAY)


def table(s, bounds, title, headers, rows, widths=None, size=23, highlights=()):
    x, y, x2, y2 = bounds
    s.box(bounds, title)
    widths = widths or [1 / len(headers)] * len(headers)
    xx = [x + 23]
    for width in widths[:-1]:
        xx.append(xx[-1] + (x2 - x - 46) * width)
    rowh = min(63, (y2 - y - 125) / max(1, len(rows)))
    for j, name in enumerate(headers):
        s.text(xx[j], y + 77, name, size - 1, GRAY)
    for i, row in enumerate(rows):
        yy = y + 127 + rowh * i
        if i in highlights:
            s.d.rounded_rectangle((x + 10, yy - 5, x2 - 10, yy + rowh - 5), radius=7, fill='#e6f4f0')
        for j, item in enumerate(row):
            s.text(xx[j], yy, item, size, TEAL if i in highlights else NAVY, j == 0)


def lineplot(s, bounds, title, xs, series, xlabels, unit, colors=None, y_max=None):
    x, y, x2, y2 = bounds
    s.box(bounds, title)
    px, py, pw, ph = x + 100, y + 92, x2 - x - 150, y2 - y - 190
    ymax = y_max or max(max(v) for v in series.values()) * 1.15 or 1
    for i in range(5):
        yy = py + ph * i / 4
        s.d.line((px, yy, px + pw, yy), fill=LIGHT, width=1)
        s.text(x + 18, yy - 15, f'{ymax*(1-i/4):.1f}', 19, GRAY)
    points_x = [px + i * pw / max(1, len(xs) - 1) for i in range(len(xs))]
    for xx, lab in zip(points_x, xlabels):
        width = s.d.textlength(lab, font=font(21))
        label_x = min(x2 - 20 - width, max(x + 20, xx - width / 2))
        s.text(label_x, py + ph + 12, lab, 21, GRAY)
    for j, (name, values) in enumerate(series.items()):
        col = colors[j] if colors else PALETTE[j % len(PALETTE)]
        pts = [(xx, py + ph * (1 - value / ymax)) for xx, value in zip(points_x, values)]
        s.d.line(pts, fill=col, width=4)
        for xx, yy in pts:
            s.d.ellipse((xx - 6, yy - 6, xx + 6, yy + 6), fill=col)
        s.text(x + 25 + j * 170, y2 - 43, name, 20, col)
    s.text(x2 - 125, y + 27, unit, 21, GRAY)


def ranges(rows):
    return [(float(r['runtime_min_sec']), float(r['runtime_max_sec'])) for r in rows]


def draw_binary(data):
    s = Sheet('二分法对照：速度收益与精度边界一起看', '秦岭冬季 · 128×128 · 同批3次正式重复 · 三种时间规则，共用原空间核心', '01')
    keys = ['plainlocal_30min', 'guardlocal_30min', 'binarylocal_30min']
    rr = [data[k] for k in keys]
    names = ['普通局部补算', '条件细化＋补查', '二分细化＋补查']
    bars(s, (45, 205, 980, 610), '计算时间：三次中位数与范围', names, [val(r) for r in rr], [ORANGE, BLUE, TEAL], ranges=ranges(rr))
    bars(s, (1010, 205, 1955, 610), '太阳可见性查询次数', names, [val(r, 'rayCalls') / 10000 for r in rr], [ORANGE, BLUE, TEAL], unit='万次；同方法三次计数一致', digits=2)
    table(s, (45, 640, 1210, 1105), '精度同时报告，不能只看平均误差', ['时间方法', 'MAE / 分', 'MAX / 分', '>1分像元'],
          [[names[i], f"{val(r,'MAE_min'):.6f}", f"{val(r,'MAX_abs_error_min'):g}", str(round(val(r,'error_gt1_ratio') * val(r,'valid_pixels')))] for i, r in enumerate(rr)], [.4, .23, .19, .18], highlights=(2,))
    s.box((1240, 640, 1955, 1105), '本批能得到的结论')
    g, b = data['guardlocal_30min'], data['binarylocal_30min']
    s.lines(1270, 735, [f"二分较条件细化省时 {100*(1-val(b)/val(g)):.2f}%", f"查询减少 {100*(1-val(b,'rayCalls')/val(g,'rayCalls')):.2f}%", '二分与条件细化：全部PSD相同', '普通局部补算：少1个超差像元', '三者MAX仍为18分钟', '结论仅限本区本日，不证明原创'], 25, step=48)
    p = 'user_runs/qinling_binary_local_001/summary.csv'
    s.footer([p], '黑线为正式重复最小—最大范围；fixed1是条件参考，不是真值。')
    s.save('01_二分法对照', '二分法与两种局部规则', '时间方法', [p], '二分与条件细化PSD相同，本批查询少10.54%、中位省时6.69%；MAX18分钟保留。')


def draw_terrain(sets):
    s = Sheet('三类地形：同一春季日期的时间方法对照', '2023-03-21 · 每区128×128 · 90米DEM · 每个地区内部同批比较，跨地区不当作同批计时', '02')
    keys = ['fixed_1min', 'fixed_5min', 'fixed_10min', 'adaptive_30min', 'guardlocal_30min', 'guardcombined_30min']
    sources = []
    for i, (name, run, data) in enumerate(sets):
        x = 45 + i * 650
        rr = [data[k] for k in keys]
        bars(s, (x, 205, x + 610, 805), name + '｜计算中位秒', ['固定1分', '固定5分', '固定10分', '原V1', '条件细化', '高度角组合'], [val(r) for r in rr], PALETTE, digits=2)
        g, v = data['guardlocal_30min'], data['adaptive_30min']
        s.text(x + 20, 835, f"条件细化较V1省时 {100*(1-val(g)/val(v)):.2f}%", 25, TEAL, True)
        s.text(x + 20, 880, 'V1 / 条件细化 / 组合：', 23)
        s.text(x + 20, 920, '累计PSD均与本区fixed1一致', 23, TEAL)
        s.text(x + 20, 977, f"固定5分 MAE {val(data['fixed_5min'],'MAE_min'):.3f} 分", 22, GRAY)
        s.text(x + 20, 1017, f"固定10分 MAE {val(data['fixed_10min'],'MAE_min'):.3f} 分", 22, GRAY)
        sources.append('user_runs/' + run + '/summary.csv')
    s.footer(sources[:2], '三地春季结果不代表全年精度；这里没有二分跨地区或空间筛选跨地区结果。江淮来源见展示页。')
    s.save('02_不同地形春季对照', '山区、平原、丘陵的春季对照', '不同地形', sources, '三个地区的春季累计PSD均与条件参考一致；空间筛选和二分法的跨区结论尚不能由此推出。')


def draw_seasons(data_by_day):
    s = Sheet('同一秦岭地区：换季后，误差表现会改变', '取自同一三日实验版本 · 春分 / 夏至 / 冬至 · 不把春夏的零累计误差外推到冬季', '03')
    keys = ['adaptive_30min', 'guardlocal_30min', 'guardcombined_30min']
    names = ['原V1', '条件细化＋补查', '高度角组合＋补查']
    sources = []
    for i, (day, title, data) in enumerate(data_by_day):
        x = 45 + i * 650
        bars(s, (x, 205, x + 610, 660), title + '｜查询计算秒', names, [val(data[k]) for k in keys], [BLUE, TEAL, '#7e6bb0'], digits=3)
        table(s, (x, 690, x + 610, 1110), '相对当日本区fixed1', ['方法', 'MAX / 分', '>1分点'],
              [[names[j], f"{max(0,val(data[k],'MAX_abs_error_min')) if val(data[k],'MAX_abs_error_min')>1e-9 else 0:g}", str(round(val(data[k],'error_gt1_ratio')*val(data[k],'valid_pixels')))] for j,k in enumerate(keys)], [.5,.25,.25], size=21)
        sources.append('user_runs/qinling_three_day_001/day_' + day + '/summary.csv')
    s.footer(sources[:2], '累计时长一致不证明所有状态转换都被找到；冬季已知隐藏阴影案例保留。冬季来源见展示页。')
    s.save('03_秦岭三季对照', '同版本的春夏冬表现', '季节与复用', sources, '春夏累计结果与参考一致；冬季条件细化MAX18分钟，组合MAX9分钟，收益与误差需要共同判断。')


def draw_spatial(data, pair_rows):
    s = Sheet('空间分块：为什么最终保留16×16？', '秦岭冬季参数敏感性 · 原方法＋4 / 8 / 16 / 32 / 64，同批比较且每次建表计入', '04')
    sizes = [4, 8, 16, 32, 64]
    rr = [data['block%dbinarylocal_30min' % n] for n in sizes]
    bars(s, (45, 205, 980, 790), '计算中位秒与正式重复范围', ['原空间'] + [str(n)+'×'+str(n) for n in sizes], [val(data['binarylocal_30min'])]+[val(r) for r in rr], ['#9dabb7', ORANGE, BLUE, TEAL, '#7e6bb0', RED], ranges=ranges([data['binarylocal_30min']]+rr))
    samples = [val(r, 'spatialSamples')/1000000 for r in rr]
    tests = [float(next(p['block_tests'] for p in pair_rows if int(p['block_size'])==n))/1000000 for n in sizes]
    lineplot(s, (1010, 205, 1955, 790), '两种工作量随块宽变化', sizes, {'实际插值':samples, '块检查':tests}, [str(n) for n in sizes], '百万次', [TEAL, ORANGE])
    s.box((45, 820, 1955, 1125), '本批参数选择的依据')
    s.lines(80, 897, ['4格：插值最少，但块检查约3774万次；64格：块检查最少，但插值约4920万次。',
                      '16格：含建表中位1.396秒，比同批原空间省59.70%；32格比16格慢7.51%。',
                      '五种块宽全部保持原结果，且三轮均更快。保留16，只称五个候选中的选择。',
                      '块宽控制排除单元大小；原90米DEM、50米采样位置和10公里搜索范围均不变。'], 26, step=47)
    sources = ['user_runs/qinling_spatial_sensitivity_001/summary.csv', 'user_runs/qinling_spatial_sensitivity_001/spatial_repeat_comparison.csv']
    s.footer(sources, '18份PSD回归一致、样本守恒；五点试验不证明全局最优或跨地形最优。')
    s.save('04_空间块宽敏感性', '4—64格参数敏感性', '空间筛选', sources, '块越小不一定越快；16格在五个候选中总成本最低，继续固定16做迁移验证。')


def draw_ablation(data, summer, winter):
    s = Sheet('时间与空间各贡献多少？四组消融拆开看', '秦岭128冬季四组同批 · 空间索引每run独立建立 · 组合成本比不等于等精度加速', '05')
    keys = ['fixed_1min','block16fixed_1min','binarylocal_30min','block16binarylocal_30min']
    names = ['原空间＋固定1分','16格＋固定1分','原空间＋二分','16格＋二分']
    rr = [data[k] for k in keys]
    bars(s, (45,205,980,780), '四组含建表中位成本', names,[val(r) for r in rr],['#9dabb7',BLUE,ORANGE,TEAL],ranges=ranges(rr))
    table(s,(1010,205,1955,780),'计算量和精度边界',['方法','射线 / 万','插值 / 亿','MAX / 分'],
          [[names[i],f"{val(r,'rayCalls')/1e4:.2f}",f"{val(r,'spatialSamples')/1e8:.3f}", '0*' if i<2 else '18'] for i,r in enumerate(rr)], [.4,.21,.21,.18],size=22,highlights=(3,))
    s.box((45,810,1955,1135),'结果解释与冻结16格的换季验证')
    ratios=[val(data['fixed_1min'])/val(data['block16fixed_1min']),val(data['binarylocal_30min'])/val(data['block16binarylocal_30min'])]
    saving_w=100*(1-val(winter['block16binarylocal_30min'])/val(winter['binarylocal_30min']))
    saving_s=100*(1-val(summer['block16binarylocal_30min'])/val(summer['binarylocal_30min']))
    s.lines(80,885,[f'固定1分上叠加空间：成本比 {ratios[0]:.3f} 倍；二分时间上叠加空间：{ratios[1]:.3f} 倍。',
                    f"组合相对原固定1分成本比 {val(data['fixed_1min'])/val(data['block16binarylocal_30min']):.2f} 倍，但MAX18分钟保留。",
                    f'冻结16格另批配对：秦岭冬季省 {saving_w:.2f}%；秦岭夏季省 {saving_s:.2f}%。两批各自配对计算。',
                    '夏季两空间累计PSD相同；这支持同区换季，华北 / 江淮的空间筛选仍待验证。'],25,step=47)
    sources=['user_runs/qinling_spatial_ablation_001/summary.csv','user_runs/qinling_spatial_block_001/summary.csv','user_runs/qinling_spatial_summer_001/summary.csv']
    s.footer(sources[:2],'同一时间法的两空间结果一致。*固定法summary约1e-12分钟为浮点舍入，CSV与参考一致；夏季来源见页面。')
    s.save('05_时空四组消融与换季','时空消融与空间换季收益','空间筛选',sources,'空间在固定法和二分法上均有效；组合有时间误差，不能将32.76倍称为等精度加速。')


def draw_scale(data):
    s=Sheet('512×512：速度接近，不代表误差也接近','秦岭冬季 · 262,144像元 · 固定5 / 10 / 30分钟与条件细化同批计时', '06')
    keys=['fixed_5min','fixed_10min','fixed_30min','guardlocal_30min'];rr=[data[k] for k in keys];names=[label(k) for k in keys]
    bars(s,(45,205,980,750),'计算秒：中位数与范围',names,[val(r) for r in rr],[ORANGE,BLUE,'#7e6bb0',TEAL],ranges=ranges(rr))
    bars(s,(1010,205,1955,750),'平均绝对误差 MAE',names,[val(r,'MAE_min') for r in rr],[ORANGE,BLUE,'#7e6bb0',TEAL],unit='分钟；相对512本区fixed1',digits=4)
    table(s,(45,780,1225,1130),'尾部误差与超差范围',['方法','MAX / 分','>1分像元','查询 / 万'],
          [[names[i],f"{val(r,'MAX_abs_error_min'):g}",f"{round(val(r,'error_gt1_ratio')*val(r,'valid_pixels')):,}",f"{val(r,'rayCalls')/1e4:.1f}"] for i,r in enumerate(rr)], [.39,.18,.23,.2],size=23,highlights=(3,))
    s.box((1255,780,1955,1130),'规模结论的边界')
    s.lines(1280,860,['条件细化与固定10分成本接近', 'MAE明显较低，但MAX仍18分钟', '固定5分MAX为7分钟，更小', '0.17%时间差不能作稳定排序', '本图是时间方法；空间512未完成'],24,step=44)
    p='user_runs/qinling_512_fixed_local_001/summary.csv'
    s.footer([p],'未将历史单次fixed1耗时用作同批加速比分母；此图没有精确算法峰值内存结论。')
    s.save('06_512规模精度成本','512规模下的精度—成本','规模验证',[p],'与固定10分近似成本下平均误差更低，但固定5分的MAX更小，保留误差取舍。')


def draw_horizon(single, multi, days):
    s=Sheet('地平线预计算：必须把建表和多日复用一起看','自建1° / 2°离散地平线对照，不代表HORAYZON等成熟工具；三日共用一次表', '07')
    keys=['horizon_1deg','horizon_2deg','adaptive_30min','guardlocal_30min','guardcombined_30min']
    lineplot(s,(45,205,1080,810),'同一批累计1 / 2 / 3日成本',[1,2,3],
             {n:[float(multi[k]['day%d_total_sec'%d]) for d in [1,2,3]] for k,n in zip(keys,['地平线1°','地平线2°','原V1','条件细化','组合'])},['春','春＋夏','春＋夏＋冬'],'秒',['#7e6bb0',ORANGE,BLUE,TEAL,RED])
    table(s,(1110,205,1955,810),'精度和建表载荷也要看',['方法','三日MAX','表载荷MiB'],
          [[label(k),f"{max(val(day[k],'MAX_abs_error_min') for _,_,day in days):g}",f"{float(multi[k]['table_payload_bytes'])/2**20:.1f}"] for k in keys],[.45,.25,.3],size=22)
    s.box((45,840,1955,1130),'单日与多日结论')
    s.lines(80,915,[f"单日另批：地平线1° {val(single['horizon_1deg']):.3f}秒 / MAX2分；条件细化 {val(single['guardlocal_30min']):.3f}秒 / MAX18分。",
                    '三日建表真实复用后，当前这3天仍未出现地平线完整成本反超；不能外推更多日期。',
                    '地平线1°的最大误差更小；直接时间法的平均误差和成本更低，指标之间存在取舍。',
                    '累计1/2/3日各自取三次中位；表数组载荷不是进程峰值内存。'],25,step=46)
    sources=['user_runs/qinling_horizon_001/summary.csv','user_runs/qinling_three_day_001/summary.csv']
    s.footer(sources,'三日顺序春→夏→冬；包含一次建表，加载和写出单列，不称完整工作流墙钟时间。')
    s.save('07_地平线单日与三日复用','地平线建表及三日复用','季节与复用',sources,'三日复用已经完成；本批直接时间法仍较快，地平线1°的最大误差更低。')


def draw_modules(summer, winter, guard):
    s=Sheet('早期模块与保护补查：这些实验也保留在证据链里','前期模块拆分与后续受控补查是不同批次；各面板只在自身批次内比较', '08')
    keys=['adaptive_30min','solar_30min','local_30min','combined_30min']
    names=['原V1','高度角采样','条件局部细化','高度角＋局部']
    bars(s,(45,205,980,760),'夏季早期模块：计算中位秒',names,[val(summer[k]) for k in keys],[BLUE,ORANGE,TEAL,'#7e6bb0'],ranges=ranges([summer[k] for k in keys]))
    table(s,(1010,205,1955,760),'冬季早期模块：误差暴露',['方法','MAE / 分','MAX / 分','>1分点'],
          [[names[i],f"{val(winter[k],'MAE_min'):.6f}",f"{val(winter[k],'MAX_abs_error_min'):g}",str(round(val(winter[k],'error_gt1_ratio')*val(winter[k],'valid_pixels')))] for i,k in enumerate(keys)],[.36,.25,.2,.19],size=22)
    table(s,(45,790,1210,1135),'同批恢复全暗补查后的变化',['候选','MAE / 分','MAX / 分','>1分点'],
          [[label(k),f"{val(guard[k],'MAE_min'):.6f}",f"{val(guard[k],'MAX_abs_error_min'):g}",str(round(val(guard[k],'error_gt1_ratio')*val(guard[k],'valid_pixels')))] for k in ['local_30min','guardlocal_30min','combined_30min','guardcombined_30min']],[.41,.23,.18,.18],size=21)
    s.box((1240,790,1955,1135),'版本解释')
    s.lines(1265,868,['春夏的良好结果不能覆盖冬季问题', '补查恢复部分短日照，未消除隐藏阴影', '恢复旧保护不自动构成创新', '此处早期local没有后来guard保护', '不拿跨批次时间计算模块收益'],23,step=45)
    sources=['user_runs/qinling_modules_001/summary.csv','user_runs/qinling_winter_001/summary.csv','user_runs/qinling_guard_comparison_001/summary.csv']
    s.footer(sources[:2],'同名模块版本与补查有区别；后续补查来源见页面。本图用于解释方法演进，不宣布贡献验收。')
    s.save('08_模块演进与补查','模块演进与补查证据','时间方法',sources,'早期模块、冬季反例与保护补查均保留；不同版本不混称同一最终方法。')


def terrain_color(t):
    anchors=[(0,(31,101,116)),(.35,(115,166,129)),(.68,(218,203,155)),(1,(148,110,91))]
    for (a,c),(b,d) in zip(anchors,anchors[1:]):
        if t<=b:
            f=max(0,min(1,(t-a)/(b-a)))
            return tuple(round(c[i]+f*(d[i]-c[i])) for i in range(3))
    return anchors[-1][1]


def draw_terrain_maps(sets):
    s=Sheet('三类地形长什么样？这里用真实DEM接收区','显示各区128×128接收区高程；每幅各自拉伸色标，数值尺度明确标注，图上方为投影网格北', '09')
    sources=[]
    for i,(name,folder) in enumerate(sets):
        meta=load_json(Path('user_runs')/folder/'metadata.json')
        raw=source(Path('user_runs')/folder/'dem.asc').decode('utf-8-sig').splitlines()
        grid=[[float(v) for v in line.split()] for line in raw[6:] if line.strip()]
        roi=meta['roi'];r0,c0=roi['row_start'],roi['col_start'];height,width=roi['rows'],roi['cols']
        vals=[grid[r][c] for r in range(r0,r0+height) for c in range(c0,c0+width)];lo,hi=min(vals),max(vals)
        im=Image.new('RGB',(width,height));im.putdata([terrain_color((v-lo)/(hi-lo or 1)) for v in vals]);im=im.resize((520,520),Image.Resampling.NEAREST)
        x=45+i*650;s.box((x,205,x+610,1135),name);s.im.paste(im,(x+45,300))
        s.text(x+45,265,'网格北 ↑     原始90米网格',21,GRAY)
        for j in range(520):s.d.line((x+45+j,845,x+45+j,869),fill=terrain_color(j/519),width=1)
        s.text(x+45,883,f'{lo:.1f} m',22);s.text(x+400,883,f'{hi:.1f} m',22)
        stats=meta.get('terrain_statistics',{});s.text(x+30,958,f'接收区极差 {hi-lo:.2f} 米',26,TEAL,True)
        if 'slope_median_deg' in stats:s.text(x+30,1008,f"坡度中位 {stats['slope_median_deg']:.3f}°",24)
        s.text(x+30,1060,'本图是真实高程，不是示意山形',21,GRAY)
        sources.extend(['user_runs/'+folder+'/dem.asc','user_runs/'+folder+'/metadata.json'])
    s.footer(['三地区输入来源和完整SHA-256见 data_sources.json；读取已保存数据，无新转换或地形计算。'],'不同色标不可只凭颜色深浅比较高差；地形类别指当前样区，不代表整个地区。')
    s.save('09_真实三类地形DEM','三个实际样区的DEM','不同地形',sources,'直接展示实际高程与各自色标，避免用示意图冒充地形数据。')


def draw_error_maps():
    s=Sheet('平均误差很小，为什么仍要保留18分钟问题？','秦岭128冬季真实逐像元累计误差；正值＝多算日照，负值＝少算；稀疏点另加圈标示位置', '10')
    reference=csv_rows(Path('user_runs/qinling_winter_001/fixed_1min_r1/psd.csv'))
    # Winter's conditional reference is reused by later outputs; align explicit row/col keys.
    ref={(r['row'],r['col']):float(r['psd_minutes']) for r in reference}
    sources=['user_runs/qinling_winter_001/fixed_1min_r1/psd.csv']
    cases=[('原V1','qinling_tradeoff_001','adaptive_30min'),('二分＋补查','qinling_binary_local_001','binarylocal_30min'),('高度角组合＋补查','qinling_tradeoff_001','guardcombined_30min')]
    for i,(name,run,method) in enumerate(cases):
        path=Path('user_runs')/run/(method+'_r1')/'psd.csv';rr=csv_rows(path);errors=[(int(r['row']),int(r['col']),float(r['psd_minutes'])-ref[(r['row'],r['col'])]) for r in rr]
        assert len(rr)==16384 and set(ref)=={(r['row'],r['col']) for r in rr}
        r0=min(r for r,c,e in errors);c0=min(c for r,c,e in errors)
        img=Image.new('RGB',(128,128),'#e6edf2');pd=ImageDraw.Draw(img)
        for r,c,e in errors:
            if abs(e)>1e-7:
                mix=min(1,abs(e)/18);base=(203,83,97) if e>0 else (56,123,181)
                color=tuple(round(235+(v-235)*mix) for v in base);pd.point((c-c0,r-r0),fill=color)
        x=45+i*650;s.box((x,205,x+610,1135),name);s.im.paste(img.resize((512,512),Image.Resampling.NEAREST),(x+49,300))
        for r,c,e in errors:
            if abs(e)>1:
                xx=x+49+(c-c0+.5)*4;yy=300+(r-r0+.5)*4;s.d.ellipse((xx-9,yy-9,xx+9,yy+9),outline=RED if e>0 else BLUE,width=2)
        ee=[abs(e) for _,_,e in errors];s.text(x+25,850,f'MAE  {statistics.mean(ee):.6f} 分钟',26)
        s.text(x+25,901,f'MAX  {max(ee):g} 分钟',29,RED,True)
        s.text(x+25,957,f'误差 >1分：{sum(e>1 for e in ee)} / 16,384 点',25)
        s.text(x+25,1012,'圈仅放大位置，不代表影响范围',21,GRAY)
        s.text(x+25,1054,'灰底＝累计误差0；统一±18分尺度',21,GRAY)
        sources.append(path.as_posix())
    s.footer(['来源为各方法_r1/psd.csv与秦岭冬季fixed1；完整路径见展示页。'],'空间筛选保持二分结果，不会消除这些时间误差；日累计误差0也不证明状态序列一致。')
    s.save('10_冬季真实误差空间分布','冬季误差的真实空间分布','误差与边界',sources,'平均误差小不意味着最坏像元误差小；稀疏的18分钟问题继续显式报告。')


def draw_fixed_tradeoff(data):
    s=Sheet('128冬季固定步长：快、平均准、最坏准并不总一致', '同批固定5 / 10 / 20 / 30分钟与V1、条件细化、组合比较；二分实验另见图01', '11')
    keys=['fixed_5min','fixed_10min','fixed_20min','fixed_30min','adaptive_30min','guardlocal_30min','guardcombined_30min']
    names=['固定5分','固定10分','固定20分','固定30分','原V1','条件细化＋补查','高度角组合＋补查']
    rr=[data[k] for k in keys]
    colors=[ORANGE,'#b78a59','#a48791','#a494b5',BLUE,TEAL,'#7e6bb0']
    bars(s,(45,205,980,920),'计算中位秒与正式范围',names,[val(r) for r in rr],colors,ranges=ranges(rr))
    bars(s,(1010,205,1955,920),'平均绝对误差 MAE',names,[val(r,'MAE_min') for r in rr],colors,unit='分钟；相对本区冬季fixed1',digits=4)
    s.box((45,950,1955,1135),'看平均误差的同时，保留MAX的差别')
    s.lines(80,1020,[f"固定5 / 10 / 20 / 30分的MAX：{' / '.join(str(int(val(data[k],'MAX_abs_error_min'))) for k in keys[:4])} 分钟。",
                       '条件细化MAE很低，但MAX18分；固定5分MAX6分更小。组合MAX9分，仍有计算成本。'],25,step=46)
    p='user_runs/qinling_tradeoff_001/summary.csv'
    s.footer([p],'横条为中位数，黑线为最小—最大范围；没有把另一批二分耗时混进本批排名。')
    s.save('11_128冬季固定步长取舍','128冬季固定步长的取舍','时间方法',[p],'方法需要同时按运行成本、MAE与MAX评估，不能只挑一个更好看的指标。')


def draw_neighbor():
    p='user_runs/neighbor_boundary_readonly_001/summary.json';j=load_json(p)
    native=j['native_minutes_since_own_sunrise']['all_unique_8_neighbors'];s=Sheet('邻点很像，为什么还不能直接复制日照边界？','已有512分钟参考中央128切片的只读统计；不是前面原128样区，也没有运行邻点复用算法', '12')
    bars(s,(45,205,1040,800),'相似性条件越严格，满足比例越低',
         ['窗口数量相同','首边界差≤5分','末边界差≤5分','同窗数且两端≤5分'],
         [j['unique_8_neighbor_pairs']['same_windows_pct'],native['first']['le5_pct'],native['last']['le5_pct'],native['same_windows_both_le5_pct']],
         [BLUE,ORANGE,'#7e6bb0',TEAL],unit='%；第一项分母64,770对，其余双有光63,932对',digits=2)
    table(s,(1070,205,1955,800),'边界差的分布，不能只看中位数',['边界','中位 / 分','P95 / 分','最大 / 分'],
          [[title,str(native[key]['median']),str(native[key]['p95']),str(native[key]['max'])] for title,key in [('首个明边界','first'),('末个明边界','last')]],[.37,.22,.22,.19],size=23)
    s.box((45,830,1955,1135),'只读统计的结论与范围')
    s.lines(80,905,['时间口径：各像元日出起算的分钟轴；地方太阳时另一套统计保留在原JSON，不混报。',
                    '相同窗口数量并不代表边界位置接近，更不代表内部每个转换边界一致。',
                    '这里只保存首末边界和窗口数，没有完整内部转换，也没有验证/回退成本或加速结论。',
                    '因此暂不实施“直接复制邻点”，保留为论文限制和后续工作分析。'],25,step=46)
    s.footer([p],'这是离线统计证据，不是新地形查询、复用安全证书或性能实验。')
    s.save('12_邻点相似性与复用边界','邻点复用的限制分析','误差与边界',[p],'同窗数约99%不等于可直接复用；严格边界接近的比例低，尚无安全性或加速证据。')


def draw_overview(binary, spatial, ablation):
    s=Sheet('潜在日照快速计算｜累计实验展示总览','由当前工作区原始记录自动绘制 · 汇总本聊天与其他聊天保存的实验 · 历史海报保留', '00')
    cards=[('时间方法','二分与条件细化PSD相同','查询减少10.54% / 本批省时6.69%',BLUE),
           ('空间筛选','五块宽比较，继续保留16格','本批含建表省59.70% / 结果一致',TEAL),
           ('独立地形','秦岭、华北、江淮春季对照','时间候选累计PSD与本区参考一致',ORANGE),
           ('规模与限制','512时间对照已完成','MAX18分仍保留 / 空间512待验证',RED)]
    for i,(title,a,b,col) in enumerate(cards):
        x=45+(i%2)*975;y=205+(i//2)*218
        s.box((x,y,x+945,y+190));s.d.rounded_rectangle((x+20,y+24,x+27,y+162),radius=3,fill=col)
        s.text(x+50,y+20,title,25,col,True);s.text(x+50,y+64,a,31,bold=True);s.text(x+50,y+121,b,24,GRAY)
    s.box((45,665,1210,1135),'已经有证据的几条主线')
    s.lines(80,745,['01  时间：普通局部 / 条件细化 / 二分受控比较', '02  地形：山区 / 平原 / 丘陵，同日期独立样区', '03  季节：同版本春夏冬，冬季尾部误差保留', '04  空间：块宽敏感性、四组消融、秦岭换季', '05  对照：单日地平线、三日真实复用、512规模', '06  模型：真实DEM及逐像元误差图，追溯原数据'],26,step=55)
    s.box((1240,665,1955,1135),'尚不能写成结论的内容')
    s.lines(1270,745,['不能把fixed1称为真值', '不能称16格为全局最优', '不能把组合32.76倍称等精度', '不能由春季零误差推出全年安全', '华北 / 江淮空间迁移尚未完成', '创新性及阶段C整体仍待复审'],25,step=55)
    s.footer(['所有图片的源CSV/JSON及SHA-256随展示页交付；图中数据均为已保存实验记录。'],'时间按各批内部比较；没有代跑Java、转换数据、补造缺失结果或把示意图当实测。')
    s.save('00_累计实验总览','累计实验总览','总览',[], '先看总览，再按主题查看二分法、地形、空间、季节、规模与误差证据。')


def build_html(catalog):
    figures=sorted(FIGURES,key=lambda f:f['file'])
    cards=[]
    for f in figures:
        links=' · '.join('<a href="../../'+html.escape(p)+'">'+html.escape(p)+'</a>' for p in f['sources'])
        cards.append('<article data-theme="'+f['category']+'"><div class="head"><span class="tag">'+f['category']+'</span><h2>'+f['title']+'</h2><p>'+f['takeaway']+'</p></div><a href="'+f['file']+'" target="_blank"><img loading="lazy" src="'+f['file']+'" alt="'+f['title']+'"></a><details><summary>数据来源与高清图</summary><p>'+links+'</p><a download href="'+f['file']+'">下载2000像素PNG</a></details></article>')
    catalog_rows=[]
    for item in catalog:
        for r in item['rows']:
            catalog_rows.append('<tr><td><a href="../../'+item['path']+'">'+html.escape(item['path'].replace('user_runs/',''))+'</a></td><td>'+html.escape(r['method'])+'</td><td>'+html.escape(r.get('runtime_sec',r.get('day3_total_sec','—')))+'</td><td>'+html.escape(r.get('MAE_min','—'))+'</td><td>'+html.escape(r.get('MAX_abs_error_min','—'))+'</td><td>'+html.escape(r.get('valid_pixels','—'))+'</td></tr>')
    body='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>论文实验｜累计证据图谱</title>
<style>:root{--ink:#15354b;--muted:#64788a;--green:#008f83}*{box-sizing:border-box}body{margin:0;background:#eef3f6;color:var(--ink);font-family:"Microsoft YaHei",sans-serif}header{background:#15354b;color:white;padding:52px max(24px,calc((100vw - 1250px)/2));}header small{color:#a8cfcd;letter-spacing:2px}h1{font-size:38px;margin:15px 0}header p{color:#d4e0e8;line-height:1.9}main{max-width:1350px;margin:auto;padding:24px}nav{position:sticky;top:0;z-index:4;background:#eef3f6ed;backdrop-filter:blur(10px);padding:13px 0;display:flex;gap:9px;flex-wrap:wrap}button{font:inherit;border:1px solid #cedce4;background:white;border-radius:20px;padding:9px 18px;color:var(--ink);cursor:pointer}button.active{background:var(--green);border-color:var(--green);color:white}article{background:white;border-radius:18px;overflow:hidden;margin:22px 0;box-shadow:0 6px 24px #15354b08}.head{padding:25px 30px 10px}.head h2{font-size:25px;margin:10px 0}.head p{color:var(--muted);line-height:1.8}.tag{font-size:13px;color:var(--green);background:#e6f4f0;border-radius:12px;padding:4px 11px}img{width:100%;display:block;height:auto}details{padding:20px 30px;overflow-wrap:anywhere}summary{cursor:pointer;color:var(--green)}a{color:#207897}p{line-height:1.8}.notice{background:#fff4e3;border-left:4px solid #d78b35;padding:16px 22px;border-radius:10px}table{border-collapse:collapse;width:100%;font-size:13px}td,th{padding:11px 12px;border-bottom:1px solid #e5edf1;text-align:left}th{background:#f3f7f9;position:sticky;top:0}input{padding:12px;border:1px solid #c8d7df;border-radius:8px;width:100%;margin:15px 0}.scroll{max-height:650px;overflow:auto}footer{color:var(--muted);padding:35px 0}article[hidden]{display:none}@media(max-width:650px){h1{font-size:29px}main{padding:12px}.head{padding:20px}nav{position:static}}@media print{nav,button,input{display:none}article{break-inside:avoid;box-shadow:none}article[hidden]{display:block}header{padding:25px}main{max-width:none;padding:0}}</style>
<header><small>RESEARCH EVIDENCE ATLAS · 2026.10.30</small><h1>潜在日照快速计算 · 累计实验图谱</h1><p>二分法与近邻规则 / 不同地形 / 季节与地平线复用 / 空间块宽与消融 / 512规模 / 真实误差图<br>本聊天与其他聊天保存在工作区的结果统一展示；所有科学图形从CSV/JSON绘制，不使用生成式图片编造曲线。</p></header><main>
<div class="notice"><strong>怎么看：</strong>图中耗时只按本批内部对比，误差相对本区fixed1条件参考。跨地形春季时间实验已完成；华北/江淮空间迁移与空间512尚未完成。原图、历史批次和负结果均保留。</div>
<nav id="filters"></nav>'''+''.join(cards)+'''<article><div class="head"><h2>原始记录索引｜包括未单独作图的历史批次</h2><p>这是已保存summary文件的清单，不等于每一项均已独立审查。不同批次、日期、规模及版本不能混算加速比。三日顶层表的耗时列为累计3日；无该指标的项目显示“—”。</p><input id="search" placeholder="搜索：binary、huabei、jianghuai、512、horizon、spatial…"></div><div class="scroll"><table><thead><tr><th>实验来源</th><th>方法ID</th><th>本批中位秒</th><th>MAE / 分</th><th>MAX / 分</th><th>有效像元</th></tr></thead><tbody id="records">'''+''.join(catalog_rows)+'''</tbody></table></div></article>
<footer><a href="../科研进展总览.html">返回累计海报与历史图</a> · <a href="累计实验长图.png">全部高清长图</a> · <a href="data_sources.json">来源及SHA-256</a> · <a href="visualization_audit.json">绘图核对记录</a> · <a href="figures.json">全部图索引</a><p>数据截至本次读取；重建脚本：scripts/build_experiment_gallery.py。没有运行新的Java实验。最新科研操作以实施计划为准。</p></footer></main>
<script>const nav=document.getElementById('filters'),articles=[...document.querySelectorAll('article[data-theme]')];['全部',...new Set(articles.map(x=>x.dataset.theme))].forEach((t,i)=>{let b=document.createElement('button');b.textContent=t;if(!i)b.className='active';b.onclick=()=>{nav.querySelectorAll('button').forEach(x=>x.classList.remove('active'));b.classList.add('active');articles.forEach(x=>x.hidden=t!=='全部'&&x.dataset.theme!==t)};nav.appendChild(b)});document.getElementById('search').oninput=e=>{const q=e.target.value.toLowerCase();document.querySelectorAll('#records tr').forEach(r=>r.hidden=!r.textContent.toLowerCase().includes(q))};</script></html>'''
    (OUT/'index.html').write_text(body,encoding='utf-8')


def main():
    OUT.mkdir(parents=True, exist_ok=False)
    binary=summary('qinling_binary_local_001')
    terrain=[(title,run,summary(run)) for title,run in [('秦岭山地','qinling_spring_001'),('华北平原','huabei_spring_001'),('江淮丘陵','jianghuai_spring_001')]]
    days=[(day,title,summary('qinling_three_day_001/day_'+day)) for day,title in [('2023-03-21','春分'),('2023-06-21','夏至'),('2023-12-21','冬至')]]
    spatial=summary('qinling_spatial_sensitivity_001');ablation=summary('qinling_spatial_ablation_001')
    summer_space=summary('qinling_spatial_summer_001');winter_space=summary('qinling_spatial_block_001')
    draw_overview(binary,spatial,ablation)
    draw_binary(binary);draw_terrain(terrain);draw_seasons(days)
    draw_spatial(spatial,csv_rows('user_runs/qinling_spatial_sensitivity_001/spatial_repeat_comparison.csv'))
    draw_ablation(ablation,summer_space,winter_space)
    draw_scale(summary('qinling_512_fixed_local_001'))
    draw_horizon(summary('qinling_horizon_001'),summary('qinling_three_day_001'),days)
    draw_modules(summary('qinling_modules_001'),summary('qinling_winter_001'),summary('qinling_guard_comparison_001'))
    draw_terrain_maps([('秦岭山地','qinling_90m_128_20261024_132644_824423'),('华北平原','huabei_plain_90m_128_20261026_144020_498379'),('江淮丘陵','jianghuai_hills_90m_128_20261026_150526_895259')])
    draw_error_maps()
    draw_fixed_tradeoff(summary('qinling_tradeoff_001'))
    draw_neighbor()
    catalog=[]
    for p in sorted((ROOT/'user_runs').rglob('summary.csv')):
        rows=csv_rows(p)
        if rows and 'method' in rows[0]:
            catalog.append({'path':p.relative_to(ROOT).as_posix(),'rows':rows})
    build_html(catalog)
    poster=Image.new('RGB',(W,H*len(FIGURES)),BG)
    for i,f in enumerate(sorted(FIGURES,key=lambda f:f['file'])):
        with Image.open(OUT/f['file']) as im:
            poster.paste(im,(0,H*i))
    poster.save(OUT/'累计实验长图.png',dpi=(240,240))
    (OUT/'data_sources.json').write_text(json.dumps(SOURCES,ensure_ascii=False,indent=2),encoding='utf-8')
    (OUT/'figures.json').write_text(json.dumps(FIGURES,ensure_ascii=False,indent=2),encoding='utf-8')
    (OUT/'experiment_catalog.json').write_text(json.dumps(catalog,ensure_ascii=False,indent=2),encoding='utf-8')
    audit={'type':'saved_evidence_visualization_only','figures':len(FIGURES),'summaries_indexed':len(catalog),
           'source_files_hashed':len(SOURCES),'median_checks':CHECKS,'java_experiments_run':False,
           'visual_inspection':'pending','limits':['fixed1 conditional reference','no cross-batch speed ratios','spatial transfer to independent terrain pending','16 is not a global optimum']}
    (OUT/'visualization_audit.json').write_text(json.dumps(audit,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'output':str(OUT),'figures':len(FIGURES),'summaries':len(catalog),'runtime_checks':len(CHECKS)},ensure_ascii=False))


if __name__=='__main__':
    main()
