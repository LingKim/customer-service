"""FastAPI 入口。"""

from __future__ import annotations

from fastapi import FastAPI

from .api import bot, brain, chat, health, kb, qa, rag
from .config import get_settings
from .core.schema_guard import ensure_schema

def create_app() -> FastAPI:
    settings = get_settings()
    ensure_schema()
    app = FastAPI(
        title=settings.app_name,
        version="0.1.0",
        debug=settings.debug,
    )
    app.include_router(health.router, prefix=settings.api_prefix)
    app.include_router(chat.router, prefix=settings.api_prefix)
    app.include_router(bot.router, prefix=settings.api_prefix)
    app.include_router(qa.router, prefix=settings.api_prefix)
    app.include_router(kb.router, prefix=settings.api_prefix)
    app.include_router(rag.router, prefix=settings.api_prefix)
    app.include_router(brain.router, prefix=settings.api_prefix)
    return app

app = create_app()
