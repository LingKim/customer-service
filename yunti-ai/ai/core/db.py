"""PostgreSQL 连接，按请求创建并及时关闭。"""

from __future__ import annotations

from ..config import get_settings


def connect():
    import psycopg
    from psycopg.rows import dict_row

    return psycopg.connect(get_settings().database_url, row_factory=dict_row)
