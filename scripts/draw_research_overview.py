"""Update one research overview image from verified records; never runs experiments."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs' / '科研进展总览.png'
WIDTH = 1800
BG, INK, MUTED = '#f3f6fa', '#172b40', '#536477'
GREEN, ORANGE, GREY = '#167452', '#ab570e', '#64748b'
FONT = Path('C:/Windows/Fonts/msyh.ttc')
BOLD = Path('C:/Windows/Fonts/msyhbd.ttc')


def font(size, bold=False):
    return ImageFont.truetype(str(BOLD if bold else FONT), size)


canvas = Image.new('RGB', (WIDTH, 7000), BG)
d = ImageDraw.Draw(canvas)


def text(x, y, value, size=30, color=INK, bold=False, max_width=1450):
    f = font(size, bold)
    lines = []
    for paragraph in value.split('\n'):
        line = ''
        for char in paragraph:
            if line and d.textlength(line + char, font=f) > max_width:
                lines.append(line)
                line = char
            else:
                line += char
        lines.append(line)
    for line in lines:
        d.text((x, y), line, font=f, fill=color)
        y += size + 16
    return y


y = text(100, 70, '我的硕士论文 · 科研进展总览', 58, bold=True)
y = text(100, y + 10, '研究：大尺度复杂地形下，怎样更快地计算潜在日照时长？', 33)
y = text(100, y + 8, '更新：2026-10-24   ｜   本次新增：局部细化在秦岭真实地形上的首轮结果', 27, MUTED)
y += 28


def card(number, title, status, paragraphs, color=GREEN, bars=None):
    global y
    top = y
    # Lay out first, then paint the background beneath the rendered content.
    layer = canvas.copy()
    heading_y = top + 30
    bottom = text(145, heading_y, f'{number}  {title}', 38, bold=True, max_width=1240)
    bottom = text(145, bottom + 6, status, 27, color, True)
    for label, value in paragraphs:
        bottom = text(145, bottom + 16, label, 28, MUTED, True)
        bottom = text(145, bottom, value, 30)
    if bars:
        bottom += 25
        for label, value, display in bars:
            bottom = text(145, bottom, f'{label}：{display}', 29)
            d.rounded_rectangle((145, bottom + 4, 1560, bottom + 28), radius=12, fill='#e6ebf1')
            d.rounded_rectangle((145, bottom + 4, 145 + 1415 * value, bottom + 28), radius=12, fill=color)
            bottom += 46
    bottom += 32
    background = ImageDraw.Draw(layer)
    background.rounded_rectangle((100, top, 1700, bottom), radius=24, fill='white')
    background.rounded_rectangle((100, top, 112, bottom), radius=5, fill=color)
    # Content was rendered on the main canvas; copy only its interior to the card.
    layer.paste(canvas.crop((125, top + 15, 1675, bottom - 15)), (125, top + 15))
    # Remove the old background under the text by using the text drawing as a mask.
    crop = canvas.crop((125, top + 15, 1675, bottom - 15))
    import PIL.ImageChops
    mask = PIL.ImageChops.difference(crop, Image.new('RGB', crop.size, BG)).convert('L').point(lambda p: 255 if p else 0)
    background.rectangle((125, top + 15, 1674, bottom - 16), fill='white')
    layer.paste(crop, (125, top + 15), mask)
    canvas.paste(layer)
    y = bottom + 30


card('01', '先弄清：我们到底计算什么', '研究范围已确定', [
    ('输入 → 计算 → 输出', '地形高程数据 ＋ 日期和位置 → 判断太阳是否被山体遮挡 → 累计日照分钟数'),
    ('研究目标', '尽量减少昂贵的射线遮挡查询，同时控制日照时长误差。\n研究的是地形条件下的潜在日照，不包含云雨天气。'),
])
card('02', '建立比较起点，并接通一个新山区', '已完成 · 用户亲自运行，结果已核对', [
    ('我们做了什么', '复用已有固定步长实验和原自适应方法；将全国90米高程数据中的秦岭区域转换为程序可读输入。'),
    ('现在有什么可以继续用', '128×128个接收点，共16,384点；外围保留约10公里遮挡范围。\n已保存2023-06-21逐分钟计算结果，后续用它计算误差，无须反复重跑。'),
    ('为什么先做这一步', '让新方法与原方法使用同一地形、日期和空间参数，结果才便于比较。'),
])
card('03', '第一项改进：只在变化附近补查分钟节点', '已完成 · 人工检查通过', [
    ('原方法怎么做 → 改进后怎么做', '原方法：30分钟区间两端明暗不同，就补查整个区间；遇到某些异常会补查该像元全天。\n改进后：用中点和条件四分点缩小变化范围，只补查相关子区间。'),
    ('先用已知答案检查程序', '26项检查通过。人为设置的短阴影例子：两种方法都算出110分钟，查询从121次降为24次。此数字仅属于人工用例。'),
    ('目前仍有什么做不到', '未被端点和中点碰到的隐藏短窗仍可能漏检；人工反例中会把118分钟算成120分钟。'),
])
card('04', '最新成果：把局部细化放到秦岭真实地形中', '已完成 · 单地区、单日期的初步实测', [
    ('怎样比较', '秦岭128×128接收区，日期2023-06-21。两种方法各预热一次、正式运行三次；以下为计算耗时中位数。'),
    ('精度有没有下降', '16,384点全部有效，未知状态为0，三重复一致。与保存的逐分钟参考没有分钟级差异；汇总约10⁻¹²分钟的差异是舍入误差。'),
    ('本次得到了什么', '计算耗时减少24.57%，射线查询从172.51万次降至117.16万次，减少32.08%。\n两种方法本次均未全天回退，收益来自缩小局部细化范围。'),
], bars=[('原自适应方法', 1, '6.991秒'), ('加入局部细化后', 5.2732801/6.9908103, '5.273秒')])
card('05', '下一项：按太阳高度和明暗变化分配采样间隔', '下一项 · 尚未实现或运行', [
    ('准备改什么', '目前粗步长固定为30分钟。下一步尝试：太阳高度低于15°时用10分钟，否则用30分钟；检测到变化后，下一段用10分钟。'),
    ('为什么要单独做', '分别比较“只改变采样间隔”“只做局部细化”“两者一起用”，看清每个模块是否有实际作用。'),
    ('你会怎样参与', '先理解规则 → 运行人工检查 → 运行秦岭对照 → 把输出发回 → 一起解释结果，再决定后续。'),
], ORANGE)
card('06', '之后再补齐论文证据与开题材料', '尚未开始 · 不与当前任务同时展开', [
    ('后续实验', '补两个代表日期；增加地平线预计算对照；方法确定后做512×512接收区的规模验证。'),
    ('最终要说明', '什么情况下更快、误差有多大、预处理是否划算、规模变大后成本怎样变化。'),
    ('开题材料', '把研究问题、方法流程、已有基础和实测结果逐步放入报告与PPT；开题后继续扩展地区和验证。'),
], GREY)
y = text(100, y + 5, '证据：user_runs/qinling_v1_v2local_001/summary.csv', 25, MUTED)
y = text(100, y, '备注：当前耗时来自调试启动的初步计时；最终性能比较统一无调试。', 25, MUTED)
y = text(100, y, '维护方式：每次核对新结果后，更新同一张图；已完成结论和原始实验记录保留。', 25, MUTED)
OUT.parent.mkdir(exist_ok=True)
canvas.crop((0, 0, WIDTH, y + 55)).save(OUT)
print(f'OVERVIEW_UPDATED {OUT} {WIDTH}x{y + 55}')

