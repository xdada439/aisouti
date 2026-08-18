import openpyxl
from openpyxl.styles import Font, Alignment

wb = openpyxl.Workbook()
ws = wb.active
ws.title = '题库'

# Header
headers = ['question_id', 'question_type', 'stem', 'option_a', 'option_b', 'option_c', 'option_d', 'option_e', 'answer', 'chapter', 'source_file']
ws.append(headers)

for cell in ws[1]:
    cell.font = Font(bold=True)
    cell.alignment = Alignment(horizontal='center')

questions = [
    # === 单选题 7道 ===
    ['Q001', 'single', '以下哪个是Java的基本数据类型？', 'String', 'int', 'ArrayList', 'HashMap', '', 'B', '编程基础', 'test_bank'],
    ['Q002', 'single', 'HTTP协议默认使用哪个端口？', '21', '22', '80', '443', '', 'C', '网络通信', 'test_bank'],
    ['Q003', 'single', '在Android开发中，Activity的onCreate之后紧接着调用的是？', 'onResume', 'onStart', 'onPause', 'onStop', '', 'B', 'Android开发', 'test_bank'],
    ['Q004', 'single', 'Git中用于查看提交历史的命令是？', 'git status', 'git diff', 'git log', 'git branch', '', 'C', '版本控制', 'test_bank'],
    ['Q005', 'single', '下列哪种排序算法平均时间复杂度为O(nlogn)？', '冒泡排序', '插入排序', '快速排序', '选择排序', '', 'C', '数据结构', 'test_bank'],
    ['Q006', 'single', 'Python中定义函数的关键字是？', 'func', 'def', 'function', 'define', '', 'B', 'Python基础', 'test_bank'],
    ['Q007', 'single', 'Linux中修改文件权限的命令是？', 'chown', 'chmod', 'chgrp', 'ls', '', 'B', 'Linux基础', 'test_bank'],

    # === 多选题 5道 ===
    ['Q008', 'multiple', '以下哪些是面向对象编程的三大特性？（多选）', '封装', '继承', '多态', '递归', '', 'ABC', '编程基础', 'test_bank'],
    ['Q009', 'multiple', '下列哪些属于关系型数据库？（多选）', 'MySQL', 'MongoDB', 'PostgreSQL', 'Redis', '', 'AC', '数据库', 'test_bank'],
    ['Q010', 'multiple', 'Android四大组件包括哪些？（多选）', 'Activity', 'Service', 'BroadcastReceiver', 'ContentProvider', 'Fragment', 'ABCD', 'Android开发', 'test_bank'],
    ['Q011', 'multiple', '以下哪些是HTTP的请求方法？（多选）', 'GET', 'POST', 'SEND', 'DELETE', '', 'ABD', '网络通信', 'test_bank'],
    ['Q012', 'multiple', 'Kotlin中声明不可变变量的关键字有？（多选）', 'var', 'val', 'const val', 'let', '', 'BC', 'Kotlin基础', 'test_bank'],

    # === 判断题 5道 ===
    ['Q013', 'judge', 'TCP协议是面向无连接的传输层协议。', '', '', '', '', '', '错', '网络通信', 'test_bank'],
    ['Q014', 'judge', '在Java中，一个类可以实现多个接口。', '', '', '', '', '', '对', '编程基础', 'test_bank'],
    ['Q015', 'judge', 'Git中git pull等同于git fetch加git merge。', '', '', '', '', '', '对', '版本控制', 'test_bank'],
    ['Q016', 'judge', 'Android的Service默认运行在子线程中。', '', '', '', '', '', '错', 'Android开发', 'test_bank'],
    ['Q017', 'judge', 'Python列表是可变的，元组是不可变的。', '', '', '', '', '', '对', 'Python基础', 'test_bank'],

    # === 填空题 3道 ===
    ['Q018', 'blank', 'Java中所有类的父类是____。', '', '', '', '', '', 'Object', '编程基础', 'test_bank'],
    ['Q019', 'blank', 'Android中存储简单键值对数据的类是____。', '', '', '', '', '', 'SharedPreferences', 'Android开发', 'test_bank'],
    ['Q020', 'blank', 'Linux中查看当前工作目录的命令是____。', '', '', '', '', '', 'pwd', 'Linux基础', 'test_bank'],
]

for row in questions:
    ws.append(row)

# Column widths
ws.column_dimensions['A'].width = 14
ws.column_dimensions['B'].width = 14
ws.column_dimensions['C'].width = 55
ws.column_dimensions['D'].width = 20
ws.column_dimensions['E'].width = 20
ws.column_dimensions['F'].width = 20
ws.column_dimensions['G'].width = 20
ws.column_dimensions['H'].width = 14
ws.column_dimensions['I'].width = 14
ws.column_dimensions['J'].width = 14
ws.column_dimensions['K'].width = 14

path = r'D:\AIProjects\ai-souti\test_question_bank_20.xlsx'
wb.save(path)
print(f'Saved: {path}')
print(f'Total questions: {len(questions)}')
print(f'Single: 7, Multiple: 5, Judge: 5, Blank: 3')
