"""入口：支持 python -m ai 或在 IntelliJ IDEA 中直接运行。"""

from __future__ import annotations

import os

import uvicorn

from .config import get_settings

def main() -> None:
    settings = get_settings()
    host = os.getenv("YUNTI_AI_HOST", "127.0.0.1")
    port = int(os.getenv("YUNTI_AI_PORT", "9100"))
    uvicorn.run(
        "ai.main:app",
        host=host,
        port=port,
        reload=settings.debug,
    )

if __name__ == "__main__":
    main()
