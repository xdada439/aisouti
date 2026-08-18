import json
import logging
import os
from typing import Dict, List

import httpx

from config import BASE_DIR, RAG_AI_ENDPOINT, RAG_AI_KEY, RAG_AI_MODEL, RAG_AI_TIMEOUT, VECTOR_TOP_K
from services.vector_search import search_relevant_chunks

logger = logging.getLogger("rag_answer")


def _load_settings() -> dict:
    settings_file = BASE_DIR / "data" / "settings.json"
    if not settings_file.exists():
        return {}
    try:
        with open(settings_file, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as exc:
        logger.warning("Failed to load AI settings: %s", exc)
        return {}


def _get_index_ai_config() -> dict:
    settings = _load_settings()
    endpoint = settings.get("index_endpoint") or settings.get("rag_endpoint") or os.environ.get("RAG_AI_ENDPOINT") or RAG_AI_ENDPOINT
    key = settings.get("index_key") or settings.get("rag_key") or os.environ.get("RAG_AI_KEY") or RAG_AI_KEY
    model = settings.get("index_model") or settings.get("rag_model") or os.environ.get("RAG_AI_MODEL") or RAG_AI_MODEL
    return {"endpoint": endpoint, "key": key, "model": model}


def _get_fallback_ai_config() -> dict:
    settings = _load_settings()
    endpoint = settings.get("fallback_endpoint") or settings.get("index_endpoint") or settings.get("rag_endpoint") or os.environ.get("FALLBACK_AI_ENDPOINT") or RAG_AI_ENDPOINT
    key = settings.get("fallback_key") or settings.get("index_key") or settings.get("rag_key") or os.environ.get("FALLBACK_AI_KEY") or RAG_AI_KEY
    model = settings.get("fallback_model") or os.environ.get("FALLBACK_AI_MODEL") or "deepseek-chat"
    return {"endpoint": endpoint, "key": key, "model": model}


RAG_SYSTEM_PROMPT = “””你是一个模糊匹配答题助手。请优先依据提供的资料片段回答题目。
规则：
1. 如果资料片段包含答案，直接给出选项字母或答案文字。
2. 如果片段不足以确定答案，回复”未找到”。
3. 不要编造资料中不存在的依据。
4. 回答格式：先给最终答案，再简要说明依据。”””

FALLBACK_SYSTEM_PROMPT = “””你是一个考试搜题助手。当前精准题库和模糊匹配资料均未命中，请根据模型自身知识谨慎作答。兜底模式答案可能不准确，仅作为参考。
要求：
1. 单选/多选题优先给出选项字母和选项内容。
2. 判断题给出”正确”或”错误”。
3. 不确定时说明不确定，不要伪造来源。
4. 回答格式：先给最终答案，再简要说明。”””


def _format_options(options: Dict[str, str]) -> str:
    return "\n".join(f"{k}. {v}" for k, v in options.items()) if options else "无选项"


def _call_chat_completion(config: dict, messages: List[dict], max_tokens: int = 512) -> str:
    endpoint = (config.get("endpoint") or "").strip()
    key = (config.get("key") or "").strip()
    model = (config.get("model") or "").strip()

    if not endpoint or not key or not model:
        logger.warning("AI config incomplete: endpoint=%s model=%s key=%s", endpoint[:60], model, "***" if key else "EMPTY")
        return ""

    payload = {
        "model": model,
        "messages": messages,
        "temperature": 0,
        "max_tokens": max_tokens,
    }
    logger.info("Calling AI endpoint=%s model=%s", endpoint[:80], model)
    resp = httpx.post(
        url=endpoint,
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
        json=payload,
        timeout=RAG_AI_TIMEOUT,
    )
    logger.info("AI HTTP status: %d", resp.status_code)
    resp.raise_for_status()
    data = resp.json()
    return data.get("choices", [{}])[0].get("message", {}).get("content", "").strip()


def generate_fallback_answer(question_text: str, options: dict[str, str]) -> dict:
    config = _get_fallback_ai_config()
    user_message = f"题目：{question_text}\n选项：\n{_format_options(options)}\n\n请给出答案。"
    try:
        content = _call_chat_completion(
            config,
            [
                {"role": "system", "content": FALLBACK_SYSTEM_PROMPT},
                {"role": "user", "content": user_message},
            ],
        )
        if not content:
            return {
                "answer": "",
                "source": "兜底模式",
                "confidence": 0.0,
                "matchDetail": "兜底AI未配置或未返回内容",
                "chunks": [],
            }
        answer_line = content.split("\n")[0].strip()
        return {
            "answer": answer_line,
            "source": "兜底模型",
            "confidence": 0.25,
            "matchDetail": content,
            "chunks": [],
        }
    except Exception as exc:
        logger.error("Fallback AI call failed: %s", exc, exc_info=True)
        return {
            "answer": "",
            "source": "兜底模型",
            "confidence": 0.0,
            "matchDetail": f"兜底AI调用失败: {str(exc)[:120]}",
            "chunks": [],
        }


def generate_answer(question_text: str, options: dict[str, str], subject: str, top_k: int = VECTOR_TOP_K) -> dict:
    logger.info("=== RAG generate_answer START ===")
    logger.info("questionText: %s", question_text[:200])
    logger.info("options: %s", options)
    logger.info("subject: '%s'", subject)

    query = question_text
    if options:
        query = f"{question_text} " + " ".join(f"{k}.{v}" for k, v in options.items())
    logger.info("search query: %s", query[:300])

    try:
        chunks = search_relevant_chunks(subject=subject, query=query, top_k=top_k)
    except Exception as exc:
        logger.error("Vector search failed, using fallback AI: %s", exc, exc_info=True)
        fallback = generate_fallback_answer(question_text, options)
        fallback["matchDetail"] = f"知识库检索失败，已尝试兜底模型。{fallback.get('matchDetail', '')}"
        return fallback

    logger.info("vector search returned %d chunks", len(chunks))
    if not chunks:
        logger.warning("No chunks found, using fallback AI")
        fallback = generate_fallback_answer(question_text, options)
        fallback["matchDetail"] = f"题库和知识库均未命中，已尝试兜底模型。{fallback.get('matchDetail', '')}"
        return fallback

    for i, chunk in enumerate(chunks):
        logger.info(
            "Chunk[%d] score=%.4f source=%s content[:200]=%s",
            i,
            chunk["score"],
            chunk.get("source_file", ""),
            chunk["content"][:200],
        )

    chunks_text = "\n\n".join(
        f"[片段{i + 1}] 来源: {chunk.get('source_file', '')}\n{chunk['content']}"
        for i, chunk in enumerate(chunks)
    )
    user_message = f"教材片段：\n{chunks_text}\n\n题目：{question_text}\n选项：\n{_format_options(options)}\n\n请基于教材片段给出正确答案。"

    try:
        content = _call_chat_completion(
            _get_index_ai_config(),
            [
                {"role": "system", "content": RAG_SYSTEM_PROMPT},
                {"role": "user", "content": user_message},
            ],
        )
        logger.info("RAG AI raw response[:300]: %s", content[:300])
        if not content or "未找到" in content.split("\n")[0]:
            logger.info("RAG did not produce final answer, using fallback AI")
            fallback = generate_fallback_answer(question_text, options)
            fallback["matchDetail"] = f"知识库片段不足以确定答案，已尝试兜底模型。{fallback.get('matchDetail', '')}"
            fallback["chunks"] = [{"content": c["content"][:300], "source_file": c.get("source_file", ""), "score": c["score"]} for c in chunks]
            return fallback

        answer_line = content.split("\n")[0].strip()
        top_score = max((c["score"] for c in chunks), default=0)
        return {
            "answer": answer_line,
            "source": "模糊匹配",
            "confidence": round(top_score, 3),
            "matchDetail": content,
            "chunks": [{"content": c["content"][:300], "source_file": c.get("source_file", ""), "score": c["score"]} for c in chunks],
        }
    except Exception as exc:
        logger.error("RAG AI call failed, using fallback AI: %s", exc, exc_info=True)
        fallback = generate_fallback_answer(question_text, options)
        fallback["matchDetail"] = f"知识库AI调用失败，已尝试兜底模型。{fallback.get('matchDetail', '')}"
        fallback["chunks"] = [{"content": c["content"][:300], "source_file": c.get("source_file", ""), "score": c["score"]} for c in chunks]
        return fallback
