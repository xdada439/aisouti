@echo off
chcp 65001 >nul
REM check_xlsx.bat — 双击或拖文件到这个 bat 上就能校验 Excel
REM 校验通过 → 显示绿色"全部通过"
REM 校验不通过 → 显示错误明细 + 暂停让你看

setlocal

if "%~1"=="" (
    echo.
    echo ====================================
    echo  题库 Excel 校验工具
    echo ====================================
    echo.
    echo 用法：
    echo   方式 1：把 .xlsx 文件拖到这个 bat 上
    echo   方式 2：在命令行 check_xlsx.bat 文件路径.xlsx
    echo.
    pause
    exit /b 1
)

set SCRIPT_DIR=%~dp0
set PYTHON_SCRIPT=%SCRIPT_DIR%validate_bank.py

if not exist "%PYTHON_SCRIPT%" (
    echo [错误] 找不到 validate_bank.py
    echo 期望路径：%PYTHON_SCRIPT%
    pause
    exit /b 1
)

where python >nul 2>nul
if errorlevel 1 (
    echo [错误] 未安装 Python
    echo 下载：https://www.python.org/downloads/
    pause
    exit /b 1
)

echo.
echo ====================================
echo  校验文件：%~nx1
echo ====================================
echo.

python "%PYTHON_SCRIPT%" "%~1" --sample 10

echo.
echo ====================================
pause
