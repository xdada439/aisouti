import threading
from sentence_transformers import SentenceTransformer
from config import EMBEDDING_MODEL_NAME


_model = None
_lock = threading.Lock()


def get_model() -> SentenceTransformer:
    global _model
    if _model is None:
        with _lock:
            if _model is None:
                _model = SentenceTransformer(EMBEDDING_MODEL_NAME)
    return _model


def embed_texts(texts: list[str]) -> list[list[float]]:
    if not texts:
        return []
    model = get_model()
    embeddings = model.encode(texts, normalize_embeddings=True)
    return embeddings.tolist()


def embed_query(text: str) -> list[float]:
    model = get_model()
    embedding = model.encode([text], normalize_embeddings=True)
    return embedding[0].tolist()
