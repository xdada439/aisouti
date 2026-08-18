import os
import json
import logging
import httpx
from config import RAG_AI_ENDPOINT, RAG_AI_KEY, RAG_AI_MODEL, RAG_AI_TIMEOUT, VECTOR_TOP_K, BASE_DIR
from services.vector_search import search_relevant_chunks

logger = logging.getLogger("rag_answer")


def _load_settings_override():
    """从data/settings.json加载RAG配置，覆盖默认环境变量"""
    settings_file = BASE_DIR / "data" / "settings.json"
    if settings_file.exists():
        try:
            with open(settings_file, "r", encoding="utf-8") as f:
                s = json.load(f)
            if s.get("rag_endpoint"):
                os.environ["RAG_AI_ENDPOINT"] = s["rag_endpoint"]
            if s.get("rag_key"):
                os.environ["RAG_AI_KEY"] = s["rag_key"]
            if s.get("rag_model"):
                os.environ["RAG_AI_MODEL"] = s["rag_model"]
            logger.info("RAG settings loaded from %s: endpoint=%s model=%s key=%s",
                         settings_file,
                         s.get("rag_endpoint", "")[:50],
                         s.get("rag_model", ""),
                         "***" if s.get("rag_key") else "EMPTY")
        except Exception as e:
            logger.warning("Failed to load RAG settings: %s", e)


# 模块加载时立即执行
_load_settings_override()


RAG_SYSTEM_PROMPT = """你是一个教材知识库助手。你必须仅基于提供的教材片段回答问题。
规则：
1. 如果教材片段中包含题目对应的答案，直接给出答案（选项字母或文字）。
2. 如果教材片段中找不到答案，回复"未找到"。
3. 严禁使用外部知识、联网搜索或常识推理。
4. 严禁自由联想或编造答案。
5. 回答格式：先给出最终答案，再简要说明依据哪个片段。"""


def generate_answer(
    question_text: str,
    options: dict[str, str],
    subject: str,
    top_k: int = VECTOR_TOP_K
) -> dict:
    logger.info("=== RAG generate_answer START ===")
    logger.info("questionText: %s", question_text[:200])
    logger.info("options: %s", options)
    logger.info("subject: '%s'", subject)

    # 1. 构建查询
    query = question_text
    if options:
        opts_text = " ".join(f"{k}.{v}" for k, v in options.items())
        query = f"{question_text} {opts_text}"
    logger.info("search query: %s", query[:300])

    # 2. 向量检索
    chunks = search_relevant_chunks(subject=subject, query=query, top_k=top_k)
    logger.info("vector search returned %d chunks", len(chunks))

    if not chunks:
        logger.warning("No chunks found in knowledge base for subject='%s'", subject)
        return {
            "answer": "",
            "source": "教材知识库",
            "confidence": 0.0,
            "matchDetail": f"知识库中未找到相关片段（subject={subject}，已尝试全库检索）",
            "chunks": []
        }

    for i, c in enumerate(chunks):
        logger.info("Chunk[%d] score=%.4f source=%s content[:200]=%s",
                     i, c["score"], c.get("source_file", ""), c["content"][:200])

    # 3. 拼接prompt
    chunks_text = "\n\n".join(
        f"[片段{i + 1}] (来源: {c['source_file']})\n{c['content']}"
        for i, c in enumerate(chunks)
    )

    options_text = "\n".join(f"{k}. {v}" for k, v in options.items()) if options else "无选项"

    user_message = f"""教材片段：
{chunks_text}

题目：{question_text}
选项：
{options_text}

请基于上述教材片段给出正确答案。"""

    logger.info("RAG prompt length: %d chars", len(user_message))

    # 4. 检查AI配置
    rag_key = os.environ.get("RAG_AI_KEY", RAG_AI_KEY)
    rag_endpoint = os.environ.get("RAG_AI_ENDPOINT", RAG_AI_ENDPOINT)
    rag_model = os.environ.get("RAG_AI_MODEL", RAG_AI_MODEL)

    if not rag_key:
        logger.warning("RAG AI Key not configured, returning chunks only")
        return {
            "answer": "",
            "source": "教材知识库",
            "confidence": 0.0,
            "matchDetail": "RAG AI未配置（请在系统设置中配置API Key），已检索到相关片段",
            "chunks": [{"content": c["content"][:300], "source_file": c.get("source_file", ""), "score": c["score"]} for c in chunks]
        }

    # 5. 调用AI
    try:
        payload = {
            "model": rag_model,
            "messages": [
                {"role": "system", "content": RAG_SYSTEM_PROMPT},
                {"role": "user", "content": user_message}
            ],
            "temperature": 0,
            "max_tokens": 512
        }

        logger.info("Calling RAG AI: endpoint=%s model=%s", rag_endpoint[:60], rag_model)
        resp = httpx.post(
            url=rag_endpoint,
            headers={
                "Authorization": f"Bearer {rag_key}",
                "Content-Type": "application/json"
            },
            json=payload,
            timeout=RAG_AI_TIMEOUT
        )
        logger.info("RAG AI HTTP status: %d", resp.status_code)
        resp.raise_for_status()
        data = resp.json()

        content = data.get("choices", [{}])[0].get("message", {}).get("content", "")
        logger.info("RAG AI raw response[:300]: %s", content[:300])

        if not content:
            logger.warning("RAG AI returned empty content")
            return {
                "answer": "",
                "source": "教材知识库",
                "confidence": 0.0,
                "matchDetail": "AI返回为空",
                "chunks": [{"content": c["content"][:300], "source_file": c.get("source_file", ""), "score": c["score"]} for c in chunks]
            }

        # 6. 提取答案
        answer_line = content.strip().split("\n")[0]
        if "未找到" in answer_line:
            logger.info("AI says '未找到' in answer")
            return {
                "answer": "",
                "source": "教材知识库",
                "confidence": 0.0,
                "matchDetail": "AI判断教材片段中无匹配答案",
                "chunks": [{"content": c["content"][:300], "source_file": c.get("source_file", ""), "score": c["score"]} for c in chunks]
            }

        top_score = max((c["score"] for c in chunks), default=0)
        logger.info("RAG answer SUCCESS: answer=%s score=%.4f", answer_line, top_score)
        return {
            "answer": answer_line,
            "source": "教材知识库",
            "confidence": round(top_score, 3),
            "matchDetail": f"基于 {len(chunks)} 个教材片段生成",
            "chunks": [{"content": c["content"][:300], "source_file": c.get("source_file", ""), "score": c["score"]} for c in chunks]
        }

    except Exception as e:
        logger.error("RAG AI call FAILED: %s", e, exc_info=True)
        return {
            "answer": "",
            "source": "教材知识库",
            "confidence": 0.0,
            "matchDetail": f"RAG AI调用失败: {str(e)[:100]}",
            "chunks": [{"content": c["content"][:300], "source_file": c.get("source_file", ""), "score": c["score"]} for c in chunks]
        }
