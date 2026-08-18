import logging
import sys
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

from config import BASE_DIR
from models import init_db

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
    datefmt="%H:%M:%S",
    stream=sys.stdout,
)
logging.getLogger("chromadb").setLevel(logging.WARNING)
logging.getLogger("sentence_transformers").setLevel(logging.WARNING)


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    yield


app = FastAPI(title="AI搜题助手后台", version="1.0.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

from routers import knowledge_base, question_bank, search, settings, users  # noqa: E402

app.include_router(question_bank.router, prefix="/api/question-bank", tags=["题库管理"])
app.include_router(knowledge_base.router, prefix="/api/knowledge-base", tags=["模糊匹配资料管理"])
app.include_router(search.router, prefix="/api", tags=["搜题"])
app.include_router(settings.router, prefix="/api/settings", tags=["系统设置"])
app.include_router(users.router, prefix="/api/users", tags=["AI搜题用户中心"])

app.mount("/", StaticFiles(directory=str(BASE_DIR / "static"), html=True), name="static")


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)
