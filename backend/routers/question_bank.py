import os
import shutil
from fastapi import APIRouter, UploadFile, File, Depends
from sqlalchemy.orm import Session
from models import get_db
from services.excel_importer import import_from_excel, clear_bank, get_stats
from config import UPLOAD_DIR

router = APIRouter()


@router.post("/import")
async def import_excel(file: UploadFile = File(...), db: Session = Depends(get_db)):
    if not file.filename or not file.filename.endswith(('.xlsx', '.xls')):
        return {"success": False, "message": "仅支持 .xlsx 或 .xls 文件"}

    file_path = UPLOAD_DIR / f"question_bank_{file.filename}"
    with open(file_path, "wb") as f:
        shutil.copyfileobj(file.file, f)

    result = import_from_excel(str(file_path), db)
    os.remove(file_path)

    return {
        "success": True,
        "message": f"导入完成：成功 {result['imported']} 条，跳过 {result['skipped']} 条",
        **result
    }


@router.get("/stats")
def question_bank_stats(db: Session = Depends(get_db)):
    return {"success": True, "stats": get_stats(db)}


@router.delete("/clear")
def clear_question_bank(db: Session = Depends(get_db)):
    count = clear_bank(db)
    return {"success": True, "message": f"已清空 {count} 条题库记录"}
