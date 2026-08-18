import logging
from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy.orm import Session
from typing import Optional
from models import get_db
from services.question_matcher import match_question
from services.rag_answer import generate_answer

logger = logging.getLogger("search")
router = APIRouter()


class SearchRequest(BaseModel):
    questionType: str = ""
    questionNumber: str = ""
    questionText: str = ""
    options: dict[str, str] = {}
    subject: Optional[str] = None


@router.post("/search")
def search_question(req: SearchRequest, db: Session = Depends(get_db)):
    logger.info("=== /api/search START ===")
    logger.info("questionType: %s", req.questionType)
    logger.info("questionText: %s", req.questionText[:200])
    logger.info("options: %s", req.options)
    logger.info("subject: '%s'", req.subject)

    # Step 1: 精准题库匹配
    logger.info("--- Step 1: 精准题库匹配 ---")
    result = match_question(
        db=db,
        question_type=req.questionType,
        stem=req.questionText,
        options=req.options,
        subject=req.subject or "",
    )

    if result:
        logger.info("精准题库命中: answer=%s source=%s confidence=%.3f",
                     result["answer"], result["source"], result["confidence"])
        return {"success": True, **result}

    logger.info("精准题库: 未命中，进入模糊匹配")

    # Step 2: 模糊匹配 RAG检索
    logger.info("--- Step 2: 模糊匹配 RAG ---")
    rag_result = generate_answer(
        question_text=req.questionText,
        options=req.options,
        subject=req.subject or "",
    )

    logger.info("模糊匹配结果: answer='%s' source='%s' confidence=%.3f chunks=%d matchDetail='%s'",
                 rag_result["answer"][:100],
                 rag_result["source"],
                 rag_result["confidence"],
                 len(rag_result.get("chunks", [])),
                 rag_result.get("matchDetail", ""))

    return {
        "success": True,
        "answer": rag_result["answer"],
        "source": rag_result["source"],
        "confidence": rag_result["confidence"],
        "matchDetail": rag_result.get("matchDetail", ""),
        "matchedQuestion": None,
        "knowledgeChunks": rag_result.get("chunks", [])
    }
