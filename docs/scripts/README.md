# 题库预处理脚本

> 把教材资料中的**红色文字**转成 `【ANS:xxx】` 标记，
> 然后发给 DP / ChatGPT 等 AI 大模型处理成标准 Excel 题库。

---

## 文件清单

| 文件 | 用途 |
|---|---|
| `red_to_ans.py` | **主脚本**：读教材文件 → 输出带标记的 .txt |
| `install_deps.bat` | 一键安装所有 Python 依赖（双击运行）|
| `README.md` | 本文档 |

---

## 支持的输入格式

| 后缀 | 格式 | 说明 |
|---|---|---|
| `.docx` | Word 2007+ | **最常用**，效果最好 |
| `.doc` | Word 97-2003 | Windows + Word 自动转 .docx 处理 |
| `.pdf` | PDF | 用 PyMuPDF 提取，能识别字符颜色 |
| `.xlsx` | Excel | 检查每个单元格字体颜色 |
| `.html / .htm` | HTML 网页 | 解析 `<font color>` 和 `style="color:red"` |
| `.pptx` | PowerPoint 课件 | 提取每段文字颜色 |
| `.rtf` | RTF 富文本 | 简易颜色解析 |

---

## 快速开始

### 第 1 步：装依赖（首次运行前）

**Windows 用户**：双击 `install_deps.bat` 自动装好所有依赖。

或手动：
```bash
pip install python-docx PyMuPDF openpyxl beautifulsoup4 python-pptx pywin32
```

### 第 2 步：处理文件

```bash
# 处理 Word 文档
python red_to_ans.py 教材.docx 教材_marked.txt

# 处理 PDF
python red_to_ans.py 资料.pdf 资料_marked.txt

# 处理 PowerPoint 课件
python red_to_ans.py 课件.pptx 课件_marked.txt

# 处理 Excel 表格
python red_to_ans.py 表格.xlsx 表格_marked.txt
```

### 第 3 步：把输出的 `_marked.txt` 发给 DP

配合主文档 [`QUESTION_BANK_STANDARDIZATION_SPEC.md`](../QUESTION_BANK_STANDARDIZATION_SPEC.md)
里的 "完整 Prompt 模板"，DP 会自动识别 `【ANS:xxx】` 转成填空题答案。

---

## 工作原理

**问题**：教材里答案常用红字标注：

```
通常把【十二指肠】以上的一段称为上消化道。
        ↑红色字
```

DP 收到的是**纯文本**，颜色信息丢失，分不清哪是答案。

**解决**：脚本预先把红字标记成 `【ANS:xxx】`：

```
通常把【ANS:十二指肠】以上的一段称为上消化道。
```

DP 看到 `【ANS:xxx】` 标记，就知道这是填空题答案，自动转换成：

| 题干 | 题型 | 正确答案 |
|---|---|---|
| 通常把____以上的一段称为上消化道。 | 填空题 | 十二指肠 |

---

## 完整工作流

```
原始教材 (各种格式)
       │
       ▼
┌────────────────────┐
│ red_to_ans.py      │  ← 本脚本：颜色 → 标记
│ 输出 _marked.txt   │
└──────────┬─────────┘
           │
           ▼
┌────────────────────┐
│ 发给 DP / ChatGPT  │  ← 配合 spec 文档的 Prompt
│ 上传 marked.txt    │
│ + 标准 Prompt      │
└──────────┬─────────┘
           │
           ▼
┌────────────────────┐
│ DP 输出标准 .xlsx  │  ← 直接可导入 AI 搜题助手
└──────────┬─────────┘
           │
           ▼
┌────────────────────┐
│ 用户中心 → 导入题库 │
└────────────────────┘
```

---

## 输出示例

输入 `教材.docx`（**粗体** 表示原文红字）：

```
肺癌的临床表现包括：**刺激性咳嗽**、**痰中带血**、**胸痛**、**发热**。
```

输出 `教材_marked.txt`：

```
肺癌的临床表现包括：【ANS:刺激性咳嗽】、【ANS:痰中带血】、【ANS:胸痛】、【ANS:发热】。
```

把这个 .txt 发给 DP，DP 会输出：

| 题干 | 题型 | 正确答案 |
|---|---|---|
| 肺癌的临床表现包括：____、____、____、____。 | 填空题 | 刺激性咳嗽\|痰中带血\|胸痛\|发热 |

---

## 红色判定规则

脚本只识别"**纯红/深红/玫红**"系列：
- ✅ 纯红 `(255, 0, 0)`
- ✅ 深红 `(200, 30, 30)`
- ✅ 暗红 `(180, 50, 50)`
- ✅ 玫红 `(220, 50, 100)`
- ❌ 橙黄 `(255, 165, 0)` — 不识别
- ❌ 紫红 `(200, 50, 200)` — 不识别（紫色多）
- ❌ 棕色 `(150, 75, 0)` — 不识别

判定阈值（在脚本里 `is_red_rgb` 函数）：
- R ≥ 150（红够强）
- G ≤ 100, B ≤ 100（绿蓝不亮）
- R 比 G、B 至少高 50（避免灰）

**如果你的教材用了其它颜色（如紫、橙）标答案**，编辑 `red_to_ans.py` 修改 `is_red_*` 函数即可。

---

## 常见问题

### Q1: 提示"缺少依赖"？
A: 双击 `install_deps.bat`，或 `pip install <对应包>`。

### Q2: 输出文件为空？
A: 检查原文件是不是 0 字节、密码保护、或受损。

### Q3: 红字标记数 = 0？
A: 三种可能：
   1. 文档真的没红字
   2. 用了"高亮颜色"而非"字体颜色"（脚本只识别字体颜色）
   3. 用了非红色系颜色（如紫色）→ 改脚本里的颜色规则

### Q4: PDF 处理失败？
A: 部分 PDF 是扫描图片（不是文字层），脚本提不到文字 + 颜色。
   需要先用 OCR 转成可选择文字的 PDF（Adobe Acrobat / ABBYY FineReader）。

### Q5: 教材里有表格，表格里的红字也能识别吗？
A: ✅ .docx / .xlsx / .html 都支持表格内红字识别。

### Q6: 一份大文档处理后 ANS 标记很多，DP 一次处理不完怎么办？
A: 把输出的 .txt 按章节分段，分批发给 DP。DP 处理每批输出一个 .xlsx，
   最后合并 Excel（保留表头一致）。

---

## 高级用法

### 批量处理一个目录的所有文件

新建 `batch.bat`：
```batch
@echo off
for %%F in (*.docx *.pdf *.xlsx) do (
    python red_to_ans.py "%%F" "%%~nF_marked.txt"
)
pause
```

把这个 `batch.bat` 放到资料文件夹下双击，一键处理全部。

### 自定义颜色识别规则

打开 `red_to_ans.py` 找到这段：
```python
def is_red_rgb(r, g, b):
    return r >= 150 and g <= 100 and b <= 100 and r > g + 50 and r > b + 50
```

改成识别紫色：
```python
def is_purple_rgb(r, g, b):
    return r >= 100 and b >= 100 and g <= 80
```

然后把 `is_red_rgb` 换成 `is_purple_rgb` 调用即可。

---

## 路径速查

| 项 | 路径 |
|---|---|
| 脚本本体 | `E:\ziyong\aisouti\docs\scripts\red_to_ans.py` |
| 一键装依赖 | `E:\ziyong\aisouti\docs\scripts\install_deps.bat` |
| 本说明 | `E:\ziyong\aisouti\docs\scripts\README.md` |
| 配套规范 | `E:\ziyong\aisouti\docs\QUESTION_BANK_STANDARDIZATION_SPEC.md` |

