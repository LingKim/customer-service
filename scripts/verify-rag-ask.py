"""知识问答（RAG + 引用溯源）验证：问得出来、引得到出处、编的出处会被剔掉。

前提：
    1. 已上传示例文档（python scripts/upload-kb-samples.py）；
    2. yunti-ai(9100) 与 customer-service(9093) 都在跑；
    3. 想验证"真实生成回答"，需要配 QWEN_API_KEY 或 DEEPSEEK_API_KEY；
       没配也能跑，只是回答会退化成"先把检索到的原文给你"。

跑法：
    python scripts/verify-rag-ask.py
    python scripts/verify-rag-ask.py --http http://127.0.0.1:19314 --ai http://127.0.0.1:19100
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import time

import httpx

SECRET = b"yunti-customer-service-jwt-secret-please-change-in-prod-0123456789"
TENANT = "T202609050000002"
AGENT_ID = "222685643665313792"
AGENT_NAME = "王梓涵"


def b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def agent_token() -> str:
    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {"sub": AGENT_ID, "uno": "U202609071953146315", "name": AGENT_NAME,
               "tnt": TENANT, "type": 2, "iat": now, "exp": now + 1800}
    signing = (f"{b64(json.dumps(header, separators=(',', ':')).encode())}."
               f"{b64(json.dumps(payload, separators=(',', ':')).encode())}")
    return f"{signing}.{b64(hmac.new(SECRET, signing.encode(), hashlib.sha256).digest())}"


def ask(http: httpx.Client, base: str, headers: dict, question: str) -> dict:
    body = (http.post(f"{base}/api/customer/kb/ask", headers=headers,
                      json={"question": question, "topK": 5})).json()
    assert body["code"] == 0, body
    return body["data"]


def diagnose_ask(http: "httpx.Client", base: str, headers: dict, ai: str,
                 question: str, health: dict) -> None:
    """把一句提问的判定过程完整打出来（排障用）。"""
    print(f"问题：{question}\n")
    print(f"① 编排层体检：{json.dumps(health, ensure_ascii=False)}")
    try:
        kb_health = http.get(f"{ai}/api/ai/v1/kb/health").json()
        print(f"\n② 知识库体检：{json.dumps(kb_health, ensure_ascii=False)}")
    except Exception as exc:  # noqa: BLE001
        print(f"\n② 知识库体检拿不到：{exc}")

    result = ask(http, base, headers, question)
    print(f"\n③ 回答：{result['answer']}")
    print(f"\n④ 资料够用（enough）：{result['enough']}   检索模式：{result['mode']}")
    print(f"\n⑤ 召回 {len(result.get('retrieved') or [])} 条切片：")
    for index, hit in enumerate(result.get("retrieved") or [], start=1):
        print(f"   [{index}] 相似度 {hit.get('score')} · {hit.get('doc_title')} 第 "
              f"{hit.get('chunk_no')} 块 · {(hit.get('content') or '')[:60]}…")
    print(f"\n⑥ 引用 {len(result.get('citations') or [])} 条："
          + "、".join(f"{c.get('doc_title')}#{c.get('chunk_no')}"
                      for c in (result.get("citations") or [])))
    print("\n⑦ 编排轨迹：")
    for step in result.get("steps") or []:
        print(f"   - {step.get('node')}: {step.get('detail')}")
    print("""
判读提示：
  · 召回 0 条        → 文档没索引，或索引时用的是兜底向量（看②的 vectorQualityHint）
  · 召回了但分数低    → 机器人判定「资料不够」，可调 YUNTI_AI_RAG_VECTOR_MIN_SCORE（默认 0.35）
  · 召回的是别的块    → 还在用关键词匹配（看②的 chunkVectorModels 是不是 local-hash）：
                       修好向量密钥后到「知识库」重新索引，向量是索引那一刻算出来的
""")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--http", default=os.environ.get("KB_HTTP", "http://127.0.0.1:9093"))
    parser.add_argument("--ai", default=os.environ.get("KB_AI", "http://127.0.0.1:9100"))
    parser.add_argument("--ask", default=None,
                        help="只诊断一句提问：打印召回、相似度、阈值判定与编排轨迹")
    args = parser.parse_args()
    base = args.http.rstrip("/")
    headers = {"Authorization": f"Bearer {agent_token()}", "X-Tenant-Code": TENANT}
    checks: list[str] = []

    with httpx.Client(timeout=180) as http:
        health = http.get(f"{args.ai.rstrip('/')}/api/ai/v1/rag/health").json()
        # --ask：只问一句，把"这句话到底是怎么被判的"整条链路打出来。
        # "知识库明明有数据、机器人却说查不到"时先跑这个，一眼能看出是
        # 没检索到、检索到了但分数不够、还是检索到的是别的块。
        if args.ask:
            diagnose_ask(http, base, headers, args.ai.rstrip("/"), args.ask, health)
            return
        checks.append(f"编排层就绪：{health}")
        print(f"① 编排层：{health}")

        # ② 能查到资料的问题：要有回答 + 要有出处
        result = ask(http, base, headers, "退货多久能到账？")
        assert result["answer"].strip(), result
        assert result["citations"], "回答没有带任何引用来源"
        checks.append(f"问答有回答：{result['answer'][:40].strip()}…")
        print(f"② 回答：{result['answer'][:60].strip()}…")

        # ③ 引用来源要带得出文档名和切片号（字段名对不上就会变成 None）
        first = result["citations"][0]
        assert first["docTitle"] and first["docTitle"] != "None", first
        assert first["chunkNo"], first
        refs = "、".join(f"{item['docTitle']}#{item['chunkNo']}" for item in result["citations"])
        checks.append(f"引用来源可读：{refs}")
        print(f"③ 引用来源 {len(result['citations'])} 条，首条：{first['docTitle']} 第 {first['chunkNo']} 块")

        # ④ 回答里的 [n] 必须落在引用范围内（越界的应该被校验环节剔掉）
        used = {int(m) for m in re.findall(r"\[(\d{1,2})\]", result["answer"])}
        valid = {int(item["index"]) for item in result["citations"]}
        assert used <= valid, f"回答里出现了范围外的引用：{used - valid}"
        checks.append(f"引用编号都在范围内：{sorted(used)} ⊆ {sorted(valid)}")
        print(f"④ 回答里引用了 {sorted(used)}，都在来源范围内")

        # ⑤ 编排轨迹要能看到每一步（LangGraph 的价值就在这条链路上）
        nodes = [step["node"] for step in result["steps"]]
        assert "retrieve" in nodes and "grade" in nodes and "answer" in nodes and "verify" in nodes, nodes
        checks.append(f"编排轨迹完整：{' → '.join(nodes)}")
        print(f"⑤ 编排轨迹：{' → '.join(nodes)}")

        # ⑥ 问一个知识库里根本没有的问题：不许编
        #    判定放宽成"三者满足其一"：没有引用 / 回答里明说资料没有 / 标记资料不够用。
        #    配了真实向量时，无关问题也可能召回几条低分切片，这时靠 enough=False 兜住，
        #    不能因为回答里没出现某个固定词就判失败。
        unknown = ask(http, base, headers, "你们的火星仓库什么时候开业？")
        hints = ("没有", "未", "无法", "建议您联系", "转人工", "查不到")
        assert (unknown["citations"] == []
                or any(word in unknown["answer"] for word in hints)
                or not unknown["enough"]), unknown
        checks.append("问知识库没有的内容：不编造，明确说明资料里没有")
        print(f"⑥ 无资料问题：{unknown['answer'][:50].strip()}…（资料够用={unknown['enough']}）")

        print("\n=== 验证通过 ===")
        for index, item in enumerate(checks, start=1):
            print(f"  {index}. {item}")


if __name__ == "__main__":
    main()
