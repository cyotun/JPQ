"""Render original static JPQ design drawings; does not edit supplied images.

Run with Pillow and a CJK font. These are review artifacts, not App screenshots.
"""
from pathlib import Path
import json
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs/design/review"
OUT.mkdir(parents=True, exist_ok=True)
FONT = Path(os.environ.get("JPQ_DESIGN_FONT", "C:/Windows/Fonts/msyh.ttc"))
BOLD = Path(os.environ.get("JPQ_DESIGN_BOLD_FONT", "C:/Windows/Fonts/msyhbd.ttc"))
PAPER = "#F5F4F0"
INK = "#222824"
MUTED = "#68726B"
DARK = "#131413"
PANEL = "#252725"
GOLD = "#FFE493"
WHITE = "#F4F5EF"
DIM = "#7B827D"
TEAL = "#167D73"
LABELS = ["大", "小", "A", "K", "Q", "J", "10", "9", "8", "7", "6", "5", "4", "3", "2"]
VALUES = [1, 1, 4, 6, 5, 3, 0, 1, 1, 0, 0, 4, 4, 4, 6]
HISTORY = ["Q·6·77722·AAA99·3", "9·4·小·66688·JJJ1010·大", "6·99944·K·J·10101088·7"]
DEFAULTS = json.loads((ROOT / "docs/research/reference-display-defaults.json").read_text(encoding="utf-8"))


def font(size, bold=False):
    return ImageFont.truetype(str(BOLD if bold else FONT), int(size))


class Canvas:
    def __init__(self, h, title, subtitle, number):
        self.im = Image.new("RGB", (1800, h), PAPER)
        self.d = ImageDraw.Draw(self.im)
        self.text(64, 40, title, 46, bold=True)
        self.text(66, 106, subtitle, 25, MUTED)
        self.text(66, h - 54, "设计确认稿 v1 · 2026-10-11 · 静态示意，手机端尚未开发", 22, MUTED)
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
        self.rect(box, GOLD if active else PANEL, 22)
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


def overlay(c, x, y, gridw=560, rightw=925, full=True):
    h, button, gap = 90, 84, 8
    total = button*2 + gap*2 + gridw + (rightw if full else 0)
    c.rect((x, y, x+total, y+h), "#202220", 26, outline="#0B100D", width=3)
    c.d.ellipse((x,y+3,x+button,y+button+3), fill=GOLD, outline="#222824", width=3)
    c.center((x,y+3,x+button,y+button+3),"隐藏",27,bold=True)
    gx = x+button+gap
    cw=gridw/15
    for i,(label,value) in enumerate(zip(LABELS,VALUES)):
        left=gx+i*cw
        c.rect((left,y+3,left+cw-1,y+44),"#4B4D4B",0)
        c.rect((left,y+46,left+cw-1,y+87),"#434343",0)
        c.center((left,y+3,left+cw-1,y+44),label,29,WHITE)
        threshold=2 if label in ["大","小"] else 4
        color = "#FFFF3140" if value>=threshold else ("#FFFFFF" if value else "#5F5F5F")
        # PIL RGB uses the actual red channel; source ARGB is kept in defaults JSON.
        if color=="#FFFF3140": color="#FF3140"
        c.center((left,y+44,left+cw-1,y+87),str(value),30,color,bold=True)
    if full:
        rx=gx+gridw+5
        for i,(seat,count,history) in enumerate(zip("上对下",[13,14,14],HISTORY)):
            ry=y+3+i*28
            c.text(rx,ry-2,seat,24,WHITE)
            c.rect((rx+28,ry,rx+67,ry+27),GOLD,0)
            c.center((rx+28,ry,rx+67,ry+27),str(count),25,bold=True)
            c.text(rx+75,ry-1,history,25,WHITE,maxw=rightw-86)
    bx=x+total-button
    c.d.ellipse((bx,y+3,bx+button,y+button+3),fill=GOLD,outline="#222824",width=3)
    c.center((bx,y+3,bx+button,y+button+3),"重置",27,bold=True)
    return total


def table(c, box):
    x,y,r,b=box
    c.rect(box,TEAL,26)
    c.text(x+35,y+155,"上家",23,"#82B8AE")
    c.text((x+r)/2-50,y+126,"对家 · 队友",23,"#82B8AE")
    c.text(r-125,y+155,"下家",23,"#82B8AE")


def start_settings():
    c=Canvas(1410,"JPQ · 启动与设置","保留原纵向配置顺序和黑金样式，移除会员与营销区域。首页简化属于本次提议 D1。","01 / 05")
    for x,title,nav in [(70,"大掼蛋记牌器","记牌"),(655,"权限设置",None),(1240,"设置","设置")]:
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
    c.row(x+28,390,434,"显示三家出牌记录",switch=True)
    c.row(x+28,480,434,"尺寸调整","›")
    c.row(x+28,570,434,"牌序调整","›")
    c.text(x+35,690,"应用设置",25,DIM)
    c.row(x+28,739,434,"权限设置","›")
    c.row(x+28,829,434,"应用更新","检查更新")
    c.row(x+28,919,434,"配置备份","›")
    c.row(x+28,1009,434,"关于 JPQ","›")
    c.wrap(76,1234,"开始 → 系统共享授权 → 返回大掼蛋 → 等待有效牌局 → 自动记牌。正常初始化不强制核对。",1640,26,INK)
    c.save("start-settings.png")


def overlay_states():
    c=Canvas(1510,"JPQ · 等待与记牌悬浮窗","沿用原两圆钮、顶部细条和混合计数配色；下方桌面为自绘示意。","02 / 05")
    c.text(70,184,"开始后 · 等待有效牌局",29,bold=True)
    table(c,(70,237,1730,475))
    c.d.ellipse((86,255,174,343),fill=GOLD,outline=INK,width=4)
    c.center((86,255,174,343),"隐藏",29,bold=True)
    c.rect((187,252,1100,350),GOLD,24,outline=INK,width=4)
    c.center((220,261,1070,312),"进入牌局后，自动开始记牌",36,bold=True)
    c.center((216,307,1071,344),"若牌局已开始仍未显示，请检查权限或重新开始记牌",24)
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
    c.text(75,1272,"隐藏后只留「显示」圆钮；恢复时显示当前状态，不停止识别。",25,INK)
    c.text(75,1314,"新增入口提议 D2：长按「隐藏」打开工具，拖动条内空白处移动；待你确认。",25,MUTED)
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
    c=Canvas(1900,"JPQ · 两页调整，沿用原默认值","尺寸和牌序保持两个标签；重置恢复当前草稿，保存并退出后正式应用。","03 / 05")
    adjust_header(c,245)
    groups=[("窗口",[("总宽度","115%",(1.1578497-.8)/.55),("左右比例","37:63",(.3703267-.3)/.3)]),
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
            c.rect((x,y+54,x+356,y+70),"#8A8055",8)
            c.rect((x,y+54,x+356*pct,y+70),GOLD,8)
            c.d.ellipse((x+356*pct-12,y+49,x+356*pct+12,y+75),fill=GOLD)
    c.text(82,956,"显示值截断，存储保留原浮点精度；关闭右侧时相关滑块置灰且保留设置。",24,MUTED)
    adjust_header(c,1091,True)
    c.text(107,1480,"拖动牌面调整顺序",27,WHITE)
    names=["大王","小王","A","K","Q","J","10","9","8","7","6","5","4","3","2"]
    for i,label in enumerate(names):
        row,col=divmod(i,8)
        x=106+col*198
        y=1537+row*105
        c.rect((x,y,x+181,y+86),PANEL,15,outline="#4B504B",width=2)
        c.center((x,y,x+181,y+86),label,31,WHITE,bold=True)
    c.text(82,1805,"掼蛋默认 2 在最后。未来斗地主预设将 2 放在 A 前；拖动只改变显示顺序。",24,MUTED)
    c.save("adjustment-pages.png")


def correction_update():
    c=Canvas(1570,"JPQ · 新增交互提议","D3：核对与出牌纠错。D4：自动检查、空闲下载、记牌结束后安装提示。","04 / 05")
    c.text(70,187,"出牌纠错 · 横屏下半屏面板",29,bold=True)
    table(c,(70,241,1060,1000))
    overlay(c,84,255,gridw=370,rightw=425)
    c.rect((72,483,1058,998),PANEL,26)
    for i,(seat,active) in enumerate([("上家",True),("对家",False),("下家",False)]):
        c.pill((99+i*163,505,247+i*163,561),seat,active)
    c.text(693,517,"取消",27,WHITE)
    c.pill((802,501,1030,567),"保存并重算",size=26)
    c.text(101,596,"编辑第 4 手：AAA99",26,WHITE)
    c.text(101,640,"实际牌面",24,DIM)
    for i,(label,value) in enumerate([("A","2"),("9","2")]):
        x=100+i*252
        c.rect((x,685,x+223,763),"#363A36",15)
        c.text(x+17,700,label,31,WHITE,bold=True)
        c.text(x+70,705,"−",31,GOLD)
        c.text(x+123,705,value,28,WHITE)
        c.text(x+175,705,"+",28,GOLD)
    c.text(619,697,"删除误记",27,GOLD)
    c.text(811,697,"补记一手",27,GOLD)
    c.rect((101,802,1028,876),"#343A30",16)
    c.text(122,822,"修改预览：上家已出 13 → 12；A 剩余 4 → 5",25,GOLD)
    c.wrap(101,905,"核对时可能遮挡牌面，建议空闲时操作。关闭后存在观测缺口会提示待核对。",918,23,DIM)
    c.rect((70,1041,1060,1406),"#E8EAE1",24)
    c.text(99,1068,"纠错原则",29,bold=True)
    c.wrap(101,1130,"修改出牌事件，历史与统计统一重算。不会只改一个剩余数字。初始手牌核对单独显示总数 / 27。争议项显示待核对，可撤销修订。",902,27,INK,43)
    c.text(101,1318,"示例数据：AAA99 修正为 AA99。",25,MUTED)
    c.phone(1180,184,w=550,h=1250,title="应用更新")
    c.text(1215,342,"以下版本和大小为设计示例",23,DIM)
    c.row(1208,397,494,"自动更新",switch=True)
    c.row(1208,489,494,"仅 Wi-Fi 自动下载",switch=True)
    c.rect((1208,615,1702,1012),PANEL,22)
    c.text(1232,643,"发现新版本",29,GOLD,bold=True)
    c.text(1232,698,"1.0.0 → 1.0.1",31,WHITE)
    c.text(1232,754,"安装包：36 MB（示例）",24,DIM)
    c.wrap(1232,811,"优化大掼蛋记牌稳定性，完善悬浮窗显示。",440,26,WHITE)
    c.pill((1232,926,1678,986),"下载更新",size=29)
    c.wrap(1218,1052,"自动检查每日一次。Wi-Fi 下空闲时下载，对局期间不弹安装提示。",472,25,DIM)
    c.wrap(1218,1186,"校验通过后提示安装；系统可能要求确认。断网继续使用旧版记牌。",472,25,DIM)
    c.save("correction-update.png")


def tools_hand():
    c=Canvas(1380,"JPQ · 工具入口与初始手牌核对","D2 / D3 新增提议；正常牌局直接开始，只在需要时打开核对。","05 / 05")
    c.text(70,183,"长按隐藏圆钮 → 记牌工具",29,bold=True)
    c.rect((70,241,590,1139),PANEL,25)
    c.text(104,271,"记牌工具",33,GOLD,bold=True)
    for i,text in enumerate(["初始手牌核对","出牌纠错","尺寸调整","牌序调整"]):
        c.row(101,349+i*98,457,text,"›")
    c.row(101,753,457,"显示三家出牌记录",switch=True)
    c.pill((101,873,558,940),"重置本局",False,28)
    c.pill((101,962,558,1029),"停止记牌",False,28)
    c.text(212,1075,"关闭面板",27,GOLD)
    c.text(667,183,"核对自己本局初始手牌",29,bold=True)
    c.rect((666,241,1730,1139),PANEL,25)
    c.text(699,274,"初始手牌",33,GOLD,bold=True)
    c.text(1425,282,"总数 27 / 27",26,WHITE)
    c.wrap(700,344,"这里只修改本局开始时的手牌。中途缺失的出牌记录还需要补记，不能只改当前手牌。",980,25,DIM)
    counts=[1,1,3,2,2,2,2,2,2,2,2,2,1,1,2]
    assert sum(counts)==27
    for i,(label,value) in enumerate(zip(LABELS,counts)):
        row,col=divmod(i,5)
        x=701+col*197
        y=460+row*153
        c.rect((x,y,x+180,y+126),"#383B37",15)
        c.center((x,y+7,x+180,y+60),label,30,WHITE,bold=True)
        c.text(x+22,y+74,"−",28,GOLD)
        c.text(x+83,y+72,str(value),28,WHITE)
        c.text(x+140,y+75,"+",27,GOLD)
    c.text(701,937,"取消",28,WHITE)
    c.pill((1270,923,1695,991),"保存并重算",size=30)
    c.wrap(700,1041,"总数或牌库校验不通过时不能保存，并显示具体需要修正的牌面。",987,24,DIM)
    c.rect((70,1180,1730,1260),"#E7E9DF",22)
    c.text(98,1205,"待核对：保留已确认数据，不确定项显示 —。查看问题 / 核对，不悄悄把错误显示成 0。",26,INK)
    c.save("tools-hand.png")


if __name__ == "__main__":
    assert len(set(LABELS)) == 15
    assert sum(VALUES) + 13 + 14 + 14 == 81
    assert DEFAULTS["twoDeckAdjustment"]["contentWidthScale"] == 1.1578497
    start_settings()
    overlay_states()
    adjustment()
    correction_update()
    tools_hand()
    print("Created 5 static design drawings in docs/design/review")
