from PIL import Image, ImageDraw, ImageFont
import os

W, H = 1080, 2376
img = Image.new('RGB', (W, H), '#F5F6FA')
draw = ImageDraw.Draw(img)

font_paths = [
    'C:/Windows/Fonts/msyh.ttc',
    'C:/Windows/Fonts/simsun.ttc',
    'C:/Windows/Fonts/simhei.ttf',
]
font_big = None
font_body = None
font_small = None
for fp in font_paths:
    if os.path.exists(fp):
        try:
            font_big = ImageFont.truetype(fp, 36)
            font_body = ImageFont.truetype(fp, 32)
            font_small = ImageFont.truetype(fp, 26)
            break
        except:
            continue

if font_big is None:
    font_big = font_body = font_small = ImageFont.load_default()

# Status bar
draw.rectangle([0, 0, W, 80], fill='#FFFFFF')
draw.text((60, 22), '11:42', fill='#1A1A1A', font=font_small)
draw.text((W-200, 22), '5G  ████   85%', fill='#1A1A1A', font=font_small)

# Top bar
draw.rectangle([0, 80, W, 160], fill='#2F6BFF')
draw.text((40, 98), '←  返回', fill='#FFFFFF', font=font_body)

# Question type tag - 多选题
draw.rectangle([40, 190, 160, 240], fill='#FFF3E0')
draw.text((55, 193), '多选题', fill='#E65100', font=font_small)

# Question number
draw.text((40, 270), '第 10 题 / 共 20 题', fill='#999999', font=font_small)

# Stem
stem = 'Android四大组件包括哪些？（多选）'
draw.text((40, 340), stem, fill='#1A1A1A', font=font_body)

# Options - multiple choice uses checkboxes
options = [
    ('A', 'Activity'),
    ('B', 'Service'),
    ('C', 'BroadcastReceiver'),
    ('D', 'ContentProvider'),
    ('E', 'Fragment'),
]
y_start = 460
for i, (letter, text) in enumerate(options):
    y = y_start + i * 100
    # Square checkbox
    cx, cy = 70, y + 32
    draw.rectangle([cx-20, cy-20, cx+20, cy+20], outline='#CCCCCC', width=2)
    draw.text((cx-6, cy-14), letter, fill='#555555', font=font_body)
    # Text
    draw.text((120, y + 10), text, fill='#1A1A1A', font=font_body)

# Hint text
draw.text((40, y_start + 5*100 + 40), '（多选题，选择所有正确答案）', fill='#999999', font=font_small)

# Bottom buttons
draw.rectangle([0, H-200, W, H], fill='#FFFFFF')
draw.rectangle([40, H-180, W/2-20, H-120], fill='#FFFFFF', outline='#2F6BFF', width=2)
draw.text((W/4-55, H-165), '上一题', fill='#2F6BFF', font=font_body)
draw.rectangle([W/2+20, H-180, W-40, H-120], fill='#2F6BFF')
draw.text((3*W/4-55, H-165), '下一题', fill='#FFFFFF', font=font_body)

draw.rectangle([0, H-100, W, H], fill='#FAFAFA')
btns = ['收藏', '答题卡', '进度']
for i, name in enumerate(btns):
    x = W//3 * i + W//6
    draw.text((x-25, H-70), name, fill='#888888', font=font_small)

out = 'D:/AIProjects/ai-souti/test_multi_choice_screenshot.png'
img.save(out)
print(f'Saved: {out}')
