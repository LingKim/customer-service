"""应用配置：环境变量前缀 YUNTI_AI_，支持 .env。"""

from __future__ import annotations

from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="YUNTI_AI_", env_file=".env", extra="ignore")

    app_name: str = "yunti-ai"
    service_name: str = "ai-center"
    debug: bool = False
    api_prefix: str = "/api"

    # LLM 网关（默认 mock，装好 AI 依赖后可切 qwen/deepseek）
    llm_default_provider: str = "mock"
    llm_default_model: str = ""
    llm_timeout: int = 30
    llm_log_payload: bool = False
    qwen_api_key: str = ""
    qwen_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    qwen_model: str = "qwen-plus"
    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com/v1"
    deepseek_model: str = "deepseek-chat"

    # 向量化（知识库用）：默认走千问的 OpenAI 兼容 /embeddings 接口。
    # 不配 embedding_api_key 时自动降级为"本地哈希向量"——只能把链路跑通，语义效果差。
    embedding_provider: str = "qwen"
    embedding_api_key: str = ""
    embedding_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    embedding_model: str = "text-embedding-v3"
    embedding_timeout: int = 30

    def embedding_key(self) -> str:
        """向量化用哪个密钥。

        优先专用的 ``embedding_api_key``；没配就**复用千问的 key**——
        千问的对话和向量化是同一个账号同一套 OpenAI 兼容接口，
        让用户把同一个密钥配两遍（少配一处就悄悄退化成兜底向量）是个坑。
        """
        return self.embedding_api_key or self.qwen_api_key

    # 知识库：切片参数与数据源（切片存在 customer_db，和文档表同库，租户隔离靠 tenant_code）
    kb_database_url: str = ""
    # 切块大小：内置实现按"字符"算，LlamaIndex 按"token"算（同数值粒度不同，可按需分别调）
    kb_chunk_size: int = 600
    kb_chunk_overlap: int = 80
    kb_max_file_mb: int = 20

    redis_url: str = "redis://localhost:6379/0"
    kafka_bootstrap: str = "localhost:9092"
    database_url: str = "postgresql://mac@127.0.0.1:5432/ai_db"
    jwt_secret: str = Field(
        default="yunti-customer-service-jwt-secret-please-change-in-prod-0123456789",
        validation_alias="YUNTI_JWT_SECRET",
    )
    internal_shared_secret: str = Field(default="", validation_alias="YUNTI_INTERNAL_SHARED_SECRET")

@lru_cache
def get_settings() -> Settings:
    return Settings()
