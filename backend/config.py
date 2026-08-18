import os
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
DATA_DIR = BASE_DIR / "data"
UPLOAD_DIR = DATA_DIR / "uploads"
CHROMA_DIR = DATA_DIR / "chroma"
DB_PATH = DATA_DIR / "question_bank.db"

DATA_DIR.mkdir(parents=True, exist_ok=True)
UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
CHROMA_DIR.mkdir(parents=True, exist_ok=True)

DATABASE_URL = f"sqlite:///{DB_PATH}"

EMBEDDING_MODEL_NAME = os.getenv(
    "EMBEDDING_MODEL",
    "paraphrase-multilingual-MiniLM-L12-v2"
)

CHUNK_SIZE = int(os.getenv("CHUNK_SIZE", "500"))
CHUNK_OVERLAP = int(os.getenv("CHUNK_OVERLAP", "50"))
VECTOR_TOP_K = int(os.getenv("VECTOR_TOP_K", "5"))

RAG_AI_ENDPOINT = os.getenv("RAG_AI_ENDPOINT", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
RAG_AI_KEY = os.getenv("RAG_AI_KEY", "")
RAG_AI_MODEL = os.getenv("RAG_AI_MODEL", "qwen-plus")
RAG_AI_TIMEOUT = int(os.getenv("RAG_AI_TIMEOUT", "30"))

UPLOAD_ALLOWED_EXTENSIONS = {".pdf", ".docx", ".pptx", ".txt", ".md", ".xlsx"}
MAX_UPLOAD_SIZE_MB = 50
