import json
from fastapi import APIRouter
from pydantic import BaseModel
from config import BASE_DIR

router = APIRouter()
SETTINGS_FILE = BASE_DIR / "data" / "settings.json"


class RagSettings(BaseModel):
    endpoint: str = ""
    key: str = ""
    model: str = ""


def load_settings() -> dict:
    if SETTINGS_FILE.exists():
        with open(SETTINGS_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    return {}


def save_settings(settings: dict):
    with open(SETTINGS_FILE, "w", encoding="utf-8") as f:
        json.dump(settings, f, ensure_ascii=False, indent=2)


@router.put("/rag")
def update_rag_settings(body: RagSettings):
    import os
    settings = load_settings()
    settings["rag_endpoint"] = body.endpoint
    settings["rag_key"] = body.key
    settings["rag_model"] = body.model
    save_settings(settings)
    # 更新环境变量使新配置生效
    if body.endpoint:
        os.environ["RAG_AI_ENDPOINT"] = body.endpoint
    if body.key:
        os.environ["RAG_AI_KEY"] = body.key
    if body.model:
        os.environ["RAG_AI_MODEL"] = body.model
    return {"success": True, "message": "RAG配置已保存"}


@router.get("/rag")
def get_rag_settings():
    settings = load_settings()
    return {
        "success": True,
        "endpoint": settings.get("rag_endpoint", ""),
        "key": settings.get("rag_key", ""),
        "model": settings.get("rag_model", ""),
    }
