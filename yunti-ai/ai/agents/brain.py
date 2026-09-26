"""AI 客服大脑的编排：意图 → 情绪 → （该转就转） → 查资料 → 回答 → 落状态。

为什么用 LangGraph 画：这条链路有两个**真正的分支点**——

    recognize ──▶ emotion ──┬─(要转人工)────────────────▶ escalate ─┐
                            └─(先自己答)──▶ recall ──┬─(答不上来)──▶ escalate ─┤
                                                    └─(有依据)──▶ answer ─▶ persist ─▶ END

  ① 意图/情绪一出来就能判断"要不要转人工"——客户点名要人工、或者正在骂人，
     这时候还去查一遍知识库纯属浪费（一次向量化 + 一次模型调用）；
  ② 只有走"先自己答"这条路，才去看检索结果够不够，够不着再转。

写成 if/else 也能跑，但这两个分支点的判断依据（命中关键词 / 意图标了 escalate /
情绪等级 / 未解决轮次）会散在各处；画成图之后每一步的结论都留在 `steps` 里，
前端能直接展示"这轮机器人在想什么"，出问题也能按步骤复盘。

没装 langgraph 时退化成同样步骤的线性实现（`_linear_flow`），流程与结果一致。
"""

from __future__ import annotations

import logging
import re
from typing import Any, TypedDict

from ..services import brain as svc
from ..rag import graph as rag_graph

logger = logging.getLogger(__name__)

# 规则判定足够确定时跳过"模型分析"这一跳（省一次模型调用，详见 node_recognize）
#   意图置信度 >= 0.6 且 情绪强度 < 20（不是抱怨/生气）时，规则的结果已经够用
RULE_SKIP_MODEL_CONFIDENCE = 0.6
RULE_SKIP_MODEL_EMOTION_SCORE = 20


class BrainState(TypedDict, total=False):
    """图里流转的状态。"""

    # 入参
    question: str
    tenant_code: str
    session_no: str
    history: list[dict[str, str]]
    top_k: int

    # 配置与多轮状态
    setting: dict[str, Any]
    intents: list[dict[str, Any]]
    dialogue: dict[str, Any]
    model_result: dict[str, Any] | None

    # 判断结果
    intent: dict[str, Any] | None
    # 这个意图是"规则命中"还是"模型判的"：转人工要不要采信，看的就是它
    intent_from_rule: bool
    confidence: float
    emotion: str
    emotion_score: int
    slots: dict[str, str]
    turn_count: int
    unresolved_rounds: int
    need_human: bool
    transfer_reason: str
    escalated_by: str
    enabled: bool

    # 回答
    citations: list[dict]
    enough: bool
    reply: str
    # 固定问答（寒暄/自我介绍）的直接回答：有它就跳过检索与转人工判定
    fixed_reply: str
    # 挂在消息上的可点动作（例如"转人工客服"按钮）：LangGraph 只透传声明过的键，
    # 忘了声明的话节点返回了也会被丢掉，前端就永远看不到按钮
    actions: list[dict]
    steps: list[dict]


def _indent_question(question: str, limit: int = 400) -> str:
    """问题可能带图片上下文（多行），压成一行并截断，日志才不会被刷乱"""
    text = " / ".join(line.strip() for line in (question or "").splitlines() if line.strip())
    return text[:limit] + ("…" if len(text) > limit else "")


def _step(state: BrainState, node: str, detail: str, **extra: Any) -> list[dict]:
    steps = list(state.get("steps") or [])
    steps.append({"node": node, "detail": detail, **extra})
    return steps


# --------------------------------------------------------------------------- 节点


async def node_recognize(state: BrainState) -> dict:
    """意图识别：先规则，后模型；模型结果必须落在租户配置的意图清单里。"""
    setting = svc.load_setting(state["tenant_code"])
    intents = svc.load_intents(state["tenant_code"])
    dialogue = svc.load_dialogue(state["tenant_code"], state.get("session_no") or "")

    # 寒暄 / "你是谁" / "你能做什么" 这类固定问答：直接给话术，既不检索也不判转人工。
    # 放到最前面是因为它们和业务意图无关——硬塞进意图识别，模型偶尔会把它当成"想找人工"。
    small_kind = svc.match_small_talk(state["question"])
    fixed_reply = svc.small_talk_reply(small_kind, setting) if small_kind else ""
    if small_kind:
        logger.info("命中固定问答 kind=%s tenant=%s session=%s 直接按话术回答，不检索、不判转人工",
                    small_kind, state["tenant_code"], state.get("session_no") or "-")

    rule_intent, rule_confidence, matched = svc.match_intent(state["question"], intents)
    # 规则判得又准又"情绪平稳"时，就**不再多花一次模型调用**：
    # 一轮对话本来要调两次模型（先判意图情绪、再生成回答），一次 1~2 秒，
    # 客户看到的就是"发了消息干等五六秒"。规则已经够确定的场景（比如"我要退款"）
    # 直接进检索+生成，等待时间差不多砍一半，token 成本也省一次。
    rule_emotion, rule_emotion_score, _ = svc.detect_emotion(state["question"])
    confident = (
        not small_kind
        and rule_intent is not None
        and rule_confidence >= RULE_SKIP_MODEL_CONFIDENCE
        and rule_emotion_score < RULE_SKIP_MODEL_EMOTION_SCORE
        and not svc.hit_transfer_keyword(state["question"], svc.transfer_keywords(setting))
    )
    if confident:
        model_result = None
        logger.info("规则判定已经很确定（意图=%s 置信度=%.2f），跳过模型分析这一跳，直接检索生成",
                    (rule_intent or {}).get("name"), rule_confidence)
    else:
        model_result = await svc.analyze_with_llm(
            state["question"], state.get("history") or [], intents, state["tenant_code"],
            dialogue.get("slots"))

    intent = rule_intent
    confidence = rule_confidence
    source = "rule" if rule_intent is not None else "none"
    if model_result:
        model_intent = svc.normalize_intent(model_result.get("intent"), intents)
        try:
            model_confidence = float(model_result.get("confidence") or 0) / 100.0
        except (TypeError, ValueError):
            model_confidence = 0.0
        # 模型认出来的意图优先（它能听懂"快递怎么还没到"其实是查物流）；
        # 但模型给的是"其他"、规则又命中了，就保留规则结果——模型不该把判得出来的判没了
        if model_intent is not None:
            intent, confidence, source = model_intent, round(model_confidence, 4), "model"
        elif rule_intent is not None:
            source = "rule(模型判为其他)"

    slots = svc.extract_slots(
        [state["question"]] + [m.get("content") or "" for m in (state.get("history") or [])],
        dialogue.get("slots"),
    )
    # 模型也抽了槽位就并进来（规则没覆盖到的格式它能补上）
    slots = svc.merge_slots(slots, (model_result or {}).get("slots"))
    turn_count = int(dialogue.get("turn_count") or 0) + 1
    logger.info("客服大脑意图 tenant=%s session=%s 意图=%s 置信度=%.2f 来源=%s 槽位=%s",
                state["tenant_code"], state.get("session_no") or "-",
                (intent or {}).get("name") or "其他", confidence, source, slots)
    # 把这一轮真正收到的问题打出来：客户发图片时，这里会看到"[客户发来一张图片] 判读：… 图中文字：…"，
    # 是"图里的内容有没有进大脑"的直接证据
    logger.info("客服大脑输入 tenant=%s session=%s 内容=%s",
                state["tenant_code"], state.get("session_no") or "-",
                _indent_question(state["question"]))
    return {
        "setting": setting,
        "intents": intents,
        "dialogue": dialogue,
        "fixed_reply": fixed_reply,
        "intent_from_rule": source.startswith("rule"),
        "model_result": model_result,
        "intent": intent,
        "confidence": confidence,
        "slots": slots,
        "turn_count": turn_count,
        "unresolved_rounds": int(dialogue.get("unresolved_rounds") or 0),
        "enabled": bool(setting.get("is_enabled", True)),
        "steps": _step(
            state, "recognize",
            f"意图「{(intent or {}).get('name') or '其他'}」置信度 {confidence:.2f}（{source}）"
            + (f"，命中语料片段 {len(matched)} 个" if matched else ""),
            intent=(intent or {}).get("name"), confidence=confidence, source=source,
            matchedTerms=matched[:8], turn=turn_count,
        ),
    }


def node_emotion(state: BrainState) -> dict:
    """情绪识别：规则打底，和模型结果取更严重的那个。"""
    rule = svc.detect_emotion(state["question"])
    emotion, score, words = svc.merge_emotion(rule, state.get("model_result"))
    return {
        "emotion": emotion,
        "emotion_score": int(score),
        "steps": _step(state, "emotion",
                       f"情绪「{emotion}」强度 {int(score)}"
                       + (f"，触发词：{'、'.join(words[:4])}" if words else ""),
                       emotion=emotion, score=int(score), words=words[:6]),
    }


async def node_recall(state: BrainState) -> dict:
    """查资料：复用第 21 篇的 RAG 编排（检索 → 判分 → 改写重检 → 生成 → 校验引用）。"""
    result = await rag_graph.ask(
        question=state["question"],
        tenant_code=state["tenant_code"],
        top_k=state.get("top_k", 5),
    )
    citations = result.get("citations") or []
    enough = bool(result.get("enough")) and bool(citations)
    return {
        "citations": citations,
        "enough": enough,
        "reply": result.get("answer") or "",
        "steps": _step(state, "recall",
                       f"知识库编排（{result.get('engine')}）检索，拿到 {len(citations)} 条依据，"
                       + ("够回答" if enough else "不够回答"),
                       citationCount=len(citations), enough=enough),
    }


def _route_after_emotion(state: BrainState) -> str:
    """第一次判断：固定问答直答；不用查资料就能定的转人工，直接走 escalate。"""
    if (state.get("fixed_reply") or "").strip():
        return "answer"
    if not state.get("enabled", True):
        return "escalate"
    if not bool((state.get("setting") or {}).get("reception_enabled", True)):
        return "escalate"
    need, _reason, _source = svc.decide_escalation(
        question=state["question"],
        setting=state.get("setting") or {},
        intents=state.get("intents") or [],
        intent=state.get("intent"),
        emotion=state.get("emotion") or svc.EMOTION_NEUTRAL,
        model=state.get("model_result"),
        unresolved_rounds=int(state.get("unresolved_rounds") or 0),
        enough=True,
        intent_from_rule=bool(state.get("intent_from_rule")),
    )
    return "escalate" if need else "recall"


def _route_after_recall(state: BrainState) -> str:
    """第二次判断：拿着检索结果再看一次"是不是答不了"。"""
    need, _reason, _source = svc.decide_escalation(
        question=state["question"],
        setting=state.get("setting") or {},
        intents=state.get("intents") or [],
        intent=state.get("intent"),
        emotion=state.get("emotion") or svc.EMOTION_NEUTRAL,
        model=state.get("model_result"),
        unresolved_rounds=int(state.get("unresolved_rounds") or 0),
        enough=bool(state.get("enough")),
        intent_from_rule=bool(state.get("intent_from_rule")),
    )
    return "escalate" if need else "answer"


def node_answer(state: BrainState) -> dict:
    """组织回复：只给答案本身；查不到资料时用兜底话术，绝不编答案。

    <p>欢迎语**不在这里拼**——它属于"会话开场"，客户进线时由 customer-service 单独发一条
    （见 SessionService.markBotReception）。粘在第一句回答前面，就成了"客户已经问完问题了，
    机器人还自我介绍一遍"，是实打实的废话。</p>
    """
    setting = state.get("setting") or {}
    fixed = (state.get("fixed_reply") or "").strip()
    if fixed:
        # 寒暄/自我介绍：直接回落配置话术，不因为有它而把"连续未解决"计数往上加
        return {
            "reply": fixed,
            "unresolved_rounds": int(state.get("unresolved_rounds") or 0),
            "need_human": False,
            "steps": _step(state, "answer", "命中固定问答，按配置话术直接回答（不检索、不判转人工）",
                           fixed=True),
        }
    enough = bool(state.get("enough"))
    body = (state.get("reply") or "").strip()
    if not enough and not body:
        body = str(setting.get("fallback_message") or svc.DEFAULT_SETTING["fallback_message"])
    reply = strip_citation_marks(body)
    if not reply:
        reply = str(svc.DEFAULT_SETTING["fallback_message"])

    # 欢迎语不在这里拼：它属于"会话开场"（客户进线时单独发一条，见 SessionService.markBotReception）。
    # 拼在第一句回答前面会变成"客户已经问了问题，机器人还自我介绍一遍"，纯属废话。

    # 模型自己都说了"资料里没有提到"——那就不能当成"已解决"。
    # 以前这种情况算解答成功、轮次清零，客户只能反复问或者自己走人；
    # 现在按未解决计数，并且它一旦建议找人工，就当场转人工（别让客户照着我们自己说的话再重复一遍）。
    has_material = enough and bool(state.get("citations"))
    admitted = _admits_no_basis(reply)
    suggests_human = has_material and _suggests_human(reply)
    unresolved = (0 if (enough and not admitted)
                  else int(state.get("unresolved_rounds") or 0) + 1)
    if admitted or suggests_human:
        logger.warning(
            "机器人回答里承认没有依据 tenant=%s session=%s 轮次=%d 建议转人工=%s 回答=%s",
            state["tenant_code"], state.get("session_no") or "-", unresolved,
            suggests_human, reply[:80])

    result = {
        "reply": reply,
        "unresolved_rounds": unresolved,
        # 不再"代客户决定"转人工：只挂一个按钮，客户点了才转（见 actions）。
        # 反正连错两轮就会按租户配置自动转，客户不会被一直晾着。
        "need_human": False,
        "steps": _step(state, "answer",
                       f"机器人生成回复（{len(state.get('citations') or [])} 条依据）",
                       citationCount=len(state.get("citations") or []), enough=enough,
                       admittedNoBasis=admitted, suggestHuman=suggests_human),
    }
    if admitted or suggests_human:
        # 给客户一个"一键转人工"的按钮：话术里说了"建议联系人工"，
        # 但客户不该自己去翻找入口——把动作直接摆在眼前
        result["actions"] = [{"type": "TRANSFER_HUMAN", "label": "转人工客服"}]
        # 顺便把"怎么找人工"的提示语附上（这个字段本来就该用在这儿）
        prompt = str(setting.get("transfer_prompt") or "").strip()
        if prompt and prompt not in reply:
            result["reply"] = f"{reply}\n{prompt}"
    return result


# 回答里的引用编号形如 [1]、[2][3]：是给"引用溯源"内部核对用的，
# 客户看到只会是一串莫名的方括号（"……到账[1][3]。"），所以对客户一律去掉。
CITATION_MARK_RE = re.compile(r"\s*\[\d{1,2}\]")


def strip_citation_marks(text: str) -> str:
    """去掉回答里的 [n] 引用编号，并顺手收拾留下的多余空格/标点前空格。

    只认"方括号里是 1~2 位数字"，所以 Markdown 链接 ``[文字](url)``、列表序号
    ``[重要]`` 这类都不会被误伤。
    """
    if not text:
        return text or ""
    cleaned = CITATION_MARK_RE.sub("", text)
    # "[1]" 常紧跟汉字或标点，去掉后可能留下"……到账 。"这种怪空格
    cleaned = re.sub(r"\s+([，。！？；：、,.!?;:])", r"\1", cleaned)
    return re.sub(r"[ \t]{2,}", " ", cleaned).strip()


# 模型承认"没查到依据"的常见说法：命中就按未解决算，不再当作回答成功
NO_BASIS_HINTS = ("资料里没有", "资料中没有", "没有提到", "未提到", "没有涉及",
                  "查不到", "没有查到", "未找到", "无法确认")

# 模型自己建议找人工的说法：命中就当场转人工，别让客户照着我们的话再重复一遍
HUMAN_HINTS = ("联系人工", "转人工", "人工客服", "咨询人工")


def _admits_no_basis(reply: str) -> bool:
    text = reply or ""
    return any(hint in text for hint in NO_BASIS_HINTS)


def _suggests_human(reply: str) -> bool:
    text = reply or ""
    return any(hint in text for hint in HUMAN_HINTS)


def node_escalate(state: BrainState) -> dict:
    """转人工：给客户一句交接话术，同时把原因留给业务侧（坐席能看到为什么转过来）。"""
    setting = state.get("setting") or {}
    if not bool(state.get("enabled", True)):
        reason, source = "机器人服务已关闭", "disabled"
    elif not bool(setting.get("reception_enabled", True)):
        reason, source = "未开启机器人首轮接待", "reception_off"
    else:
        _need, reason, source = svc.decide_escalation(
            question=state["question"],
            setting=setting,
            intents=state.get("intents") or [],
            intent=state.get("intent"),
            emotion=state.get("emotion") or svc.EMOTION_NEUTRAL,
            model=state.get("model_result"),
            unresolved_rounds=int(state.get("unresolved_rounds") or 0),
            enough=bool(state.get("enough")),
            intent_from_rule=bool(state.get("intent_from_rule")),
        )
        if not reason:
            reason, source = "机器人主动转人工", "manual"
    # 转人工说"确认语"，不是"提示语"：
    #   transfer_message —— "好的，正在为您转接人工客服，请稍候。"（已经在转了）
    #   transfer_prompt  —— "如需人工客服，请回复转人工…"（教客户怎么找人工，见 node_answer）
    # 之前两者用同一个字段，客户说完"转人工"却被回了一句"请回复转人工"，看起来就像没转。
    already = bool((state.get("dialogue") or {}).get("transferred"))
    if already:
        # 客户在排队里又催了一次：给一句"已经在办了"，别再重复同一句
        reply = "已经为您转接人工客服了，正在排队等待客服接入，请稍候～"
    else:
        reply = str(setting.get("transfer_message")
                    or svc.DEFAULT_SETTING["transfer_message"]).strip()
    logger.info("客服大脑转人工 tenant=%s session=%s 原因=%s 来源=%s 第%s次要求",
                state["tenant_code"], state.get("session_no") or "-", reason, source,
                "二" if already else "一")
    return {
        "reply": reply,
        "need_human": True,
        "transfer_reason": reason,
        "escalated_by": source,
        "citations": [],
        "steps": _step(state, "escalate", f"转人工：{reason}", reason=reason, by=source),
    }


def node_persist(state: BrainState) -> dict:
    """落状态：多轮记忆 + 意图命中统计。写失败不影响这次回复。"""
    intent = state.get("intent") or {}
    svc.bump_intent_hit(state["tenant_code"], intent.get("intent_code"),
                        round(float(state.get("confidence") or 0) * 100, 2))
    svc.save_dialogue(state["tenant_code"], state.get("session_no") or "", {
        "turn_count": int(state.get("turn_count") or 0),
        "unresolved_rounds": int(state.get("unresolved_rounds") or 0),
        "last_intent": intent.get("name") or "其他",
        "last_intent_code": intent.get("intent_code"),
        "last_confidence": round(float(state.get("confidence") or 0) * 100, 2),
        "last_emotion": state.get("emotion"),
        "emotion_score": int(state.get("emotion_score") or 0),
        "slots": state.get("slots") or {},
        "transferred": bool(state.get("need_human")),
        "transfer_reason": state.get("transfer_reason"),
        "last_message": state["question"],
    })
    return {}


# --------------------------------------------------------------------------- 图


def langgraph_available() -> bool:
    import importlib.util

    try:
        return importlib.util.find_spec("langgraph.graph") is not None
    except (ImportError, ModuleNotFoundError, ValueError):
        return False


def engine_name() -> str:
    return "langgraph" if langgraph_available() else "linear"


def _build_graph():
    from langgraph.graph import END, START, StateGraph

    builder = StateGraph(BrainState)
    builder.add_node("recognize", node_recognize)
    builder.add_node("emotion", node_emotion)
    builder.add_node("recall", node_recall)
    builder.add_node("answer", node_answer)
    builder.add_node("escalate", node_escalate)
    builder.add_node("persist", node_persist)

    builder.add_edge(START, "recognize")
    builder.add_edge("recognize", "emotion")
    builder.add_conditional_edges("emotion", _route_after_emotion,
                                  {"escalate": "escalate", "recall": "recall", "answer": "answer"})
    builder.add_conditional_edges("recall", _route_after_recall,
                                  {"escalate": "escalate", "answer": "answer"})
    builder.add_edge("answer", "persist")
    builder.add_edge("escalate", "persist")
    builder.add_edge("persist", END)
    return builder.compile()


_graph = None


async def think(question: str, tenant_code: str, session_no: str = "",
                history: list[dict[str, str]] | None = None, top_k: int = 5) -> dict[str, Any]:
    """客服大脑主入口：一句话进去，意图 / 情绪 / 回复 / 是否转人工一起出来。"""
    global _graph
    state: BrainState = {
        "question": (question or "").strip(),
        "tenant_code": tenant_code,
        "session_no": session_no or "",
        "history": history or [],
        "top_k": top_k,
        "steps": [],
    }
    if langgraph_available():
        if _graph is None:
            _graph = _build_graph()
            logger.info("客服大脑编排已就绪：LangGraph StateGraph")
        final: BrainState = await _graph.ainvoke(state)
    else:
        logger.info("客服大脑编排：线性实现（未装 langgraph）")
        final = await _linear_flow(state)

    intent = final.get("intent") or {}
    return {
        "reply": final.get("reply") or "",
        "intent": intent.get("name") or "其他",
        "intent_code": intent.get("intent_code"),
        "confidence": round(float(final.get("confidence") or 0) * 100, 2),
        "emotion": final.get("emotion") or svc.EMOTION_NEUTRAL,
        "emotion_score": int(final.get("emotion_score") or 0),
        "slots": final.get("slots") or {},
        "turn": int(final.get("turn_count") or 0),
        "unresolved_rounds": int(final.get("unresolved_rounds") or 0),
        "need_human": bool(final.get("need_human")),
        "transfer_reason": final.get("transfer_reason") or "",
        "escalated_by": final.get("escalated_by") or "",
        "bot_enabled": bool(final.get("enabled", True)),
        "citations": final.get("citations") or [],
        # 消息上要挂的"可点动作"（目前只有转人工）；空列表表示纯文本消息
        "actions": final.get("actions") or [],
        "engine": engine_name(),
        "steps": final.get("steps") or [],
    }


async def _linear_flow(state: BrainState) -> BrainState:
    """没装 langgraph 时的等价实现：分支判断完全一致，只是写成顺序代码。"""
    state.update(await node_recognize(state))
    state.update(node_emotion(state))
    if _route_after_emotion(state) == "escalate":
        state.update(node_escalate(state))
        state.update(node_persist(state))
        return state
    state.update(await node_recall(state))
    if _route_after_recall(state) == "escalate":
        state.update(node_escalate(state))
    else:
        state.update(node_answer(state))
    state.update(node_persist(state))
    return state
