"""Draw new review figures from UI constants; never edits supplied screenshots.

These are design drawings, not Android or browser screenshots. The private
verified font is read locally and is not copied into the public repository.
"""
from pathlib import Path
import hashlib
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/design/straight-flush'
FONT = ROOT / 'app/src/main/assets/overlay.ttf'
assert hashlib.sha256(FONT.read_bytes()).hexdigest() == '6f6a1e15f33d559ff3d0d7dcc8bd9ab25a6dbd8e9bcd29865daf7615a17ea284'
GOLD, PANEL, CELL = '#FFE395', '#262626', '#434343'
RANKS = ['大','小','A','K','Q','J','10','9','8','7','6','5','4','3','2']
NUMS = [1,1,6,4,6,5,3,0,1,1,0,0,4,4,4]
LABELS = {'natural':'可能', 'wild':'需变', 'none':'无', 'unknown':'待确认'}
BADGE_WIDTH=54
ICON_MODE=False

def ui(draw, xy, value, size=25, fill='#EEE', bold=False):
    f = ImageFont.truetype('C:/Windows/Fonts/msyhbd.ttc' if bold else 'C:/Windows/Fonts/msyh.ttc', size)
    draw.text(xy,value,font=f,fill=fill)

def centered(im, box, value, size, fill):
    d = ImageDraw.Draw(im); f = ImageFont.truetype(str(FONT), round(size))
    b = d.textbbox((0,0),value,font=f)
    d.text(((box[0]+box[2]-b[0]-b[2])/2,(box[1]+box[3]-b[1]-b[3])/2),value,font=f,fill=fill)

def badge(im,x,y,s,state):
    d=ImageDraw.Draw(im); d.rectangle((x,y,x+BADGE_WIDTH*s,y+44*s),fill=CELL)
    if ICON_MODE:
        if state=='unknown': return
        tone=GOLD if state in ('natural','wild') else '#626262'
        d.rectangle((x,y,x+BADGE_WIDTH*s,y+44*s),fill=PANEL)
        xx=x+BADGE_WIDTH*s/2; yy=y+22*s
        d.polygon([(xx,yy-7*s),(xx-5*s,yy-1*s),(xx,yy+3*s),(xx+5*s,yy-1*s)],fill=tone)
        d.ellipse((xx-6*s,yy-3*s,xx,yy+3*s),fill=tone)
        d.ellipse((xx,yy-3*s,xx+6*s,yy+3*s),fill=tone)
        d.polygon([(xx,yy+1*s),(xx-3*s,yy+7*s),(xx+3*s,yy+7*s)],fill=tone)
        return
    centered(im,(x,y+2*s,x+54*s,y+19*s),'同花顺',11*s,'#EEE')
    color = GOLD if state=='natural' else '#FFCE77' if state=='wild' else '#AAA'
    centered(im,(x,y+20*s,x+54*s,y+42*s),LABELS[state],15*s,color)

def bar(im,x,y,s=2,placement='inline',state='natural',mode='all'):
    d=ImageDraw.Draw(im)
    def circle(cx,text):
        d.ellipse((cx,y,cx+44*s,y+44*s),fill=GOLD,outline=PANEL,width=round(2*s))
        centered(im,(cx,y,cx+44*s,y+44*s),text,16*s,'#000')
    circle(x,'隐藏'); x+=48*s; start=x
    history=292 if mode=='all' else 34 if mode=='counts' else 0
    total=235+history+(0 if placement=='below' else BADGE_WIDTH+1)+3
    d.rounded_rectangle((x,y,x+total*s,y+46*s),radius=12*s,fill=PANEL,outline='#000',width=2)
    x+=s; cell=(235-14)/15
    for i,r in enumerate(RANKS):
        a=x+i*(cell+1)*s
        for row in range(2):
            b=(a,y+(1+row*22)*s,a+cell*s,y+(22+row*22)*s)
            d.rectangle(b,fill=CELL)
            if row==0:
                for j in range(round(21*s)):
                    n=round(109-(109-67)*j/(21*s)); d.line((a,b[1]+j,a+cell*s,b[1]+j),fill=(n,n,n))
            tone='#FF3140' if NUMS[i]>=(2 if i<2 else 4) else '#5F5F5F' if NUMS[i]==0 else '#FFF'
            centered(im,b,r if row==0 else str(NUMS[i]),(14 if row==0 else 16)*s,'#FFF' if row==0 else tone)
    x+=236*s
    if placement=='inline': badge(im,x,y+s,s,state); x+=(BADGE_WIDTH+1)*s
    if history:
        for i,(seat,n,detail) in enumerate(zip(['上','对','下'],[13,14,14],['Q·6·77722·AAA99·3','9·4·小·66688·JJ1010·大','6·99944·K·J·101088·7'])):
            yy=y+(1+i*15)*s
            d.rectangle((x,yy,x+14*s,yy+14*s),fill=CELL); centered(im,(x,yy,x+14*s,yy+14*s),seat,12*s,'#FFF')
            d.rectangle((x+16*s,yy,x+32*s,yy+14*s),fill=GOLD); centered(im,(x+16*s,yy,x+32*s,yy+14*s),str(n),12*s,'#000')
            if mode=='all':
                f=ImageFont.truetype(str(FONT),round(13*s)); d.text((x+34*s,yy-2*s),detail,font=f,fill='#FFF')
        x+=history*s
    if placement=='end': badge(im,x,y+s,s,state)
    if placement=='below':
        d.rounded_rectangle((start,y+48*s,start+235*s,y+64*s),radius=3*s,fill=PANEL)
        centered(im,(start,y+48*s,start+235*s,y+64*s),'同花顺：'+LABELS[state],11*s,GOLD)
    circle(start+(total+4)*s,'重置')

def options():
    im=Image.new('RGB',(1800,1040),'#141514'); d=ImageDraw.Draw(im)
    ui(d,(62,35),'记牌器 · 同花顺提示位置',42,GOLD,True)
    ui(d,(64,104),'先确认设计再开发 · 原黑金风格 · 以下为自绘示例，非实机截图',24,'#AAA')
    specs=[('A · 牌格旁独立小格（建议）','不增加高度；无论三家记录是否显示，都在牌格旁。','inline','natural'),
           ('B · 放在整条末端','不增加高度；出牌记录扩展后，标识随末端移动。','end','wild'),
           ('C · 牌格下方文字条','少占宽度，但增加约16dp高度，遮挡更多牌局画面。','below','none')]
    for i,(title,desc,place,state) in enumerate(specs):
        y=170+i*265
        d.rounded_rectangle((48,y,1752,y+245),radius=18,fill='#202220',outline='#AA9361' if i==0 else '#454545',width=2)
        ui(d,(78,y+16),title,28,GOLD if i==0 else '#EEE',True)
        d.rounded_rectangle((78,y+69,1722,y+177),radius=9,fill='#166B5C')
        bar(im,106,y+79,2,place,state)
        ui(d,(78,y+(217 if place=='below' else 194)),desc,23,'#BBB')
    ui(d,(62,989),'“可能”表示三家余牌池可成顺，不能证明同一家持有；“需变”包含变牌补位。',22,'#AAA')
    im.save(OUT/'placement-options.png',optimize=True)

def states():
    im=Image.new('RGB',(1800,990),'#141514'); d=ImageDraw.Draw(im)
    ui(d,(62,35),'方案 A · 状态与显示模式',42,GOLD,True)
    ui(d,(64,104),'相同牌格、相同字号；新增约54dp标识，关闭详情后不保留右侧空白',24,'#AAA')
    for i,(state,title,desc) in enumerate([
        ('natural','可能','天然同花顺仍可能存在；同时纳入含变组合。'),
        ('wild','需变','天然组合已排除，但剩余变牌还能补成同花顺。'),
        ('none','无','天然、含变牌的全部合法组合都被排除。'),
        ('unknown','待确认','花色、级牌或变牌数据不全，保持未知。')]):
        y=180+i*185
        d.rounded_rectangle((48,y,1752,y+161),radius=14,fill='#202220')
        ui(d,(78,y+15),title,27,GOLD,True)
        bar(im,300,y+25,2,'inline',state,'counts' if i%2 else 'off')
        ui(d,(78,y+116),desc,23,'#BBB')
    ui(d,(62,944),'等待开局、整局结束或停止记牌后不显示牌型判断；识别不确定不能显示“无”。',22,'#AAA')
    im.save(OUT/'state-preview.png',optimize=True)

def icon_preview():
    global BADGE_WIDTH,ICON_MODE
    BADGE_WIDTH=16; ICON_MODE=True
    im=Image.new('RGB',(1800,870),'#141514'); d=ImageDraw.Draw(im)
    ui(d,(62,35),'同花顺 · 牌格旁图案版',42,GOLD,True)
    ui(d,(64,104),'单个12dp小黑桃 · 无底框、无文字 · 亮表示可能有，暗表示已排除 · 自绘设计图',24,'#AAA')
    for i,(state,mode,title,desc) in enumerate([
        ('natural','all','亮 · 天然或含变牌仍可能成顺','计数和详情均显示；只占约16dp，图标本身约12dp，不增加条高。'),
        ('none','counts','暗 · 已排除所有天然及含变组合','只显示三家数量时，不保留详情的空白区域。'),
        ('wild','off','亮 · 包含变牌能补齐的情况','三家记录关闭，图案仍在牌格旁；没有额外点击或长按面板。')]):
        y=180+i*210
        d.rounded_rectangle((48,y,1752,y+188),radius=14,fill='#202220')
        ui(d,(78,y+14),title,27,GOLD,True)
        bar(im,106,y+63,2,'inline',state,mode)
        ui(d,(78,y+156),desc,22,'#BBB')
    ui(d,(62,818),'花色/级牌/变牌规则不确定时暂不显示图案，不能把未知画成“暗”。',22,'#AAA')
    im.save(OUT/'icon-preview.png',optimize=True)

if __name__=='__main__': OUT.mkdir(parents=True,exist_ok=True); options(); states(); icon_preview(); print(OUT)
