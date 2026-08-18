import os
import json
import logging
import shutil
from fastapi import APIRouter, UploadFile, File, Form, Depends
from pydantic import BaseModel
from sqlalchemy.orm import Session
from models import get_db
from models.knowledge_base import KnowledgeBase, KnowledgeChunk
from services.doc_parser import parse_file, chunk_text
from services.vector_search import index_chunks, remove_chunks_by_subject, get_all_collections_info
from services.vector_search import search_relevant_chunks as vector_search_chunks
from config import UPLOAD_DIR, CHUNK_SIZE, CHUNK_OVERLAP, UPLOAD_ALLOWED_EXTENSIONS, CHROMA_DIR

logger = logging.getLogger("knowledge_base")
router = APIRouter()


class TestSearchRequest(BaseModel):
    query: str = ""
    subject: str = ""


@router.get("/list")
def list_knowledge_bases(db: Session = Depends(get_db)):
    items = db.query(KnowledgeBase).order_by(KnowledgeBase.created_at.desc()).all()
    return {
        "success": True,
        "items": [
            {
                "id": kb.id,
                "name": kb.name,
                "subject": kb.subject,
                "tags": kb.tags.split(",") if kb.tags else [],
                "enabled": kb.enabled,
                "fileCount": kb.file_count,
                "chunkCount": kb.chunk_count,
                "createdAt": kb.created_at.isoformat() if kb.created_at else "",
            }
            for kb in items
        ]
    }


@router.post("/upload")
async def upload_knowledge_base(
    file: UploadFile = File(...),
    name: str = Form(""),
    subject: str = Form(""),
    tags: str = Form(""),
    db: Session = Depends(get_db)
):
    if not file.filename:
        return {"success": False, "message": "未选择文件"}

    ext = os.path.splitext(file.filename)[1].lower()
    if ext not in UPLOAD_ALLOWED_EXTENSIONS:
        return {"success": False, "message": f"不支持的文件格式: {ext}"}

    kb_name = name or os.path.splitext(file.filename)[0]

    # 保存文件
    file_path = UPLOAD_DIR / f"kb_{file.filename}"
    with open(file_path, "wb") as f:
        shutil.copyfileobj(file.file, f)

    try:
        # 解析文本
        logger.info("=== Upload START: %s ===", file.filename)
        text = parse_file(str(file_path))
        logger.info("Parsed text length: %d chars", len(text))

        # 切块
        chunks = chunk_text(text, CHUNK_SIZE, CHUNK_OVERLAP)
        logger.info("Chunked into %d pieces (chunk_size=%d, overlap=%d)", len(chunks), CHUNK_SIZE, CHUNK_OVERLAP)
        for i, c in enumerate(chunks[:3]):
            logger.info("  chunk[%d] preview: %s", i, c[:100])

        # 查找或创建知识库
        kb = db.query(KnowledgeBase).filter(KnowledgeBase.name == kb_name).first()
        if not kb:
            kb = KnowledgeBase(
                name=kb_name,
                subject=subject,
                tags=tags,
                file_count=0,
                chunk_count=0
            )
            db.add(kb)
            db.flush()
            logger.info("Created new KB: id=%d name=%s subject=%s", kb.id, kb_name, subject)
        else:
            logger.info("Using existing KB: id=%d name=%s", kb.id, kb_name)

        # 向量索引
        subj = subject or kb_name
        logger.info("Indexing chunks with subject='%s'...", subj)
        embedding_ids = index_chunks(
            subject=subj,
            chunks=chunks,
            source_file=file.filename
        )
        logger.info("Got %d embedding_ids back from ChromaDB", len(embedding_ids))

        # 存储chunks
        for i, chunk in enumerate(chunks):
            db_chunk = KnowledgeChunk(
                kb_id=kb.id,
                content=chunk,
                chunk_index=i,
                source_file=file.filename,
                embedding_id=embedding_ids[i] if i < len(embedding_ids) else ""
            )
            db.add(db_chunk)

        kb.file_count += 1
        kb.chunk_count += len(chunks)
        kb.subject = subject or kb.subject
        kb.tags = tags or kb.tags
        db.commit()
        logger.info("DB commit OK: kb_id=%d file_count=%d chunk_count=%d", kb.id, kb.file_count, kb.chunk_count)

        return {
            "success": True,
            "message": f"上传成功：{kb_name}，解析 {len(chunks)} 个文本块",
            "kbId": kb.id,
            "chunkCount": len(chunks),
            "embeddingCount": len(embedding_ids),
            "subject": subj,
        }
    except Exception as e:
        db.rollback()
        logger.error("Upload FAILED: %s", e, exc_info=True)
        return {"success": False, "message": f"处理失败: {str(e)}"}
    finally:
        if file_path.exists():
            os.remove(file_path)
            logger.info("Cleaned up temp file: %s", file_path)


@router.put("/{kb_id}")
def update_knowledge_base(
    kb_id: int,
    name: str = Form(""),
    subject: str = Form(""),
    tags: str = Form(""),
    enabled: bool = Form(None),
    db: Session = Depends(get_db)
):
    kb = db.query(KnowledgeBase).filter(KnowledgeBase.id == kb_id).first()
    if not kb:
        return {"success": False, "message": "知识库不存在"}

    old_subject = kb.subject
    if name:
        kb.name = name
    if subject:
        kb.subject = subject
    if tags:
        kb.tags = tags
    if enabled is not None:
        kb.enabled = enabled
    db.commit()

    # 如果科目变更，需要重建索引（这里做简单处理）
    if subject and subject != old_subject:
        pass  # 后续手动重建

    return {"success": True, "message": "更新成功"}


@router.delete("/{kb_id}")
def delete_knowledge_base(kb_id: int, db: Session = Depends(get_db)):
    kb = db.query(KnowledgeBase).filter(KnowledgeBase.id == kb_id).first()
    if not kb:
        return {"success": False, "message": "知识库不存在"}

    remove_chunks_by_subject(kb.subject)
    db.query(KnowledgeChunk).filter(KnowledgeChunk.kb_id == kb_id).delete()
    db.delete(kb)
    db.commit()

    return {"success": True, "message": f"已删除知识库「{kb.name}」"}


@router.post("/{kb_id}/reindex")
def reindex_knowledge_base(kb_id: int, db: Session = Depends(get_db)):
    kb = db.query(KnowledgeBase).filter(KnowledgeBase.id == kb_id).first()
    if not kb:
        return {"success": False, "message": "知识库不存在"}

    chunks = db.query(KnowledgeChunk).filter(KnowledgeChunk.kb_id == kb_id).all()
    if not chunks:
        return {"success": False, "message": "无文本块可索引"}

    remove_chunks_by_subject(kb.subject)
    texts = [c.content for c in chunks]
    embedding_ids = index_chunks(
        subject=kb.subject or kb.name,
        chunks=texts,
        source_file="reindex"
    )

    for i, c in enumerate(chunks):
        c.embedding_id = embedding_ids[i] if i < len(embedding_ids) else ""
    db.commit()

    return {"success": True, "message": f"重建索引完成：{len(chunks)} 个文本块"}


@router.get("/subjects")
def list_subjects(db: Session = Depends(get_db)):
    from sqlalchemy import distinct
    subjects = (
        db.query(distinct(KnowledgeBase.subject))
        .filter(KnowledgeBase.subject != "", KnowledgeBase.enabled == True)
        .all()
    )
    return {
        "success": True,
        "subjects": [s[0] for s in subjects if s[0]]
    }


@router.post("/test-search")
def test_search(req: TestSearchRequest):
    """直接测试向量检索，不经过题库匹配和RAG AI"""
    logger.info("=== Test Search ===")
    logger.info("query: %s", req.query)
    logger.info("subject: '%s'", req.subject)

    chunks = vector_search_chunks(
        subject=req.subject or "",
        query=req.query,
        top_k=5
    )

    return {
        "success": True,
        "query": req.query,
        "subject": req.subject or "(empty)",
        "result_count": len(chunks),
        "chunks": [
            {
                "score": c["score"],
                "content": c["content"][:300],
                "source_file": c.get("source_file", "")
            }
            for c in chunks
        ]
    }


@router.get("/debug")
def debug_chromadb(db: Session = Depends(get_db)):
    """ChromaDB 自检 + SQL数据库状态"""
    # ChromaDB info
    collections = get_all_collections_info()
    total_chunks_chroma = sum(c.get("count", 0) for c in collections)

    # SQL info
    kbs = db.query(KnowledgeBase).all()
    chunks_sql = db.query(KnowledgeChunk).count()

    return {
        "success": True,
        "chroma_path": str(CHROMA_DIR),
        "collections": collections,
        "chroma_total_chunks": total_chunks_chroma,
        "sql_kb_count": len(kbs),
        "sql_chunk_count": chunks_sql,
        "kbs": [
            {
                "id": kb.id,
                "name": kb.name,
                "subject": kb.subject,
                "enabled": kb.enabled,
                "file_count": kb.file_count,
                "chunk_count": kb.chunk_count,
            }
            for kb in kbs
        ]
    }
