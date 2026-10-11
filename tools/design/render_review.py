"""Render original static JPQ design drawings; does not edit supplied images.

Run with Pillow and a locally obtained official DingTalk JinBuTi font.
These are review artifacts, not App screenshots. Font binaries are not published.
"""
from pathlib import Path
import json
import math
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs/design/review"
OUT.mkdir(parents=True, exist_ok=True)
FONT = Path(os.environ.get("JPQ_DESIGN_FONT", "C:/Windows/Fonts/msyh.ttc"))
BOLD = Path(os.environ.get("JPQ_DESIGN_BOLD_FONT", "C:/Windows/Fonts/msyhbd.ttc"))
OVERLAY_FONT = Path(os.environ.get("JPQ_OVERLAY_FONT", str(ROOT / "research-private/dingtalk-official.ttf")))
PAPER = "#F5F4F0"
INK = "#222824"
MUTED = "#68726B"
DARK = "#131413"
PANEL = "#262626"
GOLD = "#FFE395"
WHITE = "#FFFFFF"
DIM = "#7B827D"
TEAL = "#167D73"
LABELS = ["大", "小", "A", "K", "Q", "J", "10", "9", "8", "7", "6", "5", "4", "3", "2"]
VALUES = [1, 1, 4, 6, 5, 3, 0, 1, 1, 0, 0, 4, 4, 4, 6]
HISTORY = ["Q·6·77722·AAA99·3", "9·4·小·66688·JJJ1010·大", "6·99944·K·J·10101088·7"]
DEFAULTS = json.loads((ROOT / "docs/research/reference-display-defaults.json").read_text(encoding="utf-8"))
JPQ_DEFAULTS = json.loads((ROOT / "docs/design/display-defaults.json").read_text(encoding="utf-8"))
WIDTH_ANALYSIS = json.loads((ROOT / "docs/design/history-width-analysis.json").read_text(encoding="utf-8"))


def font(size, bold=False):
    return ImageFont.truetype(str(BOLD if bold else FONT), int(size))


class Canvas:
    def __init__(self, h, title, subtitle, number):
        self.im = Image.new("RGB", (1800, h), PAPER)
        self.d = ImageDraw.Draw(self.im)
        self.text(64, 40, title, 46, bold=True)
        self.text(66, 106, subtitle, 25, MUTED)
        self.text(66, h - 54, "设计确认稿 v4 · 2026-10-11 · 自绘静态示意，手机端尚未开发", 22, MUTED)
        self.text(1650, h - 54, number, 24, MUTED)

    def rect(self, box, fill, radius=18, outline=None, width=1):
        self.d.rounded_rectangle(tuple(map(int, box)), radius=radius, fill=fill, outline=outline, width=width)

    def text(self, x, y, value, size=26, color=INK, bold=False, maxw=None):
        f = font(size, bold)
        if maxw is not None:
            while self.d.textlength(value, font=f) > maxw and size > 14:
                size -= 1
                f = font(size, bold)
        self.d.text((int(x), int(y)), value, font=f, fill=color)

    def center(self, box, value, size=26, color=INK, bold=False):
        x, y, r, b = box
        f = font(size, bold)
        while self.d.textlength(value, font=f) > r - x - 14 and size > 14:
            size -= 1
            f = font(size, bold)
        bounds = self.d.textbbox((0, 0), value, font=f)
        self.d.text(((x+r-self.d.textlength(value, font=f))/2, (y+b-bounds[3]-bounds[1])/2), value, font=f, fill=color)

    def wrap(self, x, y, value, width, size=25, color=MUTED, step=None):
        lines, line = [], ""
        f = font(size)
        for ch in value:
            if ch == "\n" or self.d.textlength(line + ch, font=f) > width:
                lines.append(line)
                line = "" if ch == "\n" else ch
            else:
                line += ch
        if line:
            lines.append(line)
        for i, line in enumerate(lines):
            self.text(x, y + i * (step or size + 12), line, size, color)
        return y + len(lines) * (step or size + 12)

    def pill(self, box, value, active=True, size=25):
        self.rect(box, GOLD if active else "#434343", 22)
        self.center(box, value, size, DARK if active else WHITE, bold=active)

    def toggle(self, x, y, active=True):
        self.rect((x, y, x+68, y+36), GOLD if active else "#656B67", 18)
        cx = x + (50 if active else 18)
        self.d.ellipse((cx-14, y+4, cx+14, y+32), fill=WHITE)

    def phone(self, x, y, w=490, h=1030, title="", nav=None):
        self.rect((x, y, x+w, y+h), "#090A09", 46)
        self.rect((x+12, y+12, x+w-12, y+h-12), DARK, 35)
        self.text(x+35, y+32, "11:22", 22, WHITE)
        self.text(x+w-105, y+32, "62%", 22, DIM)
        self.text(x+35, y+90, title, 34, GOLD, bold=True)
        if nav:
            self.d.line((x+28, y+h-90, x+w-28, y+h-90), fill="#383D39", width=2)
            self.center((x+25,y+h-84,x+w/2,y+h-28),"记牌",26,GOLD if nav=="记牌" else DIM)
            self.center((x+w/2,y+h-84,x+w-25,y+h-28),"设置",26,GOLD if nav=="设置" else DIM)
        self.rect((x+w/2-65, y+h-23, x+w/2+65, y+h-17), DIM, 3)

    def row(self, x, y, w, left, right="", switch=None, h=78):
        self.rect((x, y, x+w, y+h), PANEL, 18)
        self.text(x+20, y+21, left, 25, WHITE)
        if switch is not None:
            self.toggle(x+w-90, y+22, switch)
        elif right:
            self.text(x+w-155, y+24, right, 22, GOLD, maxw=135)

    def save(self, name):
        self.im.save(OUT / name, optimize=True)


def jpq_text(im, box, value, size, color, align="center"):
    """Use the actual reference typeface; never silently substitute a UI font."""
    d = ImageDraw.Draw(im)
    f = ImageFont.truetype(str(OVERLAY_FONT), max(1, round(size)))
    x, y, r, b = box
    bbox = d.textbbox((0, 0), value, font=f)
    tx = (x+r-bbox[2]-bbox[0])/2 if align == "center" else x-bbox[0]
    ty = (y+b-bbox[3]-bbox[1])/2
    d.text((round(tx), round(ty)), value, font=f, fill=color)


def circle(im, x, y, scale, value):
    w = 44*scale
    d = ImageDraw.Draw(im)
    d.ellipse((round(x),round(y),round(x+w),round(y+w)), fill=GOLD, outline=PANEL, width=round(2*scale))
    jpq_text(im,(x,y,x+w,y+w),value,16*scale,"#000000")


def overlay(c, x, y, full=True, scale=2.18, double_tap_hide=False, show_reset=True,
            reference_width=False, histories=None, counts=None, values=None):
    # The reference dp constants and adjustment floats drive the same geometry
    # in both full and count-only previews. This is not a crop of the APK UI.
    a = DEFAULTS["twoDeckAdjustment"] if reference_width else JPQ_DEFAULTS["twoDeckAdjustment"]
    base = DEFAULTS["standardBase"]
    g = base["grid"]
    rec = base["record"]
    win = base["window"]
    grid_base = g["columnCount"]*g["cellWidthDp"] + (g["columnCount"]-1)*g["cellSpacingDp"]
    content_base = grid_base + win["rightRecordWidthDp"] + win["sectionSpacingDp"] + 2*win["edgeInsetDp"]
    inner = content_base*a["contentWidthScale"] - 2*win["edgeInsetDp"] - win["sectionSpacingDp"]
    gridw = inner*a["gridSectionRatio"]*scale
    rightw = inner*(1-a["gridSectionRatio"])*scale if full else 0
    histories = HISTORY if histories is None else histories
    counts = [13, 14, 14] if counts is None else counts
    values = VALUES if values is None else values
    if full and not reference_width:
        # Keep the saved base geometry and grow only the right section.
        # Android will use its resolved text measurer and available screen area.
        detail_font = ImageFont.truetype(str(OVERLAY_FONT), 1000)
        needed = []
        for value in histories:
            bounds = detail_font.getbbox(value)
            ink = max(detail_font.getlength(value), bounds[2]) - min(0, bounds[0])
            needed.append(ink/1000*rec["detailFontSizeSp"]*a["recordDetailTextScale"])
        rightw = max(rightw, math.ceil(32+max(needed)+JPQ_DEFAULTS["historyWidth"]["endPaddingDp"])*scale)
    edge, seam = scale, scale
    ch = g["cellHeightDp"]*a["gridCellHeightScale"]*scale
    h = ch*2 + seam + edge*2
    contentw = gridw + (seam+rightw if full else 0) + edge*2
    button, gap = 44*scale, 4*scale
    left_controls = 0 if double_tap_hide else button+gap
    right_controls = button+gap if show_reset else 0
    gx = left_controls+edge
    total = left_controls+contentw+right_controls
    # Antialias edges and glyphs at 3x, then draw on the review sheet.
    aa = 3
    patch = Image.new("RGBA",(round(total*aa),round(max(h,button)*aa)),(0,0,0,0))
    d = ImageDraw.Draw(patch)
    ss = scale*aa
    cb = (round(left_controls*aa),0,round((left_controls+contentw)*aa)-1,round(h*aa)-1)
    d.rounded_rectangle(cb,radius=12*ss,fill="#000000")
    inner_patch = Image.new("RGBA",patch.size,PANEL)
    pd = ImageDraw.Draw(inner_patch)
    cw = (gridw-14*seam)/15
    pd.rectangle((round(gx*aa),round(edge*aa),round((gx+gridw)*aa),round((h-edge)*aa)),fill="#000000")
    for i,(label,value) in enumerate(zip(LABELS,values)):
        left = (gx+i*(cw+seam))*aa
        top = edge*aa
        for j in range(round(ch*aa)):
            t=j/max(1,round(ch*aa)-1)
            rgb=tuple(round(v*(1-t)+67*t) for v in (109,110,110))
            pd.line((round(left),round(top+j),round(left+cw*aa),round(top+j)),fill=rgb)
        vb = (left,(edge+ch+seam)*aa,left+cw*aa,(edge+ch*2+seam)*aa)
        pd.rectangle(tuple(map(round,vb)),fill="#434343")
        jpq_text(inner_patch,(left,top,left+cw*aa,top+ch*aa),label,16*a["gridHeaderTextScale"]*ss,WHITE)
        threshold = 2 if label in ("大","小") else 4
        # #26FFFFFF composited over #434343, exactly the reference zero alpha.
        color = "#FF3140" if value >= threshold else (WHITE if value else "#5F5F5F")
        jpq_text(inner_patch,vb,str(value),16*a["gridValueTextScale"]*ss,color)
    if full:
        rx = (gx+gridw+seam)*aa
        rh = rec["rowHeightDp"]*a["recordRowHeightScale"]*ss
        for i,(seat,count,history) in enumerate(zip("上对下",counts,histories)):
            ry=edge*aa+i*(rh+ss)
            lb=(rx,ry,rx+14*ss,ry+rh)
            pd.rectangle(tuple(map(round,lb)),fill="#434343")
            jpq_text(inner_patch,lb,seat,12*ss,WHITE)
            nb=(rx+16*ss,ry,rx+30*ss,ry+rh)
            pd.rectangle(tuple(map(round,nb)),fill=GOLD)
            jpq_text(inner_patch,nb,str(count),12*a["recordCounterTextScale"]*ss,"#000000")
            hb=(rx+32*ss,ry,(gx+gridw+seam+rightw)*aa,ry+rh)
            jpq_text(inner_patch,hb,history,14*a["recordDetailTextScale"]*ss,WHITE,"left")
    mask=Image.new("L",patch.size,0)
    md=ImageDraw.Draw(mask)
    inset=win["borderWidthDp"]*ss
    md.rounded_rectangle((cb[0]+inset,cb[1]+inset,cb[2]-inset,cb[3]-inset),radius=11*ss,fill=255)
    patch.paste(inner_patch,(0,0),mask)
    if not double_tap_hide:
        circle(patch,0,0,ss,"隐藏")
    if show_reset:
        circle(patch,(total-button)*aa,0,ss,"重置")
    patch=patch.resize((round(total),round(max(h,button))),Image.Resampling.LANCZOS)
    c.im.paste(patch,(round(x),round(y)),patch)
    return total


def waiting(c, x, y, scale=2.18, double_tap_hide=False):
    if not double_tap_hide:
        circle(c.im,x,y,scale,"隐藏")
    lx=x+(0 if double_tap_hide else 48*scale)
    box=(lx,y,lx+370*scale,y+46*scale)
    c.rect(box,GOLD,round(12*scale),outline="#000000",width=round(scale))
    jpq_text(c.im,(lx+9*scale,y+3*scale,box[2]-9*scale,y+25*scale),"进入游戏房间后，会自动开始记牌",18*scale,"#000000")
    jpq_text(c.im,(lx+9*scale,y+27*scale,box[2]-9*scale,y+43*scale),"牌局已开始仍未显示？请检查权限或重新开始记牌",10.5*scale,"#000000")


def table(c, box, seats=True):
    x,y,r,b=box
    c.rect(box,TEAL,26)
    if not seats:
        return
    c.text(x+35,y+155,"上家",23,"#82B8AE")
    c.text((x+r)/2-50,y+126,"对家 · 队友",23,"#82B8AE")
    c.text(r-125,y+155,"下家",23,"#82B8AE")


def start_settings():
    c=Canvas(1410,"记牌器 · 启动与设置","原配置顺序、按钮设置保留；右侧采用适中基础宽度，超长记录自动加宽。","01 / 06")
    for x,title,nav in [(70,"记牌器","记牌"),(655,"权限设置",None),(1240,"设置","设置")]:
        c.phone(x,180,title=title,nav=nav)
    x=70
    c.text(x+35,325,"本地运行 · 个人使用",23,DIM)
    c.text(x+384,287,"权限",24,GOLD)
    c.text(x+35,383,"1、选择游戏平台",26,WHITE)
    c.row(x+28,428,434,"大掼蛋（腾讯）","已适配")
    c.text(x+35,548,"2、选择玩法",26,WHITE)
    c.pill((x+28,597,x+162,655),"掼蛋")
    c.pill((x+176,597,x+460,655),"默认",False)
    c.text(x+35,697,"3、选择玩家人数",26,WHITE)
    c.pill((x+28,742,x+234,800),"4 人")
    c.text(x+35,839,"4、几副牌",26,WHITE)
    c.pill((x+28,884,x+234,942),"2 副")
    for i,label in enumerate(LABELS):
        a=x+28+i*29
        c.rect((a,971,a+27,1040),PANEL,5)
        c.center((a,973,a+27,1004),label,18,GOLD)
        c.center((a,1004,a+27,1038),"2" if i<2 else "8",20,WHITE)
    c.pill((x+28,1056,x+462,1111),"开始记牌",size=31)
    x=655
    c.wrap(x+35,332,"授权后即可使用记牌；后台设置按实际手机状态显示。",415,23,DIM)
    rows=[("悬浮窗权限","已开启"),("通知权限","已允许"),("允许后台运行","去设置"),("允许后台耗电","去设置"),("锁定后台应用","查看指引")]
    for i,(left,right) in enumerate(rows): c.row(x+28,440+i*108,434,left,right)
    c.wrap(x+35,1006,"屏幕共享在每次开始时由系统授权。不能自动检测的后台项标为需手动设置。",410,23,DIM)
    x=1240
    c.text(x+35,339,"显示设置",25,DIM)
    c.row(x+28,390,434,"显示三家出牌记录",switch=True,h=66)
    c.row(x+28,462,434,"显示重置按钮",switch=True,h=66)
    c.row(x+28,534,434,"双击记牌条隐藏",switch=False,h=66)
    c.row(x+28,606,434,"尺寸调整","›",h=66)
    c.row(x+28,678,434,"牌序调整","›",h=66)
    c.text(x+35,772,"应用设置",25,DIM)
    c.row(x+28,824,434,"权限设置","›",h=66)
    c.row(x+28,896,434,"应用更新","检查更新",h=66)
    c.row(x+28,968,434,"配置备份","›",h=66)
    c.row(x+28,1040,434,"关于记牌器","›",h=66)
    c.wrap(76,1234,"开始 → 系统共享授权 → 返回大掼蛋 → 等待有效牌局 → 自动记牌。无需手动核对或修正。",1640,26,INK)
    c.save("start-settings.png")


def overlay_states():
    c=Canvas(1510,"记牌器 · 原 APK 风格悬浮窗","原字形、字号和牌格不变；右侧基础宽度缩短，放不下记录时自动加宽。","02 / 06")
    c.text(70,184,"开始后 · 等待有效牌局",29,bold=True)
    table(c,(70,237,1730,475))
    waiting(c,86,252)
    c.text(1210,272,"发牌 / 贡牌期间继续等待",25,WHITE)
    c.text(1210,320,"初始化通过后自动切换",25,WHITE)
    c.text(70,519,"正常记牌 · 三家区域默认开启",29,bold=True)
    table(c,(70,572,1730,902))
    overlay(c,84,588)
    for i,label in enumerate(["9","10","J","Q","K"]):
        px=660+i*92
        c.rect((px,749,px+86,839),"#F1F2E9",9)
        c.center((px,754,px+86,797),label,29,bold=True)
        # Draw club shapes directly to avoid missing suit glyphs in CJK fonts.
        cx,cy=px+43,816
        for ox,oy in [(0,-9),(-9,1),(9,1)]:
            c.d.ellipse((cx+ox-9,cy+oy-9,cx+ox+9,cy+oy+9),fill=INK)
        c.d.polygon([(cx,cy),(cx-5,cy+15),(cx+5,cy+15)],fill=INK)
    c.text(180,700,"红：普通牌 ≥4；王各自 ≥2",25,WHITE)
    c.text(180,743,"白：其余正数；淡色：0",25,WHITE)
    c.text(1180,701,"黄色数字 = 累计已出",25,WHITE)
    c.text(1180,743,"上 / 对 / 下三家历史",25,WHITE)
    c.text(70,946,"仅计数 · 关闭整个右侧区域",29,bold=True)
    table(c,(70,997,1730,1224))
    overlay(c,84,1015,full=False)
    c.wrap(920,1023,"保留左侧列宽和字号，条宽缩短。三家识别、扣牌和历史仍继续。再次开启恢复完整记录。",730,27,WHITE)
    circle(c.im,85,1260,1.65,"显示")
    c.text(176,1272,"隐藏后只留「显示」圆钮；恢复时显示当前状态，不停止识别。",25,INK)
    c.text(75,1368,"工具入口提议 D2：长按记牌条或「隐藏」按钮；拖动内容区移动，不触发双击隐藏。",25,MUTED)
    c.save("overlay-states.png")


def adjust_header(c, y, order=False):
    c.text(70,y-51,"牌序调整" if order else "尺寸调整",30,bold=True)
    table(c,(70,y,1730,y+695))
    overlay(c,85,y+14)
    c.text(700,y+157,"自绘桌面示意 · 上方即时预览",25,"#B6D2C5")
    c.rect((72,y+262,1728,y+694),PANEL,28)
    c.pill((105,y+288,285,y+351),"尺寸调整",not order)
    c.pill((307,y+288,487,y+351),"牌序调整",order)
    c.text(535,y+305,"掼蛋默认 · 4人 · 2副",26,DIM)
    c.text(1165,y+305,"退出",27,WHITE)
    c.text(1280,y+305,"重置",27,GOLD)
    c.pill((1390,y+282,1697,y+359),"保存并退出",size=29)


def adjustment():
    c=Canvas(1900,"记牌器 · 复现原来的两页调整","宽度与比例设置基础布局；超长记录只扩展右侧，不改左侧牌格或保存值。","03 / 06")
    adjust_header(c,245)
    a=JPQ_DEFAULTS["twoDeckAdjustment"]
    pct=int(a["gridSectionRatio"]*100)
    groups=[("窗口",[("总宽度",f'{int(a["contentWidthScale"]*100)}%',(a["contentWidthScale"]-.8)/.55),("左右比例",f'{pct}:{100-pct}',(a["gridSectionRatio"]-.3)/.3)]),
            ("行高",[("牌格高度","94%",(.94622684-.85)/.45),("记录行高","98%",(.98167837-.85)/.45)]),
            ("左侧字体",[("标题字号","89%",(.89647275-.8)/.55),("数值字号","96%",(.96344686-.8)/.55)]),
            ("右侧字体",[("计数字号","102%",(1.0216086-.8)/.55),("详情字号","90%",(.90082836-.8)/.55)])]
    for i,(group,sliders) in enumerate(groups):
        x=106+i*399
        c.text(x,641,group,28,WHITE)
        for j,(label,value,pct) in enumerate(sliders):
            y=709+j*112
            c.text(x,y,label,25,WHITE)
            c.text(x+268,y,value,25,GOLD)
            knob=x+10+336*pct
            c.rect((x,y+52,max(x+13,knob-15),y+82),GOLD,15)
            c.rect((min(x+343,knob+15),y+52,x+356,y+82),"#887D54",15)
            c.rect((knob-4,y+39,knob+4,y+95),GOLD,4)
            c.d.ellipse((x+339,y+64,x+346,y+71),fill=GOLD)
    c.text(82,956,"滑块设置基础宽度，长记录自动扩展右侧。显示值截断，存储保留浮点精度。",24,MUTED)
    adjust_header(c,1091,True)
    c.text(107,1480,"拖动牌面调整顺序",27,WHITE)
    names=["大王","小王","A","K","Q","J","10","9","8","7","6","5","4","3","2"]
    for i,label in enumerate(names):
        row,col=divmod(i,8)
        x=106+col*198
        y=1537+row*105
        c.rect((x,y,x+181,y+86),PANEL,15,outline="#434343",width=2)
        c.center((x,y,x+181,y+86),label,31,WHITE,bold=True)
    c.text(82,1805,"掼蛋默认 2 在最后。未来斗地主预设将 2 放在 A 前；拖动只改变显示顺序。",24,MUTED)
    c.save("adjustment-pages.png")


def tools_update():
    c=Canvas(1470,"记牌器 · 工具与应用更新","按钮可隐藏，重置与停止仍可从工具进入；保留应用自动更新。","04 / 06")
    c.text(70,183,"长按记牌条 / 隐藏按钮（D2）",29,bold=True)
    c.rect((70,241,650,1080),PANEL,25)
    c.text(106,275,"记牌工具",33,GOLD,bold=True)
    for i,label in enumerate(["尺寸调整","牌序调整"]):
        c.row(103,355+i*108,512,label,"›")
    c.row(103,583,512,"显示三家出牌记录",switch=True)
    c.pill((103,718,615,787),"重置本局",False,29)
    c.pill((103,818,615,887),"停止记牌",False,29)
    c.text(277,982,"关闭",29,GOLD)
    c.wrap(81,1126,"隐藏按钮或重置按钮不显示时，长按记牌条仍可打开工具。重置本局、停止记牌继续可用。",551,25,INK,41)
    c.phone(760,184,w=550,h=1130,title="应用更新")
    c.text(797,337,"版本、大小仅为设计示例",23,DIM)
    c.row(788,397,494,"自动更新",switch=True)
    c.row(788,488,494,"仅 Wi-Fi 自动下载",switch=True)
    c.rect((788,611,1282,975),PANEL,22)
    c.text(811,641,"发现新版本",29,GOLD,bold=True)
    c.text(811,701,"1.0.0 → 1.0.1",30,WHITE)
    c.text(811,757,"安装包：36 MB（示例）",24,DIM)
    c.wrap(811,809,"优化记牌稳定性，完善显示效果。",440,25,WHITE)
    c.pill((811,892,1258,952),"下载更新",size=29)
    c.wrap(798,1013,"空闲时下载，记牌期间不弹安装提示。系统需要确认时显示安装界面。",467,25,DIM)
    c.wrap(798,1157,"断网继续记牌；当前服务尚无安装包。",467,24,DIM)
    c.rect((1364,241,1730,1302),"#E8EAE1",23)
    c.text(1395,275,"准确性要求",30,bold=True)
    y=c.wrap(1395,351,"自动识别、确定归属和去重后统一提交。临时画面冲突先自动复核。",305,26,INK,43)
    y=c.wrap(1395,y+40,"无法恢复时停止精确数字显示，提示等待下一局；不猜牌、不让用户补记。",305,26,INK,43)
    c.wrap(1395,y+40,"交付门槛：完整对局逐手零错漏。无法确认或意外中断也计为失败。",305,26,INK,43)
    c.save("tools-update.png")


def visibility_controls():
    c=Canvas(1960,"记牌器 · 按钮与双击隐藏","两个设置独立保存：默认显示重置按钮，默认关闭双击隐藏。所有牌格保持原尺寸。","05 / 06")
    rows=[
        ("默认：两个按钮均显示",False,True,"点击「隐藏」隐藏；未开启双击时，双击内容区不会隐藏。"),
        ("只关闭重置按钮：保留隐藏按钮",False,False,"右端圆钮及占位移除；重置本局仍可从工具进入。"),
        ("只开启双击隐藏：保留重置按钮",True,True,"左端「隐藏」按钮移除；双击牌面 / 记录区域隐藏。"),
        ("开启双击隐藏，并关闭重置按钮",True,False,"只显示记牌内容；两个按钮及占位都移除。")
    ]
    for i,(title,double_tap,reset,note) in enumerate(rows):
        y=184+i*277
        c.text(70,y,title,29,bold=True)
        table(c,(70,y+51,1730,y+234),seats=False)
        overlay(c,84,y+70,double_tap_hide=double_tap,show_reset=reset)
        c.text(99,y+183,note,25,WHITE)
    y=1292
    c.text(70,y,"双击后：仅出现「显示」按钮，记牌继续",29,bold=True)
    table(c,(70,y+51,1730,y+234),seats=False)
    circle(c.im,84,y+70,2.18,"显示")
    c.text(211,y+98,"点击「显示」恢复记牌条",29,WHITE)
    c.text(99,y+183,"隐藏期间完成新局初始化或新增出牌，恢复时显示最新状态。",25,WHITE)
    y=1569
    c.text(70,y,"点击显示后：恢复当前设置，「显示」按钮消失",29,bold=True)
    table(c,(70,y+51,1730,y+234),seats=False)
    overlay(c,84,y+70,double_tap_hide=True,show_reset=False)
    c.text(99,y+183,"此例沿用「双击开 / 重置隐藏」。恢复操作不会重置牌局或改动设置。",25,WHITE)
    c.text(78,1847,"等待条、仅计数及异常提示同样遵从设置；调整页预览不触发隐藏。",25,MUTED)
    c.save("visibility-controls.png")


def history_width():
    c=Canvas(1500,"记牌器 · 默认适中，超长才加宽","三条示意使用相同字号、牌格和圆钮；只比较右侧长度。","06 / 06")
    rows=[
        ("原 APK 默认宽度 · 右侧约 401dp",True,None,None,None),
        ("新默认宽度 · 右侧 320dp，缩短约 20%",False,None,None,None),
        ("极端记录 · 自动扩至约 511dp",False,
         [WIDTH_ANALYSIS["maxHistoryExample"],"K·K·J·J","9·9·8·8"],[27,4,4],
         [0,0,1,4,0,4,0,4,4,4,4,4,4,5,8]),
    ]
    scale=1.86
    for i,(title,reference,histories,counts,values) in enumerate(rows):
        y=180+i*290
        c.text(70,y,title,29,bold=True)
        table(c,(70,y+54,1730,y+242),seats=False)
        total=overlay(c,86,y+72,scale=scale,reference_width=reference,histories=histories,counts=counts,values=values)
        note=["同样的短记录，原右侧留白较多。", "平常固定基础长度，左侧 15 列宽度不变。", "27 张全部单出、26 个分隔点；最宽文字约 467dp，末尾留约 12dp。 "][i]
        c.text(99,y+179,note,25,WHITE)
        assert 86+total < 1730
    c.rect((70,1084,1730,1376),"#E8EAE1",20)
    c.text(105,1110,"默认右侧 = 标签和计数 32dp + 文字空间 276dp + 边距 12dp",29,bold=True)
    c.wrap(105,1170,"只有已确认的记录超过基础长度才扩展；同一局不因识别临时变化缩回。新一局恢复基础宽度。放大字体时重新测量，始终限制在手机可见区域内。",1580,27,INK,43)
    c.text(105,1296,"320dp 是当前设计基线，适配多数对局的目标仍需真机录屏验证。",25,MUTED)
    c.save("history-width.png")


if __name__ == "__main__":
    if not OVERLAY_FONT.is_file():
        raise SystemExit("Set JPQ_OVERLAY_FONT to the official DingTalk JinBuTi font obtained locally.")
    assert len(set(LABELS)) == 15
    assert sum(VALUES) + 13 + 14 + 14 == 81
    assert DEFAULTS["twoDeckAdjustment"]["contentWidthScale"] == 1.1578497
    start_settings()
    overlay_states()
    adjustment()
    tools_update()
    visibility_controls()
    history_width()
    print("Created 6 static design drawings in docs/design/review")
