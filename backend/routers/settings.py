import json
import os
from typing import Dict
from fastapi import APIRouter
from pydantic import BaseModel
from config import BASE_DIR

router = APIRouter()
SETTINGS_FILE = BASE_DIR / "data" / "settings.json"

AI_SETTING_KEYS = {
    "vision": {
        "title": "AI图文识别",
        "endpoint": "vision_endpoint",
        "key": "vision_key",
        "model": "vision_model",
        "default_model": "qwen-vl-plus",
    },
    "index": {
        "title": "题库索引解析",
        "endpoint": "index_endpoint",
        "key": "index_key",
        "model": "index_model",
        "default_model": "deepseek-chat",
    },
    "fallback": {
        "title": "兜底解析",
        "endpoint": "fallback_endpoint",
        "key": "fallback_key",
        "model": "fallback_model",
        "default_model": "deepseek-chat",
    },
}


class AiProviderSettings(BaseModel):
    endpoint: str = ""
    key: str = ""
    model: str = ""


class AiSettingsPayload(BaseModel):
    vision: AiProviderSettings = AiProviderSettings()
    index: AiProviderSettings = AiProviderSettings()
    fallback: AiProviderSettings = AiProviderSettings()


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
    SETTINGS_FILE.parent.mkdir(parents=True, exist_ok=True)
    with open(SETTINGS_FILE, "w", encoding="utf-8") as f:
        json.dump(settings, f, ensure_ascii=False, indent=2)


def normalize_ai_settings(settings: dict) -> dict:
    # 兼容旧版 RAG 配置：旧的 rag_* 迁移为“题库索引解析”配置。
    if settings.get("rag_endpoint") and not settings.get("index_endpoint"):
        settings["index_endpoint"] = settings.get("rag_endpoint", "")
    if settings.get("rag_key") and not settings.get("index_key"):
        settings["index_key"] = settings.get("rag_key", "")
    if settings.get("rag_model") and not settings.get("index_model"):
        settings["index_model"] = settings.get("rag_model", "")

    for cfg in AI_SETTING_KEYS.values():
        settings.setdefault(cfg["endpoint"], "")
        settings.setdefault(cfg["key"], "")
        settings.setdefault(cfg["model"], cfg["default_model"])
    return settings


def apply_runtime_env(settings: dict):
    settings = normalize_ai_settings(settings)
    os.environ["RAG_AI_ENDPOINT"] = settings.get("index_endpoint", "")
    os.environ["RAG_AI_KEY"] = settings.get("index_key", "")
    os.environ["RAG_AI_MODEL"] = settings.get("index_model", "")
    os.environ["FALLBACK_AI_ENDPOINT"] = settings.get("fallback_endpoint", "")
    os.environ["FALLBACK_AI_KEY"] = settings.get("fallback_key", "")
    os.environ["FALLBACK_AI_MODEL"] = settings.get("fallback_model", "")
    os.environ["VISION_AI_ENDPOINT"] = settings.get("vision_endpoint", "")
    os.environ["VISION_AI_KEY"] = settings.get("vision_key", "")
    os.environ["VISION_AI_MODEL"] = settings.get("vision_model", "")


def _settings_to_response(settings: dict) -> Dict[str, dict]:
    settings = normalize_ai_settings(settings)
    return {
        name: {
            "title": cfg["title"],
            "endpoint": settings.get(cfg["endpoint"], ""),
            "key": settings.get(cfg["key"], ""),
            "model": settings.get(cfg["model"], cfg["default_model"]),
        }
        for name, cfg in AI_SETTING_KEYS.items()
    }


@router.get("/ai")
def get_ai_settings():
    settings = normalize_ai_settings(load_settings())
    save_settings(settings)
    apply_runtime_env(settings)
    return {"success": True, "settings": _settings_to_response(settings)}


@router.post("/ai")
@router.put("/ai")
def update_ai_settings(body: AiSettingsPayload):
    settings = normalize_ai_settings(load_settings())
    for name, cfg in AI_SETTING_KEYS.items():
        item = getattr(body, name)
        settings[cfg["endpoint"]] = item.endpoint.strip()
        settings[cfg["key"]] = item.key.strip()
        settings[cfg["model"]] = (item.model or cfg["default_model"]).strip()

    # 保留旧接口读取习惯，避免现有链路回退。
    settings["rag_endpoint"] = settings.get("index_endpoint", "")
    settings["rag_key"] = settings.get("index_key", "")
    settings["rag_model"] = settings.get("index_model", "")

    save_settings(settings)
    apply_runtime_env(settings)
    return {"success": True, "message": "AI设置已保存", "settings": _settings_to_response(settings)}


@router.put("/rag")
def update_rag_settings(body: RagSettings):
    settings = normalize_ai_settings(load_settings())
    settings["index_endpoint"] = body.endpoint.strip()
    settings["index_key"] = body.key.strip()
    settings["index_model"] = body.model.strip()
    settings["rag_endpoint"] = settings["index_endpoint"]
    settings["rag_key"] = settings["index_key"]
    settings["rag_model"] = settings["index_model"]
    save_settings(settings)
    apply_runtime_env(settings)
    return {"success": True, "message": "RAG配置已保存"}


@router.get("/rag")
def get_rag_settings():
    settings = normalize_ai_settings(load_settings())
    return {
        "success": True,
        "endpoint": settings.get("index_endpoint", ""),
        "key": settings.get("index_key", ""),
        "model": settings.get("index_model", ""),
    }


