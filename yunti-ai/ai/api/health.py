"""健康检查。"""

from __future__ import annotations

from fastapi import APIRouter

from .. import __version__
from ..config import get_settings

router = APIRouter()

@router.get("/ai/health")
def health() -> dict[str, object]:
    settings = get_settings()
    return {
        "status": "UP",
        "service": settings.service_name,
        "version": __version__,
        "app": settings.app_name,
    }
