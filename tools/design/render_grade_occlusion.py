"""Self-draw grade-occlusion review art; never reads or edits user screenshots."""
from pathlib import Path
import hashlib
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/design/grade-occlusion'
FONT = ROOT / 'app/src/main/assets/overlay.ttf'
assert hashlib.sha256(FONT.read_bytes()).hexdigest() == '6f6a1e15f33d559ff3d0d7dcc8bd9ab25a6dbd8e9bcd29865daf7615a17ea284'
GOLD, PANEL, CELL = '#FFE395', '#262626', '#434343'
RANKS = ['大','小','A','K','Q','J','10','9','8','7','6','5','4','3','2']
NUMBERS = [1,1,6,4,6,5,3,0,1,1,0,0,4,4,4]

def ui(draw, xy, text, size=24, fill='#BBB', bold=False):
    f = ImageFont.truetype('C:/Windows/Fonts/msyhbd.ttc' if bold else 'C:/Windows/Fonts/msyh.ttc', size)
    draw.text(xy, text, font=f, fill=fill)

def center(im, box, text, size, fill='#FFF'):
    d = ImageDraw.Draw(im); f = ImageFont.truetype(str(FONT), round(size))
    b = d.textbbox((0,0), text, font=f)
    d.text(((box[0]+box[2]-b[0]-b[2])/2,(box[1]+box[3]-b[1]-b[3])/2), text, font=f, fill=fill)

def scene(state):
    s=1.6; im=Image.new('RGB',(1600,292),'#086E62'); d=ImageDraw.Draw(im)
    # A shallow table crop is enough to inspect the top HUD and counter; all
    # three panels use the same fictional cards, counts and level-card values.
    for x,label,color in [(8,'我方','#2484A6'),(60,'对方','#929A95')]:
        d.rounded_rectangle((x*s,5*s,(x+49)*s,58*s),radius=3*s,fill='#F0F0F0',outline='#494D48',width=2)
        d.rectangle((x*s,5*s,(x+49)*s,27*s),fill=color)
        center(im,(x*s,5*s,(x+49)*s,27*s),label,15*s)
        center(im,(x*s,28*s,(x+49)*s,58*s),'3',27*s,color)
    center(im,(905*s,10*s,985*s,35*s),'规则　00:49',12*s,'#B6D4CB')
    d.rounded_rectangle((477*s,108*s,531*s,170*s),radius=4*s,fill='#284D58',outline='#4DCEF3',width=3)
    center(im,(477*s,115*s,531*s,163*s),'队友',12*s)
    # The safe target remains in the top strip, above avatars and play regions.
    bar_x=(148 if state=='moved' else 64)*s; y=6*s
    def circle(x, label):
        d.ellipse((x,y,x+44*s,y+44*s),fill=GOLD,outline=PANEL,width=round(2*s))
        center(im,(x,y,x+44*s,y+44*s),label,16*s,'#000')
    circle(bar_x,'隐藏'); x=bar_x+48*s
    d.rounded_rectangle((x,y,x+568*s,y+46*s),radius=11*s,fill=PANEL,outline='#000',width=2)
    x+=s; cell=(235-14)/15
    for i,rank in enumerate(RANKS):
        a=x+i*(cell+1)*s
        for row in range(2):
            rect=(a,y+(1+22*row)*s,a+cell*s,y+(22+22*row)*s)
            d.rectangle(rect,fill=CELL)
            if row==0:
                for j in range(round(21*s)):
                    n=round(109-(109-67)*j/(21*s)); d.line((a,rect[1]+j,a+cell*s,rect[1]+j),fill=(n,n,n))
            color='#FF3140' if NUMBERS[i]>=(2 if i<2 else 4) else '#5F5F5F' if NUMBERS[i]==0 else '#FFF'
            center(im,rect,rank if row==0 else str(NUMBERS[i]),(14 if row==0 else 16)*s,'#FFF' if row==0 else color)
    x+=236*s
    d.rectangle((x,y+s,x+16*s,y+45*s),fill=CELL)
    for j in range(round(21*s)):
        n=round(109-(109-67)*j/(21*s)); d.line((x,y+s+j,x+16*s,y+s+j),fill=(n,n,n))
    xx=x+8*s; yy=y+23*s
    d.polygon([(xx,yy-6*s),(xx-5*s,yy),(xx,yy+3*s),(xx+5*s,yy)],fill=GOLD)
    d.ellipse((xx-6*s,yy-2*s,xx,yy+3*s),fill=GOLD); d.ellipse((xx,yy-2*s,xx+6*s,yy+3*s),fill=GOLD)
    d.polygon([(xx,yy+1*s),(xx-3*s,yy+6*s),(xx+3*s,yy+6*s)],fill=GOLD)
    x+=17*s
    for i,(seat,count,detail) in enumerate([('上',13,'Q·6·77722·AAA99·3'),('对',14,'9·4·小·66688·JJ1010·大'),('下',14,'6·99944·K·J·101088·7')]):
        yy=y+(1+i*15)*s
        d.rectangle((x,yy,x+14*s,yy+14*s),fill=CELL); center(im,(x,yy,x+14*s,yy+14*s),seat,12*s)
        d.rectangle((x+16*s,yy,x+32*s,yy+14*s),fill=GOLD); center(im,(x+16*s,yy,x+32*s,yy+14*s),str(count),12*s,'#000')
        f=ImageFont.truetype(str(FONT),round(13*s)); d.text((x+34*s,yy-2*s),detail,font=f,fill='#FFF')
    circle(bar_x+620*s,'重置')
    if state=='blocked':
        text='级牌区域被遮挡，请挪开记牌器'
        tx=bar_x+48*s; ty=y+50*s; f=ImageFont.truetype(str(FONT),round(12*s))
        text_width=d.textlength(text,font=f); button_width=70*s; ignore_width=36*s
        w=text_width+18*s+button_width+10*s+ignore_width+5*s
        d.rounded_rectangle((tx,ty,tx+w,ty+30*s),radius=6*s,fill=PANEL,outline='#6D6547',width=1)
        center(im,(tx+9*s,ty+4*s,tx+9*s+text_width,ty+26*s),text,12*s,GOLD)
        if button_width:
            bx=tx+9*s+text_width+10*s
            d.rounded_rectangle((bx,ty+4*s,bx+button_width,ty+26*s),radius=5*s,fill=GOLD)
            center(im,(bx,ty+4*s,bx+button_width,ty+26*s),'一键挪开',12*s,'#111')
            center(im,(bx+button_width+5*s,ty+4*s,bx+button_width+5*s+ignore_width,ty+26*s),'忽略',12*s,GOLD)
    return im

def render():
    im=Image.new('RGB',(1800,1450),'#141514'); d=ImageDraw.Draw(im)
    ui(d,(60,34),'级牌区域遮挡 · 轻提示与一键挪开',40,GOLD,True)
    ui(d,(62,99),'仅为待确认设计 · 不弹窗、不自动跳动 · 自绘示例牌局，非实机截图',24)
    specs=[('blocked','1 · 遮挡时提醒','小号提示贴在记牌条下方；按钮只在安全位置能容纳整条时可用。'),
           ('moved','2 · 点击后挪开','整条移到顶部右侧空位，提醒消失；牌格、数量和级牌值均未改变。'),
           ('ignored','3 · 忽略本次记牌提醒','区域仍被挡住也不再提醒；进入下一局仍忽略，停止后重新开始才恢复。')]
    for i,(state,title,desc) in enumerate(specs):
        y=155+i*400
        d.rounded_rectangle((46,y,1754,y+390),radius=14,fill='#202220',outline='#454545',width=1)
        ui(d,(76,y+15),title,27,GOLD,True)
        im.paste(scene(state),(100,y+54))
        ui(d,(76,y+358),desc,21,'#BBB')
    ui(d,(60,1400),'待确认：遮挡提示样式、全局提示开关、一键挪开的安全位置与空间不足退路、本次记牌忽略。',21,'#AAA')
    OUT.mkdir(parents=True,exist_ok=True); im.save(OUT/'grade-occlusion-preview.png',optimize=True)
    print('Rendered grade-occlusion design preview.')

if __name__=='__main__':render()
