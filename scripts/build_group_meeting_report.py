"""Build a concise meeting DOCX from existing CSV results. No research runs."""
from pathlib import Path
import csv
from collections import Counter
from PIL import Image, ImageDraw, ImageFont
from docx import Document
from docx.shared import Cm, Pt, RGBColor
from docx.oxml import OxmlElement
from docx.oxml.ns import qn

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'output' / '组会汇报'
QA = OUT / '.qa_20261029'
OUT.mkdir(parents=True, exist_ok=True)
QA.mkdir(exist_ok=True)
TARGET = OUT / '复杂地形潜在日照计算阶段进展_20261029.docx'


def read(run):
    with (ROOT/'user_runs'/run/'summary.csv').open(encoding='utf-8-sig', newline='') as f:
        return {r['method']: r for r in csv.DictReader(f)}


def number(row, key):
    return float(row[key])


local = read('qinling_512_guardlocal_30min_20261029_120231_207')['guardlocal_30min']
v1 = read('qinling_512_adaptive_30min_20261029_124342_729')['adaptive_30min']
combo = read('qinling_512_guardcombined_30min_20261029_132155_284')['guardcombined_30min']
combo2 = read('qinling_512_guardcombined_30min_20261029_133545_120')['guardcombined_30min']
ref = read('qinling_512_reference_002')['fixed_1min']

# A new plot of measured query counts; does not edit an existing image.
im = Image.new('RGB', (1500, 340), 'white')
d = ImageDraw.Draw(im)
font = lambda size: ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', size)
items = [('逐分钟参考', ref, '#A5ADBA'), ('原自适应', v1, '#7D91AA'),
         ('局部细化与补查', local, '#1D7A78'), ('高度角组合与补查', combo, '#D18D32')]
for i, (label, row, color) in enumerate(items):
    y = 15+i*78
    d.text((15, y+8), label, font=font(25), fill='#233247')
    length = int(920*number(row, 'rayCalls')/number(ref, 'rayCalls'))
    d.rounded_rectangle((300, y+5, 300+length, y+50), radius=4, fill=color)
    d.text((315+length, y+8), f"{number(row, 'rayCalls')/1e6:.2f} 百万次", font=font(24), fill='#233247')
chart = QA/'query_counts.png'
im.save(chart)

doc = Document()
sec = doc.sections[0]
sec.page_width, sec.page_height = Cm(21), Cm(29.7)
sec.top_margin, sec.bottom_margin = Cm(1.5), Cm(1.45)
sec.left_margin = sec.right_margin = Cm(1.8)
sec.header_distance = sec.footer_distance = Cm(.75)
for name in ['Normal', 'Title', 'Heading 1', 'Heading 2', 'Caption']:
    st = doc.styles[name]
    st.font.name = 'Calibri'
    st.element.rPr.rFonts.set(qn('w:eastAsia'), '微软雅黑')
    st.font.color.rgb = RGBColor.from_string('202A35')
normal = doc.styles['Normal']
normal.font.size = Pt(10.5)
normal.paragraph_format.space_after = Pt(4)
normal.paragraph_format.line_spacing = 1.05
doc.styles['Title'].font.size = Pt(19)
doc.styles['Title'].font.bold = True
doc.styles['Title'].font.color.rgb = RGBColor(0, 0, 0)
doc.styles['Heading 1'].font.size = Pt(14)
doc.styles['Heading 1'].paragraph_format.space_before = Pt(10)
doc.styles['Heading 1'].paragraph_format.space_after = Pt(6)
doc.styles['Heading 2'].font.size = Pt(11.5)
doc.styles['Heading 2'].paragraph_format.space_before = Pt(7)
doc.styles['Heading 2'].paragraph_format.space_after = Pt(4)
doc.styles['Caption'].font.size = Pt(9)
# Remove inherited paragraph borders, including the default title rule.
for style in doc.styles:
    for border in list(style.element.iter(qn('w:pBdr'))):
        border.getparent().remove(border)

header = sec.header.paragraphs[0]
header.text = '硕士论文阶段进展  |  2026年10月29日'
header.runs[0].font.size = Pt(8)
footer = sec.footer.paragraphs[0]
footer.alignment = 2
footer.add_run('组会汇报  ·  ')
field = OxmlElement('w:fldSimple'); field.set(qn('w:instr'), 'PAGE'); footer._p.append(field)
for r in footer.runs:
    r.font.size = Pt(8)


def p(text, bold=False, small=False):
    para = doc.add_paragraph()
    run = para.add_run(text)
    run.bold = bold
    if small:
        run.font.size = Pt(9)
        run.font.color.rgb = RGBColor.from_string('586579')
    return para


def h(text, level=1):
    doc.add_heading(text, level)


def table(headers, body, widths=None):
    t = doc.add_table(rows=1, cols=len(headers))
    t.style = 'Table Grid'
    t.autofit = False
    if widths:
        for col, width in zip(t.columns, widths):
            col.width = Cm(width)
    for cell, value in zip(t.rows[0].cells, headers):
        cell.text = value
        shade = OxmlElement('w:shd'); shade.set(qn('w:fill'), 'E9F0F4'); cell._tc.get_or_add_tcPr().append(shade)
    repeat = OxmlElement('w:tblHeader'); t.rows[0]._tr.get_or_add_trPr().append(repeat)
    for row in body:
        for cell, value in zip(t.add_row().cells, row):
            cell.text = str(value)
    for row in t.rows:
        cant = OxmlElement('w:cantSplit'); row._tr.get_or_add_trPr().append(cant)
        for i, cell in enumerate(row.cells):
            if widths: cell.width = Cm(widths[i])
            cell.vertical_alignment = 1
            for para in cell.paragraphs:
                para.paragraph_format.space_after = Pt(2)
                para.paragraph_format.space_before = Pt(2)
                for run in para.runs:
                    run.font.size = Pt(9)
    for cell in t.rows[0].cells:
        for r in cell.paragraphs[0].runs: r.bold = True
    return t


doc.add_paragraph('复杂地形潜在日照计算阶段进展', 'Title')
p('研究题目  大尺度复杂地形条件下潜在日照时长快速计算方法研究', small=True)
p('本阶段已完成多地形和多季节验证，并把秦岭接收区扩大到512×512。局部细化相对原自适应方法减少计算，但少量像元误差增大；太阳高度角组合能改善部分尾部误差。目前重点是明确具体规则的贡献，而非提前认定两个独立创新。')
h('一 研究问题与当前方法')
p('潜在日照时长指在DEM与太阳几何条件下累计的可见时长，不包含云量和天气折算。当前研究减少时间方向的遮挡查询，空间射线模型保持一致。')
table(['方法', '主要处理方式'], [
    ['原自适应方法 V1', '粗采样和中点检查；部分多段或异常触发当前像元全天分钟补算'],
    ['局部细化与补查', '中点和条件四分点分割区间，仅对仍显示变化的子段补分钟；粗采样全天暗时保留全天补查'],
    ['高度角组合与补查', '在局部版上加入高度角与已采样状态辅助的10/30分钟步长选择']
], [4,13.4])
h('二 秦岭512区域的主要结果')
p('90米UTM栅格，262,144个接收像元，2023年12月21日；射线间距50米，搜索范围10公里。改进方法预热1次、正式3次，计算耗时取中位数。')
body = []
for label, row in [('逐分钟参考', ref), ('原自适应', v1), ('局部细化与补查', local), ('高度角组合与补查', combo)]:
    body.append([label, f"{number(row,'runtime_sec'):.2f}" + (' 单次' if row is ref else ''),
                 f"{number(row,'MAE_min'):.5f}", f"{number(row,'MAX_abs_error_min'):.0f}",
                 str(round(number(row,'error_gt1_ratio')*262144))])
table(['方法','计算秒数','MAE 分钟','MAX 分钟','误差超1分钟点数'], body, [4.6,3.1,3,2.8,3.9])
doc.add_picture(str(chart), width=Cm(17.1))
p('图1  同一512区域的实际遮挡查询次数', small=True)
p('局部版相对原自适应：计算耗时减少21.95%，查询减少37.42%；相对逐分钟参考，查询减少89.53%。组合较局部查询多28.30%，最大误差18→14分钟；逐像元39点改善、38点恶化，不能称全面更准。', bold=True)
p(f"说明：逐分钟是条件参考，不是真值；参考仅运行1次，不据此宣称严格同批加速比。组合额外重复一批，中位{number(combo2,'runtime_sec'):.2f}秒，6份PSD一致，两批全部保留。输出、加载不计入表中计算时间。", small=True)

doc.add_page_break()
h('三 不同地形与既有方法的比较')
p('三类地区均使用现有China-90m.tif，接收区128×128，日期2023年3月21日。每个区域分别计算逐分钟参考；同一地区内的方法共用输入和空间参数。')
terrain_body = []
for label, run in [('华北平原','huabei_spring_001'),('江淮低丘','jianghuai_spring_001'),('秦岭山地','qinling_spring_001')]:
    rs = read(run)
    terrain_body.append([label, f"{number(rs['fixed_10min'],'runtime_sec'):.2f}",
                         f"{number(rs['fixed_10min'],'MAE_min'):.2f}",
                         f"{number(rs['guardlocal_30min'],'runtime_sec'):.2f}", '0 / 0'])
table(['地区','固定10分钟 秒','固定10分钟 MAE','局部版 秒','局部版 MAE / MAX'], terrain_body, [3.1,3.6,3.9,2.9,3.9])
p('误差单位为分钟。局部版在三个春季场景的累计时长与分钟参考一致；这不代表任意地形或任意短窗口都无误差。不同地区的绝对耗时受地形和运行时负载影响，不直接用于证明地形类别决定速度。', small=True)
h('地平线预计算路线的单日与三日成本', 2)
p('秦岭128×128，按春分、夏至、冬至顺序累计。每次重复只建一次表供三日共用；下表为同一批次的计算中位秒数，包含建表和查询。')
multi = read('qinling_three_day_001')
table(['方法','累计首日 秒','累计三日 秒','三日最大误差 分钟'], [
    [label, f"{number(multi[m], 'day1_total_sec'):.2f}", f"{number(multi[m], 'day3_total_sec'):.2f}", maximum]
    for label,m,maximum in [('地平线1度','horizon_1deg',2),('地平线2度','horizon_2deg',7),
                            ('原自适应','adaptive_30min',18),('局部细化与补查','guardlocal_30min',18),
                            ('高度角组合与补查','guardcombined_30min',9)]
], [5.4,3.3,3.3,4.4])
p('解释：这三个指定日期内，局部版成本更低；1度地平线的最大误差更小。多日能分摊建表成本，但本结果不能外推全年，也不能代表成熟HORAYZON工具的性能。512地平线尚无已核验结果。', small=True)
h('四 18分钟最大误差的原因')
p('512像元354,418的分钟参考为380分钟，原自适应与局部版均为398分钟。保存轨迹确认，三段阴影藏在同亮采样点之间，未触发细化。')
table(['日出后区间 分钟','分钟参考中的阴影时长','多算日照 分钟'],
      [['255—270','3分钟',3],['285—300','3分钟',3],['315—330','12分钟',12]], [5.8,6.3,4.3])
p('该点与旧区域162,226的参考轨迹完全一致，是已知问题尚未修复。组合在该点误差降至6分钟，新的14分钟最大误差位于另一像元。全暗补查只保护未发现任何日照的像元，不能补回这类隐藏阴影。')
p('7条保存轨迹离线回放表明：普遍加密探测能减少该点漏检，但会明显增加原本正确点的查询。尚未采用全域3分钟加密，也不把样例修正当整区精度保证。', small=True)

doc.add_page_break()
h('五 对前期讨论问题的回应')
p('以下按前期讨论中的建议归纳，不作为导师逐字引述。', small=True)
h('早晚边界能否决定全天日照', 2)
p('早晚边界值得重点计算，但首尾相减只适用于中间连续见光的情况。已保存的秦岭真实像元存在4段日照：首尾跨度398分钟，累计日照380分钟，中间阴影18分钟。因此不能把“中间基本不变”作为所有像元的共同前提。平原与低丘春季样区的分钟统计均为单段，说明这一简化具有场景条件，应通过区间统计判断，而非直接否定早晚优先思路。')
h('复杂地形自适应是否已经实现', 2)
p('已实现按采样明暗状态决定探测与细化，并试验太阳高度角辅助步长。尚未实现坡度、起伏度等地形指标直接驱动步长。多地形实验是在验证适用性，不能等同于已实现地形特征感知；后续只有指标确有增益才考虑加入。')
h('为什么不直接用固定15或30分钟', 2)
p('固定粗步长通常更快，但误差也可能更大。秦岭128冬季同批实验：固定30分钟1.38秒、MAE 9.28分钟、MAX 40分钟；局部版2.97秒、MAE 0.00250分钟、MAX 18分钟。目标是比较精度与成本取舍，不要求在所有误差指标上胜出。固定15分钟在已诊断点的日总误差为25分钟，但这是单点回放，尚非整区结论。')
h('规模和精度应怎样解释', 2)
p('512×512接收区约46.08公里见方，外围另留遮挡缓冲，已比128扩大16倍像元数；这提供区域规模证据，不代表全国或全年验证。数据来自同一全国DEM，不是三个独立来源。原“最大误差1分钟”只是初始目标，正式容许误差应结合用途与导师讨论，不能随结果调整后宣称达标。')
h('六 当前判断与下一步')
p('当前可以作为组会阶段成果汇报：方法已实现，精度与效率已有证据，限制也已定位。局部版是主要效率候选，组合版作为精度取舍备选；两个模块暂不认定为两个独立创新。')
p('下一项只补普通局部细化近邻对照：保持粗采样、中点、缓存及全暗保护一致，关闭条件四分点，判断其独立收益。代码与128冬季配置已准备，轻量检查尚未运行。若收益不足，再讨论一项具体深化，不立即扩展GPU、天气或光伏。')
p('本次希望讨论：核心方法是否按上述范围继续；论文采用什么误差评价要求；近邻对照通过后是否进入开题材料整理。', bold=True)
h('主要证据入口', 2)
p('实验原记录位于user_runs。512取20261029的reference_002、adaptive_124342、guardlocal_120231、guardcombined_132155及133545；128取三地区spring、qinling_tradeoff_001及qinling_three_day_001。完整目录、配置及核验记录见科研路线与进度.md；表格数字直接读取各批summary.csv。', small=True)
p('相关工作：CN103003818B说明书已有粗时间检查与阴影边界细化，https://patents.google.com/patent/CN103003818B/en；HORAYZON v1.2为空间地平线加速，https://doi.org/10.5194/gmd-15-6817-2022。当前不主张粗扫细化框架首次提出。', small=True)

doc.save(TARGET)
print(f'DOCX_CREATED={TARGET}')
print('MEETING_REPORT_BUILT_FROM_EXISTING_RESULTS no_research_execution')
