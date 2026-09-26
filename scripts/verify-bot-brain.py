"""AI 客服大脑验证：意图识别 / 情绪识别 / 多轮对话 / 自动转人工。

两种跑法：

  1. **离线自检**（不连库、不连服务，只验判断逻辑，随时可跑）：

        cd yunti-ai && ./.venv/bin/python ../scripts/verify-bot-brain.py --offline

  2. **真实链路**（yunti-ai 9100 + customer-service 9093 都在跑）：

        python scripts/verify-bot-brain.py
        python scripts/verify-bot-brain.py --http http://127.0.0.1:9093

     真实链路会真开一条访客会话、真发消息，然后看机器人回没回、
     意图/情绪有没有落到会话上、说"转人工"时状态有没有变回排队中。

前提（真实链路）：
    - 已执行 `bash scripts/migrate-ai-db.sh`（接待策略字段 + bot_dialogue 表）；
    - 想看到"模型版"的识别效果，需要在 yunti-ai 配好 QWEN_API_KEY；
      没配也能跑，意图/情绪走规则，结果照样能断言。
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import hashlib
import hmac
import json
import os
import sys
import time

SECRET = b"yunti-customer-service-jwt-secret-please-change-in-prod-0123456789"
TENANT = "T202609050000002"
APP_KEY = "R9B9yYV327uAYCTevRHvYYamyNFNGs7W"
AGENT_ID = "222685643665313792"   # 王梓涵
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


# --------------------------------------------------------------------------- 离线自检


def offline() -> int:
    """不连库不连服务，只验判断逻辑：意图、情绪、多轮、转人工四个能力各来一遍。"""
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
    sys.path.insert(0, os.path.join(root, "yunti-ai"))
    try:
        import ai.agents.brain as brain
        import ai.rag.graph as rag_graph
        import ai.services.brain as svc
    except Exception as exc:  # noqa: BLE001
        print(f"❌ 导入 yunti-ai 失败（请在项目根目录、用 yunti-ai/.venv 的 python 跑）：{exc}")
        return 1

    intents = [
        {"intent_code": "IT0001", "name": "查询物流",
         "samples": "我的快递到哪了\n帮我查一下订单物流\n订单什么时候发货", "escalate": False},
        {"intent_code": "IT0002", "name": "退款售后",
         "samples": "我要退款\n怎么申请售后\n商品有问题怎么退\n退货多久到账", "escalate": False},
        {"intent_code": "IT0004", "name": "人工客服",
         "samples": "转人工\n找人工客服\n我要投诉", "escalate": True},
    ]
    state = {"dialogue": {}}

    # 打桩：把"读配置/读状态/写状态"换成内存实现，判断逻辑本身一行不改
    svc.load_setting = lambda tenant: dict(svc.DEFAULT_SETTING)
    svc.load_intents = lambda tenant: intents
    svc.load_dialogue = lambda tenant, session: (
        dict(state["dialogue"]) if state["dialogue"]
        else {"turn_count": 0, "unresolved_rounds": 0, "slots": {}})

    def save(tenant, session, payload):
        state["dialogue"] = {
            "turn_count": payload["turn_count"],
            "unresolved_rounds": payload["unresolved_rounds"],
            "slots": payload.get("slots") or {},
        }

    svc.save_dialogue = save
    svc.bump_intent_hit = lambda *args: None

    async def fake_rag(question, tenant_code, top_k=5, doc_ids=None):
        if "退货" in question or "退款" in question:
            hit = {"id": 1, "doc_id": 9, "doc_title": "退款与售后政策说明", "chunk_no": 2,
                   "score": 0.88, "content": "退货签收后 1 个工作日原路退款。"}
            return {"answer": "退货签收后 1 个工作日内原路退款[1]。",
                    "citations": [{**hit, "index": 1}], "retrieved": [hit],
                    "mode": "vector", "engine": "langgraph", "enough": True, "steps": []}
        return {"answer": "", "citations": [], "retrieved": [], "mode": "vector",
                "engine": "langgraph", "enough": False, "steps": []}

    rag_graph.ask = fake_rag

    async def ask(question):
        return await brain.think(question, TENANT, session_no="S-VERIFY", history=[])

    failures: list[str] = []

    def check(label: str, condition: bool, detail: str = "") -> None:
        print(("  ✅ " if condition else "  ❌ ") + label + (f"（{detail}）" if detail else ""))
        if not condition:
            failures.append(label)

    print("① 意图识别 + 多轮槽位")
    r1 = asyncio.run(ask("我要退款，订单号 2026091700012345"))
    check("识别出「退款售后」", r1["intent"] == "退款售后",
          f"实际={r1['intent']} 置信度={r1['confidence']}")
    check("抽出订单号槽位", r1["slots"].get("order_no") == "2026091700012345", f"slots={r1['slots']}")
    check("知识库能答就不转人工", r1["need_human"] is False, f"转人工={r1['need_human']}")
    check("走的是完整编排",
          [s["node"] for s in r1["steps"]] == ["recognize", "emotion", "recall", "answer"],
          "步骤=" + "→".join(s["node"] for s in r1["steps"]))

    print("② 明确要求转人工（关键词 + 意图 escalate）")
    r2 = asyncio.run(ask("转人工"))
    check("转人工=true", r2["need_human"] is True, f"原因={r2['transfer_reason']}")
    check("触发来源是关键词", r2["escalated_by"] == "keyword", f"by={r2['escalated_by']}")
    check("跳过检索直接转",
          [s["node"] for s in r2["steps"]] == ["recognize", "emotion", "escalate"],
          "步骤=" + "→".join(s["node"] for s in r2["steps"]))

    print("③ 情绪识别 + 情绪激动自动转人工")
    r3 = asyncio.run(ask("你们这什么破服务！气死我了！我要投诉！！！"))
    check("情绪判为愤怒", r3["emotion"] == "愤怒", f"情绪={r3['emotion']} 强度={r3['emotion_score']}")
    check("情绪触发转人工", r3["need_human"] is True,
          f"by={r3['escalated_by']} 原因={r3['transfer_reason']}")

    print("④ 多轮对话：连续答不上来 → 转人工")
    r4 = asyncio.run(ask("你们火星仓库什么时候开业"))
    check("第 1 轮先兜底、不转人工", r4["need_human"] is False, f"未解决轮次={r4['unresolved_rounds']}")
    r5 = asyncio.run(ask("你们火星仓库什么时候开业"))
    check("第 2 轮转人工", r5["need_human"] is True, f"原因={r5['transfer_reason']}")
    check("转人工原因是「连续未解决」", r5["escalated_by"] == "unresolved", f"by={r5['escalated_by']}")

    print("⑤ 固定问答：自我介绍不该被当成「要转人工」")
    r6 = asyncio.run(ask("你是谁？"))
    check("直接答出身份，不转人工", r6["need_human"] is False and "客服" in r6["reply"],
          f"回复={r6['reply'][:30]}…")
    check("不查知识库（固定话术直答）",
          [s["node"] for s in r6["steps"]] == ["recognize", "emotion", "answer"],
          "步骤=" + "→".join(s["node"] for s in r6["steps"]))

    print()
    if failures:
        print(f"❌ 离线自检未通过，失败 {len(failures)} 项：" + "、".join(failures))
        return 1
    print("✅ 离线自检全部通过：意图识别 / 情绪识别 / 多轮状态 / 自动转人工 / 固定问答 都在工作")
    return 0


# --------------------------------------------------------------------------- 真实链路


async def online(base: str) -> int:
    import httpx

    admin = {"Authorization": f"Bearer {agent_token()}", "X-Tenant-Code": TENANT}
    failures: list[str] = []

    def check(label: str, condition: bool, detail: str = "") -> None:
        print(("  ✅ " if condition else "  ❌ ") + label + (f"（{detail}）" if detail else ""))
        if not condition:
            failures.append(label)

    async with httpx.AsyncClient(timeout=60) as http:
        opened = (await http.post(f"{base}/api/customer/sessions/open",
                                  json={"appKey": APP_KEY, "visitorName": "大脑验证访客"})).json()
        assert opened["code"] == 0, opened
        session_no = opened["data"]["sessionNo"]
        print(f"① 已开会话 {session_no}（状态 {opened['data']['sessionStatus']}，2=机器人接待）")

        async def send(text: str) -> None:
            body = (await http.post(
                f"{base}/api/customer/internal/sessions/{session_no}/messages",
                json={"tenantCode": TENANT, "senderType": 1, "msgType": 1, "content": text})).json()
            assert body["code"] == 0, body

        async def messages() -> list[dict]:
            body = (await http.get(
                f"{base}/api/customer/internal/sessions/{session_no}/messages",
                params={"tenantCode": TENANT, "limit": 50})).json()
            return body["data"] if body["code"] == 0 else []

        async def wait_bot_reply(after_seq: int, timeout: float = 45) -> dict | None:
            deadline = time.time() + timeout
            while time.time() < deadline:
                for item in await messages():
                    if item.get("senderType") == 3 and (item.get("seq") or 0) > after_seq:
                        return item
                await asyncio.sleep(1.5)
            return None

        async def session_detail() -> dict:
            return (await http.get(f"{base}/api/customer/sessions/{session_no}",
                                   headers=admin)).json()["data"]

        async def last_seq() -> int:
            rows = await messages()
            return max((item.get("seq") or 0 for item in rows), default=0)

        # --- 场景 1：机器人接待 + 意图/情绪落库 ---
        await send("我要退款，订单号 2026091700012345")
        reply = await wait_bot_reply(0)
        check("机器人回了消息（不是只落库不回复）", reply is not None,
              ((reply or {}).get("content") or "")[:40])
        detail = await session_detail()
        check("会话处于「排队/机器人接待」状态", detail["status"] in (1, 2),
              f"status={detail['status']}")
        check("意图已写进会话", bool(detail.get("intent")), f"intent={detail.get('intent')}")
        check("情绪已写进会话", bool(detail.get("emotion")), f"emotion={detail.get('emotion')}")

        # --- 场景 2：明确要求转人工 → 记一条转人工流转 ---
        mark = await last_seq()
        await send("转人工")
        await wait_bot_reply(mark)
        transferred = None
        deadline = time.time() + 30
        while time.time() < deadline:
            events = (await http.get(f"{base}/api/customer/sessions/{session_no}/events",
                                     headers=admin)).json()["data"]
            # 6-机器人转人工（1-转接、2-升级、3-分配、4-关闭、5-超时）
            transferred = next((item for item in events if item["eventType"] == 6), None)
            if transferred:
                break
            await asyncio.sleep(1.5)
        check("转人工：流转记录里有「6-机器人转人工」", transferred is not None,
              ((transferred or {}).get("remark") or ""))
        check("转人工：原因写清了为什么转", bool((transferred or {}).get("remark")),
              ((transferred or {}).get("remark") or ""))
        detail = await session_detail()
        check("转人工：原因也落在会话上（列表/头部直接显示）",
              bool(detail.get("botTransferReason")), f"reason={detail.get('botTransferReason')}")

        # --- 场景 3：接待记录与意图统计可查 ---
        records = (await http.get(f"{base}/ai/v1/bot/dialogues",
                                  headers={"X-Tenant-Code": TENANT},
                                  params={"limit": 50})).json()
        items = records.get("data") or []
        mine = next((item for item in items if item.get("sessionNo") == session_no), None)
        check("接待记录里能查到这个会话", mine is not None, f"共 {len(items)} 条记录")
        if mine:
            check("记录里有轮次与意图", (mine.get("turnCount") or 0) >= 1,
                  f"轮次={mine.get('turnCount')} 意图={mine.get('lastIntent')}")

        stats = (await http.get(f"{base}/ai/v1/bot/intent-stats",
                                headers={"X-Tenant-Code": TENANT})).json()["data"]
        check("意图统计可用", (stats.get("totalSessions") or 0) >= 1,
              f"会话={stats.get('totalSessions')} 轮次={stats.get('totalTurns')} "
              f"转人工={stats.get('transferredSessions')}")

        print(f"\n会话号：{session_no}（需要的话自行清理）")

    print()
    if failures:
        print(f"❌ 真实链路验证未通过，失败 {len(failures)} 项：" + "、".join(failures))
        return 1
    print("✅ 真实链路验证通过：机器人接待、意图/情绪落库、自动转人工、接待记录全部正常")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--offline", action="store_true", help="只验判断逻辑，不连服务")
    parser.add_argument("--http", default=os.environ.get("BOT_HTTP", "http://127.0.0.1:9093"))
    args = parser.parse_args()
    if args.offline:
        return offline()
    return asyncio.run(online(args.http.rstrip("/")))


if __name__ == "__main__":
    sys.exit(main())
