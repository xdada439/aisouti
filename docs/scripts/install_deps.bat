@echo off
REM 一键安装 red_to_ans.py 的所有依赖
REM 双击此 bat 即可运行

echo ====================================
echo  安装 red_to_ans.py 依赖
echo ====================================
echo.

where python >nul 2>nul
if errorlevel 1 (
    echo [错误] 未找到 Python！请先安装 Python 3.8+
    echo 下载: https://www.python.org/downloads/
    pause
    exit /b 1
)

python -m pip install --upgrade pip
python -m pip install python-docx PyMuPDF openpyxl beautifulsoup4 python-pptx pywin32

echo.
echo ====================================
echo  安装完成！
echo  现在可以运行: python red_to_ans.py [输入] [输出]
echo ====================================
pause
