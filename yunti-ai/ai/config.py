"""应用配置：环境变量前缀 YUNTI_AI_，支持 .env。"""

from __future__ import annotations

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="YUNTI_AI_", env_file=".env", extra="ignore")

    app_name: str = "yunti-ai"
    service_name: str = "ai-center"
    debug: bool = False
    api_prefix: str = "/api"

    # LLM 网关（默认 mock，装好 AI 依赖后可切 qwen/deepseek）
    llm_default_provider: str = "mock"
    llm_default_model: str = "mock-model"

    redis_url: str = "redis://localhost:6379/0"
    kafka_bootstrap: str = "localhost:9092"

@lru_cache
def get_settings() -> Settings:
    return Settings()
