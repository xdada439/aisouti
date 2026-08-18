import logging
import sys

# 配置全局日志
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
    datefmt="%H:%M:%S",
    stream=sys.stdout,
)
# 关闭chromadb自身的debug日志噪音
logging.getLogger("chromadb").setLevel(logging.WARNING)
logging.getLogger("sentence_transformers").setLevel(logging.WARNING)

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from contextlib import asynccontextmanager

from config import BASE_DIR
from models import init_db


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    yield


app = FastAPI(title="AI搜题助手后端", version="1.0.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

from routers import question_bank, knowledge_base, search, settings

app.include_router(question_bank.router, prefix="/api/question-bank", tags=["题库管理"])
app.include_router(knowledge_base.router, prefix="/api/knowledge-base", tags=["知识库管理"])
app.include_router(search.router, prefix="/api", tags=["搜题"])
app.include_router(settings.router, prefix="/api/settings", tags=["系统设置"])

app.mount("/", StaticFiles(directory=str(BASE_DIR / "static"), html=True), name="static")


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)
