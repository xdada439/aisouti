import uuid
import logging
import chromadb
from chromadb.config import Settings
from config import CHROMA_DIR, VECTOR_TOP_K
from services.embedding_service import embed_texts, embed_query

logger = logging.getLogger("vector_search")

_client = None


def _get_client() -> chromadb.PersistentClient:
    global _client
    if _client is None:
        logger.info("Initializing ChromaDB at: %s", CHROMA_DIR)
        _client = chromadb.PersistentClient(
            path=str(CHROMA_DIR),
            settings=Settings(anonymized_telemetry=False)
        )
        _log_all_collections()
    return _client


def _collection_name(subject: str) -> str:
    """按科目隔离collection"""
    safe = subject.strip().replace(" ", "_").replace("/", "_") or "default"
    return f"kb_{safe}"


def _log_all_collections():
    """打印所有collections状态"""
    try:
        client = _get_client()
        cols = client.list_collections()
        logger.info("ChromaDB collections (%d total):", len(cols))
        for col in cols:
            logger.info("  - %s: %d docs, metadata=%s",
                         col.name, col.count(), col.metadata)
    except Exception as e:
        logger.warning("Failed to list collections: %s", e)


def get_all_collections_info() -> list[dict]:
    """返回所有collections信息，供debug API使用"""
    try:
        client = _get_client()
        cols = client.list_collections()
        result = []
        for col in cols:
            result.append({
                "name": col.name,
                "count": col.count(),
                "metadata": col.metadata,
            })
        return result
    except Exception as e:
        return [{"error": str(e)}]


def index_chunks(
    subject: str,
    chunks: list[str],
    source_file: str
) -> list[str]:
    """为文本块建立向量索引"""
    if not chunks:
        logger.warning("index_chunks: empty chunks, skipping")
        return []

    client = _get_client()
    col_name = _collection_name(subject)
    logger.info("=== index_chunks START ===")
    logger.info("subject: '%s' -> collection: '%s'", subject, col_name)
    logger.info("chunks count: %d, source_file: %s", len(chunks), source_file)
    for i, c in enumerate(chunks[:3]):
        logger.info("  chunk[%d] preview: %s", i, c[:100])

    # 不删除旧collection，改为追加。删除会导致之前上传的数据丢失。
    # 如需覆盖，应先调用 remove_chunks_by_subject

    # 生成embedding
    logger.info("Generating embeddings for %d chunks...", len(chunks))
    embeddings = embed_texts(chunks)
    logger.info("Embeddings generated: %d vectors, dim=%d",
                 len(embeddings), len(embeddings[0]) if embeddings else 0)

    ids = [f"{subject}_{uuid.uuid4().hex[:12]}" for _ in chunks]
    metadatas = [
        {"source_file": source_file, "chunk_index": i, "subject": subject}
        for i in range(len(chunks))
    ]

    collection = client.get_or_create_collection(
        name=col_name,
        metadata={"subject": subject, "hnsw:space": "cosine"}
    )

    collection.add(
        ids=ids,
        embeddings=embeddings,
        documents=chunks,
        metadatas=metadatas
    )

    logger.info("ChromaDB add() done: collection=%s, added=%d docs, total now=%d",
                 col_name, len(ids), collection.count())
    for i, eid in enumerate(ids[:5]):
        logger.info("  embedding_id[%d]: %s", i, eid)

    _log_all_collections()
    return ids


def remove_chunks_by_subject(subject: str) -> int:
    client = _get_client()
    col_name = _collection_name(subject)
    try:
        collection = client.get_collection(col_name)
        count = collection.count()
        client.delete_collection(col_name)
        logger.info("Deleted collection '%s' with %d docs", col_name, count)
        return count
    except Exception:
        logger.info("Collection '%s' not found for deletion", col_name)
        return 0


def search_relevant_chunks(
    subject: str,
    query: str,
    top_k: int = VECTOR_TOP_K
) -> list[dict]:
    logger.info("=== search_relevant_chunks START ===")
    logger.info("subject: '%s', top_k: %d, query: %s", subject, top_k, query[:200])

    client = _get_client()
    col_name = _collection_name(subject)

    # 尝试精确subject匹配
    try:
        collection = client.get_collection(col_name)
        logger.info("Found collection '%s' with %d docs", col_name, collection.count())

        if collection.count() == 0:
            logger.warning("Collection '%s' exists but has 0 documents", col_name)
        else:
            return _do_search(collection, query, top_k, col_name)
    except Exception:
        logger.info("Collection '%s' not found, trying fallback...", col_name)

    # Fallback 1: 如果 subject 为空，尝试所有collection
    all_cols = client.list_collections()
    logger.info("Fallback: checking all %d collections", len(all_cols))

    if all_cols:
        # 尝试每个collection
        for col in all_cols:
            if col.count() > 0:
                logger.info("Fallback: using collection '%s' (count=%d)", col.name, col.count())
                return _do_search(col, query, top_k, col.name)

    logger.warning("No collections with documents found")
    return []


def _do_search(collection, query: str, top_k: int, col_name: str) -> list[dict]:
    """执行实际检索"""
    logger.info("Generating query embedding...")
    query_embedding = embed_query(query)
    logger.info("Query embedding generated, dim=%d", len(query_embedding))

    k = min(top_k, collection.count())
    logger.info("Searching collection '%s' top_k=%d", col_name, k)

    results = collection.query(
        query_embeddings=[query_embedding],
        n_results=k
    )

    if not results or not results.get("documents") or not results["documents"][0]:
        logger.warning("Query returned no documents")
        return []

    chunks = []
    for i, doc in enumerate(results["documents"][0]):
        meta = results["metadatas"][0][i] if results.get("metadatas") else {}
        distance = results["distances"][0][i] if results.get("distances") else 0
        score = round(1 - distance, 4) if distance else 0
        logger.info("Result[%d]: score=%.4f source=%s preview=%s",
                     i, score, meta.get("source_file", ""), doc[:100])
        chunks.append({
            "content": doc,
            "source_file": meta.get("source_file", ""),
            "chunk_index": meta.get("chunk_index", 0),
            "score": score
        })

    logger.info("search_relevant_chunks returning %d chunks", len(chunks))
    return chunks
