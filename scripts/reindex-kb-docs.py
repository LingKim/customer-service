"""把知识库文档重新索引一遍（重点是：把兜底向量换成真实向量）。

什么时候要用：
    向量化失败过（密钥没配 / 401 / 网络不通）时，索引会静默降级成没有语义的
    local-hash 兜底向量。之后密钥配好了、服务也重启了，**老向量还是兜底向量**
    ——向量是索引那一刻算出来的，光重启不会重算。表现就是"知识库明明有数据，
    机器人却答不到点子上"。

跑法：
    python scripts/reindex-kb-docs.py                 # 只重建"用了兜底向量"的文档（推荐）
    python scripts/reindex-kb-docs.py --all           # 整库重建
    python scripts/reindex-kb-docs.py --http http://127.0.0.1:9093
    python scripts/reindex-kb-docs.py --doc 226305535593418752

前提：yunti-ai(9100) 与 customer-service(9093) 都在跑，且**向量密钥已配好**
     （YUNTI_AI_QWEN_API_KEY 或 YUNTI_AI_EMBEDDING_API_KEY）。

顺带说明：customer-service 启动时也会自检这件事（KbVectorHealer），
同一件活儿不用重复做——这个脚本适合"不想重启服务"的时候用。
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import time

import httpx

SECRET = b"yunti-customer-service-jwt-secret-please-change-in-prod-0123456789"
TENANT = "T202609050000002"
AGENT_ID = "222685643665313792"   # 王梓涵
AGENT_NAME = "王梓涵"


def b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def agent_token() -> str:
    """自己签一个坐席令牌：这些验证脚本不依赖浏览器登录态"""
    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {"sub": AGENT_ID, "uno": "U202609071953146315", "name": AGENT_NAME,
               "tnt": TENANT, "type": 2, "iat": now, "exp": now + 1800}
    signing = (f"{b64(json.dumps(header, separators=(',', ':')).encode())}."
               f"{b64(json.dumps(payload, separators=(',', ':')).encode())}")
    return f"{signing}.{b64(hmac.new(SECRET, signing.encode(), hashlib.sha256).digest())}"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--http", default=os.environ.get("KB_HTTP", "http://127.0.0.1:9093"))
    parser.add_argument("--ai", default=os.environ.get("KB_AI", "http://127.0.0.1:9100"))
    parser.add_argument("--all", action="store_true", help="整库重建（默认只重建用了兜底向量的文档）")
    parser.add_argument("--doc", default=None, help="只重建这一份文档 ID")
    args = parser.parse_args()
    base, ai = args.http.rstrip("/"), args.ai.rstrip("/")
    headers = {"Authorization": f"Bearer {agent_token()}", "X-Tenant-Code": TENANT}

    with httpx.Client(timeout=300) as http:
        health = http.get(f"{ai}/api/ai/v1/kb/health").json()
        print("① 知识库体检：")
        print(f"   向量密钥已配：{health.get('embeddingKeyConfigured')}  向量模型：{health.get('embeddingModel')}")
        print(f"   切片向量来源：{health.get('chunkVectorModels')}")
        print(f"   {health.get('vectorQualityHint')}")
        if health.get("embeddingKeyConfigured") is not True:
            print("\n❌ 向量密钥还没配好，现在重建还是兜底向量。")
            print("   先配 YUNTI_AI_QWEN_API_KEY（或 YUNTI_AI_EMBEDDING_API_KEY）并重启 yunti-ai。")
            return

        docs = (http.get(f"{base}/api/customer/kb/documents", headers=headers).json())["data"]
        fallback_ids = {str(item) for item in (health.get("fallbackDocIds") or [])}
        if args.doc:
            targets = [doc for doc in docs if str(doc["id"]) == str(args.doc)]
        elif args.all:
            targets = docs
        elif fallback_ids:
            targets = [doc for doc in docs if str(doc["id"]) in fallback_ids]
        else:
            targets = docs

        if not targets:
            print("\n✅ 没有需要重建的文档（切片用的都是真实向量）")
            return
        print(f"\n② 待重建 {len(targets)} 份文档：{[doc['title'] for doc in targets]}")

        ok = failed = 0
        for index, doc in enumerate(targets, start=1):
            try:
                result = (http.post(f"{base}/api/customer/kb/documents/{doc['id']}/reindex",
                                    headers=headers)).json()
                if result.get("code") != 0:
                    failed += 1
                    print(f"   {index}/{len(targets)} ❌ {doc['title']}：{result.get('message')}")
                    continue
                data = result["data"]
                ok += 1
                print(f"   {index}/{len(targets)} ✅ {doc['title']}：{data.get('chunkCount')} 个切片"
                      f"（{data.get('indexMessage') or ''}）")
            except Exception as exc:  # noqa: BLE001
                failed += 1
                print(f"   {index}/{len(targets)} ❌ {doc['title']}：{exc}")

        after = http.get(f"{ai}/api/ai/v1/kb/health").json()
        print(f"\n③ 重建完成：成功 {ok} 份，失败 {failed} 份")
        print(f"   重建后切片向量来源：{after.get('chunkVectorModels')}")
        print(f"   {after.get('vectorQualityHint')}")
        if failed == 0 and not (after.get("fallbackDocIds") or []):
            print("\n✅ 全部换成真实向量了；现在可以用提问验证：")
            print('   python scripts/verify-rag-ask.py --ask "退款之后多久可以到账？"')


if __name__ == "__main__":
    main()
