"""yunti-ai 启动入口（参考企业知识库后端 main.py 的 IDEA 运行方案）。

支持两种等价启动方式：
1. python main.py      —— IDEA 的 Python 运行配置默认指向本文件；
2. python -m ai        —— 包模块方式启动。

均可通过环境变量 YUNTI_AI_HOST / YUNTI_AI_PORT 覆盖监听地址与端口。
"""

from __future__ import annotations

import os

try:
    import uvicorn
except ModuleNotFoundError as exc:
    if exc.name == "uvicorn":
        raise SystemExit(
            "未找到 uvicorn：当前运行解释器不是项目 .venv。\n"
            "请选择运行配置「yunti-ai (Shell)」，"
            "或在 Settings → Project → Python Interpreter 中把解释器指向 "
            "yunti-ai/.venv/bin/python 后重试。"
        ) from None
    raise

from ai.config import get_settings


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
