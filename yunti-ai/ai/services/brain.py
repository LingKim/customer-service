"""AI 客服大脑的"判断层"：意图识别、情绪识别、槽位抽取、转人工决策、多轮状态。

分工说明：
    - 检索与生成（RAG）在 services/rag.py，这一层只管"这句话什么意思、客户什么情绪、
      该不该转人工、这已经是第几轮没解决了"；
    - 编排（什么时候调哪个判断）在 agents/brain.py，用 LangGraph 画成状态机。

两个设计原则，都是被"AI 项目最常见的水分"逼出来的：
    1. **没有密钥也要能用**：意图和情绪都有规则实现（关键词 + 语料相似度），
       配了密钥再叠加模型判断。不允许出现"没配密钥就整条链路不可用"；
    2. **模型说的是"参考"，代码说了算**：模型返回的意图必须在租户配置的意图清单里，
       不在清单里一律落到"其他"。宁可判成"没识别出来"，也不要编一个不存在的意图。
"""

from __future__ import annotations

import json
import logging
import re
from typing import Any

from ..config import get_settings
from ..core.db import connect
from ..core.llm_chat import call_chat, message_content, resolve_provider
from ..core.trace import get_trace_id
from ..rag.store import next_id, query_terms

logger = logging.getLogger(__name__)

# --------------------------------------------------------------------------- 常量

EMOTION_NEUTRAL = "中性"
EMOTION_UPSET = "不满"
EMOTION_ANGRY = "愤怒"
EMOTION_ANXIOUS = "焦虑"

# 强烈不满：命中就基本可以判定"该转人工了"
ANGER_WORDS = (
    "垃圾", "废物", "骗子", "坑人", "气死", "滚", "恶心", "投诉", "举报", "起诉",
    "去死", "傻逼", "妈的", "妈的", "什么破", "太差了", "垃圾服务", "骗人",
)
# 一般不满
UPSET_WORDS = (
    "不满意", "太慢", "怎么还没", "没人管", "催", "催一下", "又出问题", "每次都",
    "失望", "差评", "退钱", "算了", "搞什么", "怎么回事", "搞什么鬼", "烦死了",
)
# 焦虑 / 着急
ANXIOUS_WORDS = ("急", "着急", "尽快", "马上", "什么时候能", "还要多久", "抓紧", "紧急")

# 标点 / 语气：连续感叹号、连续问号都是情绪的强信号
EXCLAIM_RE = re.compile(r"[!！]{2,}")
QUESTION_RE = re.compile(r"[?？]{2,}")
REPEAT_RE = re.compile(r"(.)\1{3,}")

# 槽位：客服场景里最常要客户补的两类信息
ORDER_NO_RE = re.compile(r"(?:订单号|订单编号|订单|单号|运单号|快递单号|工单号)[:：\s]*([A-Za-z0-9\-]{6,32})")
BARE_DIGITS_RE = re.compile(r"(?<!\d)(\d{10,20})(?!\d)")
PHONE_RE = re.compile(r"(?<!\d)(1[3-9]\d{9})(?!\d)")
EMAIL_RE = re.compile(r"[\w.+-]+@[\w-]+\.[\w.]+")

# 意图规则命中的最低相似度（命中片段占比）。低于它认为"规则判不出来"，交给模型
INTENT_RULE_MIN_SCORE = 0.34

# 默认策略：库里没配置时用这套，保证"装完就能跑"
DEFAULT_SETTING: dict[str, Any] = {
    "bot_name": "小云",
    "welcome_message": "您好，我是云梯智能客服小云，很高兴为您服务。您可以直接描述问题。",
    "fallback_message": "抱歉，我暂时无法准确回答您的问题，已为您记录并建议转人工客服进一步处理。",
    # 提示语：出现在"机器人答不上来"的回答里，告诉客户还有人工这条路
    "transfer_prompt": "如需人工客服，请回复“转人工”，或通过右上角电话联系我们。",
    # 确认语：客户真的要人工时回的那一句（与提示语分开，别混用）
    "transfer_message": "好的，正在为您转接人工客服，请稍候。",
    "is_enabled": True,
    "reception_enabled": True,
    "transfer_on_anger": True,
    "transfer_after_unresolved": 2,
    "transfer_keywords": "转人工,人工客服,找人工,要人工,人工",
    "model_key": None,
    "temperature": 0.3,
}


# --------------------------------------------------------------------------- 配置


def load_setting(tenant_code: str) -> dict[str, Any]:
    """读租户的机器人设置；读不到就用默认（不让配置缺失卡住对话）。"""
    setting = dict(DEFAULT_SETTING)
    if not tenant_code:
        return setting
    try:
        with connect() as conn:
            row = conn.execute(
                """
                SELECT bot_name, welcome_message, fallback_message, transfer_prompt, transfer_message,
                       is_enabled, reception_enabled, transfer_on_anger,
                       transfer_after_unresolved, transfer_keywords, model_key, temperature
                  FROM bot_setting
                 WHERE tenant_code = %s AND is_deleted = FALSE
                 LIMIT 1
                """,
                (tenant_code,),
            ).fetchone()
    except Exception as exc:  # noqa: BLE001
        # 库结构没升级（缺列）时也别把对话打挂：退回默认设置，日志里留一句
        logger.warning("读取机器人设置失败，使用默认设置 tenant=%s error=%s", tenant_code, exc)
        return setting
    if not row:
        return setting
    for key, value in row.items():
        if value is not None:
            setting[key] = value
    return setting


def load_intents(tenant_code: str) -> list[dict[str, Any]]:
    """读租户启用的意图（含语料与"是否直接转人工"）。"""
    if not tenant_code:
        return []
    try:
        with connect() as conn:
            rows = conn.execute(
                """
                SELECT intent_code, name, samples, escalate
                  FROM bot_intent
                 WHERE tenant_code = %s AND is_deleted = FALSE AND status = 1
                 ORDER BY id
                """,
                (tenant_code,),
            ).fetchall()
    except Exception as exc:  # noqa: BLE001
        logger.warning("读取意图清单失败 tenant=%s error=%s", tenant_code, exc)
        return []
    return list(rows)


def transfer_keywords(setting: dict[str, Any]) -> list[str]:
    raw = str(setting.get("transfer_keywords") or "")
    words = [item.strip() for item in raw.replace("，", ",").split(",")]
    return [word for word in words if word]


def bump_intent_hit(tenant_code: str, intent_code: str | None, confidence: float) -> None:
    """意图命中统计：次数 +1，置信度按历史做滚动平均（页面上的"识别率"要真实）。"""
    if not tenant_code or not intent_code:
        return
    try:
        with connect() as conn, conn.cursor() as cur:
            cur.execute(
                """
                -- 参数要显式 ::numeric：psycopg 把 Python 的 float 当 double precision 传，
                -- 而 PostgreSQL 只有 round(numeric, int)，没有 round(double precision, int)。
                -- 不转的话这里会报：function round(double precision, int) does not exist，
                -- 而且因为它被下面的 try 兜住，只留一行 WARN——"意图命中次数一直不涨"就是这么来的。
                UPDATE bot_intent
                   SET hit_count = hit_count + 1,
                       confidence = ROUND(
                           ((COALESCE(confidence, %s::numeric) * hit_count) + %s::numeric)
                           / (hit_count + 1), 2),
                       update_time = CURRENT_TIMESTAMP
                 WHERE tenant_code = %s AND intent_code = %s AND is_deleted = FALSE
                """,
                (confidence, confidence, tenant_code, intent_code),
            )
            conn.commit()
    except Exception as exc:  # noqa: BLE001
        logger.warning("更新意图命中统计失败 tenant=%s intent=%s error=%s",
                       tenant_code, intent_code, exc)


# --------------------------------------------------------------------------- 固定问答（寒暄 / 自我介绍）

# 客户常问的"关于你"的问题，不该去知识库翻、更不该转人工。
# 这些是产品话术，不是业务知识——配置在代码里、跟着机器人名字走，
# 命中就直答（对应 node_answer 里的 fixed_reply 分支，跳过检索与转人工判定）。
SMALL_TALK_RULES: tuple[tuple[str, tuple[str, ...]], ...] = (
    # 注意别写太宽的词："你是什么" 会误伤"你是什么时候发货的"，所以只留完整问法
    ("identity", ("你是谁", "你叫什么", "你是机器人", "你是人工", "你是真人",
                  "你哪位", "你是什么东西", "你是不是机器人")),
    ("capability", ("你能做什么", "你会什么", "你能干什么", "能帮我做什么", "会做什么",
                    "你会干嘛", "你能帮我查", "你有哪些功能")),
    ("greeting", ("你好", "您好", "在吗", "有人吗", "在么", "hello", "hi")),
    ("thanks", ("谢谢", "谢了", "感谢", "辛苦了", "好的谢谢", "多谢")),
)


# 打招呼/道谢必须"整句就是它"才算寒暄：客户很常写"你好，我想问下退款…"，
# 那种是正经问题，不能被"你好"两个字吃掉。
_SMALL_TALK_MAX_LEN = {"identity": 20, "capability": 20, "greeting": 6, "thanks": 6}


def match_small_talk(question: str) -> str | None:
    """命中固定问答就返回它的类型；没命中返回 None。

    匹配用"包含"就够了：这类问法短且固定（"你是谁""你能做什么"），
    没必要上语义检索；反过来，宁可少命中也不要把正经问题误判成寒暄。
    """
    text = (question or "").strip().lower().rstrip("?？!！。.~ ")
    if not text:
        return None
    for kind, samples in SMALL_TALK_RULES:
        if len(text) > _SMALL_TALK_MAX_LEN.get(kind, 20):
            continue
        if any(sample in text for sample in samples):
            return kind
    return None


def small_talk_reply(kind: str, setting: dict[str, Any]) -> str:
    """固定问答的话术：机器人名字取自租户配置，能力介绍写死（避免每次都不一样）。"""
    bot_name = str(setting.get("bot_name") or DEFAULT_SETTING["bot_name"]).strip()
    if kind == "identity":
        return (f"我是{bot_name}，云梯智能客服的机器人助手。"
                "退款、售后、物流、发票这类问题我可以直接帮您查；需要人工时随时说“转人工”。")
    if kind == "capability":
        return ("我可以帮您查退款与售后政策、物流时效、发票规则、会员积分等常见问题，"
                "也能帮您记录问题并转接人工客服。您直接描述遇到的问题就行。")
    if kind == "greeting":
        return f"您好，我是{bot_name}，请问有什么可以帮您？"
    if kind == "thanks":
        return "不客气～还有其他需要帮忙的吗？"
    return ""


# 只有客户**明确**要人工时才认这个信号；光靠模型说"该转人工"不够（见 decide_escalation）
HUMAN_SIGNAL_WORDS = ("人工", "客服", "真人", "投诉", "举报", "转接", "找人", "转人工",
                      "不想跟机器人", "别用机器人", "换个人")


# --------------------------------------------------------------------------- 意图


def match_intent(question: str, intents: list[dict[str, Any]]) -> tuple[dict[str, Any] | None, float, list[str]]:
    """规则版意图识别：客户这句话和哪个意图的语料重叠最多。

    为什么用"相邻两字片段"而不是分词：中文短句分词要装词典，而"我的快递到哪了"和
    "快递到哪了"靠两字片段就能对上，成本几乎为零（复用知识库关键词检索那套切片段逻辑）。
    """
    terms = query_terms(question, max_terms=20)
    if not terms or not intents:
        return None, 0.0, []
    best: dict[str, Any] | None = None
    best_score = 0.0
    best_hits: list[str] = []
    for intent in intents:
        samples = str(intent.get("samples") or "")
        if not samples.strip():
            continue
        corpus = set(query_terms(samples, max_terms=200))
        if not corpus:
            continue
        hits = [term for term in terms if term in corpus]
        score = len(hits) / len(terms)
        if score > best_score:
            best, best_score, best_hits = intent, score, hits
    if best is None or best_score < INTENT_RULE_MIN_SCORE:
        return None, round(best_score, 4), best_hits
    # 规则命中的置信度：相似度打个折（规则永远没有模型那么"敢确定"）
    return best, round(min(0.9, 0.5 + best_score * 0.5), 4), best_hits


ANALYZE_PROMPT = """你是企业在线客服的意图与情绪识别引擎。请分析客户的这句话。

可选意图（只能从这些里选，选不出来返回"其他"）：
{intents}

请只输出一个 JSON 对象，不要输出任何多余文字、不要用代码块包裹，格式：
{{"intent": "意图名称或其他", "confidence": 0-100 的整数,
  "emotion": "中性|不满|愤怒|焦虑", "emotion_score": 0-100 的整数,
  "slots": {{"order_no": "订单号或空字符串", "phone": "手机号或空字符串"}},
  "need_human": true 或 false, "reason": "一句话说明判成这个意图/情绪的依据"}}

判断口径：
1. 只有客户**明确**要求"转人工/找人工客服/投诉/找真人"时，intent 才选对应意图、need_human 才为 true；
   询问"你是谁""你是机器人吗""你能做什么"、打招呼、道谢都属于普通咨询，need_human 必须是 false；
2. 客户带辱骂、激烈抱怨、反复追问同一件事 → emotion 判"愤怒"或"不满"；
3. 只是催进度、问时间 → emotion 判"焦虑"，不一定要转人工；
4. 拿不准就返回 "其他"，不要硬猜。"""


async def analyze_with_llm(question: str, history: list[dict[str, str]],
                          intents: list[dict[str, Any]], tenant_code: str,
                          known_slots: dict[str, str] | None = None) -> dict[str, Any] | None:
    """让模型判意图 + 情绪 + 槽位。没配密钥或调用失败返回 None，由规则结果兜底。"""
    chosen, desc = resolve_provider(tenant_code)
    if chosen is None:
        return None
    names = [str(item.get("name")) for item in intents] or ["其他"]
    context = "\n".join(
        f"{'客户' if (m.get('role') or 'user') == 'user' else '客服'}：{m.get('content') or ''}"
        for m in history[-6:]
    ) or f"客户：{question}"
    if question and (not context or not context.endswith(question)):
        context = f"{context}\n客户：{question}" if context else f"客户：{question}"
    if known_slots:
        # 多轮的价值就在这里：客户上一轮报过的订单号 / 手机号要带上，
        # 否则模型会再问一遍"请提供订单号"——那就不叫多轮对话了
        known = "、".join(f"{key}={value}" for key, value in known_slots.items() if value)
        if known:
            context = f"{context}\n（已知信息，客户之前已经提供过，不要再问：{known}）"
    payload = {
        "model": chosen["model"],
        "temperature": 0.1,
        "messages": [
            {"role": "system", "content": ANALYZE_PROMPT.format(intents="、".join(names))},
            {"role": "user", "content": context},
        ],
    }
    logger.info("客服大脑分析请求 trace=%s tenant=%s 模型=%s 意图候选=%d",
                get_trace_id() or "-", tenant_code or "-", desc, len(names))
    try:
        data = await call_chat(chosen, payload, get_settings().llm_timeout, scene="客服大脑")
    except Exception as exc:  # noqa: BLE001
        logger.warning("客服大脑模型调用失败，退回规则判断 tenant=%s error=%s", tenant_code, exc)
        return None
    content = message_content(data)
    parsed = parse_json_object(content)
    logger.info("客服大脑分析出参 trace=%s provider=%s content=%s",
                get_trace_id() or "-", chosen["provider"], (content or "")[:500])
    return parsed


def parse_json_object(text: str) -> dict[str, Any] | None:
    """从模型输出里抠出 JSON 对象。

    模型很爱加 ```json 包裹或者前后加解释，这里做一次容错：先整体解析，
    不行就取第一对花括号之间的内容再解析，仍失败就返回 None（由规则兜底）。
    """
    if not text:
        return None
    candidate = text.strip()
    if candidate.startswith("```"):
        candidate = re.sub(r"^```[a-zA-Z]*\s*", "", candidate)
        candidate = re.sub(r"\s*```$", "", candidate)
    try:
        parsed = json.loads(candidate)
        return parsed if isinstance(parsed, dict) else None
    except json.JSONDecodeError:
        pass
    start = candidate.find("{")
    end = candidate.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        parsed = json.loads(candidate[start:end + 1])
        return parsed if isinstance(parsed, dict) else None
    except json.JSONDecodeError:
        return None


def normalize_intent(name: Any, intents: list[dict[str, Any]]) -> dict[str, Any] | None:
    """把模型给的意图名对齐到租户配置的意图上；对不上就是"没识别出来"。"""
    if not name:
        return None
    wanted = str(name).strip()
    for intent in intents:
        if wanted == str(intent.get("name")).strip():
            return intent
    for intent in intents:
        if wanted and (wanted in str(intent.get("name")) or str(intent.get("name")) in wanted):
            return intent
    return None


# --------------------------------------------------------------------------- 情绪


def detect_emotion(question: str) -> tuple[str, int, list[str]]:
    """规则版情绪识别。返回 (情绪, 0-100 强度, 触发词)。

    分档口径（先看有没有"重话"，再看语气符号，最后看一般抱怨词）：
        愤怒  ≥ 70    命中辱骂/要投诉，或连续感叹号 + 抱怨
        不满  ≥ 40
        焦虑  ≥ 20
        中性  其他
    """
    text = (question or "").strip()
    if not text:
        return EMOTION_NEUTRAL, 0, []
    hits: list[str] = []
    score = 0
    for word in ANGER_WORDS:
        if word in text:
            hits.append(word)
            score += 45
    for word in UPSET_WORDS:
        if word in text:
            hits.append(word)
            score += 22
    for word in ANXIOUS_WORDS:
        if word in text:
            hits.append(word)
            score += 12
    if EXCLAIM_RE.search(text):
        score += 18
    if QUESTION_RE.search(text):
        score += 8
    if REPEAT_RE.search(text):
        score += 10
    # 追问次数：同一句话重复问，本身就是"没解决"的信号
    score = min(100, score)
    if score >= 70:
        emotion = EMOTION_ANGRY
    elif score >= 40:
        emotion = EMOTION_UPSET
    elif score >= 20:
        emotion = EMOTION_ANXIOUS
    else:
        emotion = EMOTION_NEUTRAL
    return emotion, score, hits


EMOTION_RANK = {EMOTION_NEUTRAL: 0, EMOTION_ANXIOUS: 1, EMOTION_UPSET: 2, EMOTION_ANGRY: 3}


def merge_emotion(rule: tuple[str, int, list[str]],
                  model: dict[str, Any] | None) -> tuple[str, int, list[str]]:
    """规则 + 模型取"更严重"的那个。

    为什么取更严重：宁可多转一次人工，也不要让一个已经很生气的客户继续跟机器人绕。
    误转人工的代价是坐席多接一单，漏转的代价是客户直接流失。
    """
    emotion, score, words = rule
    if not model:
        return emotion, score, words
    model_emotion = str(model.get("emotion") or "").strip()
    if model_emotion not in EMOTION_RANK:
        return emotion, score, words
    try:
        model_score = int(float(model.get("emotion_score") or 0))
    except (TypeError, ValueError):
        model_score = 0
    if EMOTION_RANK[model_emotion] > EMOTION_RANK[emotion]:
        return model_emotion, max(model_score, score), words
    return emotion, max(model_score, score), words


# --------------------------------------------------------------------------- 槽位


def extract_slots(texts: list[str], known: dict[str, Any] | None = None) -> dict[str, str]:
    """从（多轮）对话里抽槽位。

    多轮的意义就在这里：客户第一句说"我要退货"，第二句才报订单号——
    第二句进来时要把订单号记住，而不是每次都从零开始问。
    """
    slots: dict[str, str] = {k: v for k, v in (known or {}).items() if v}
    for text in texts:
        content = text or ""
        if not slots.get("order_no"):
            found = ORDER_NO_RE.search(content)
            if found:
                slots["order_no"] = found.group(1)
            else:
                bare = BARE_DIGITS_RE.search(content)
                if bare:
                    slots["order_no"] = bare.group(1)
        if not slots.get("phone"):
            phone = PHONE_RE.search(content)
            if phone:
                slots["phone"] = phone.group(1)
        if not slots.get("email"):
            email = EMAIL_RE.search(content)
            if email:
                slots["email"] = email.group(0)
    return slots


def merge_slots(slots: dict[str, str], model_slots: Any) -> dict[str, str]:
    """把模型抽到的槽位并进来：规则没抽到、模型抽到了就用模型的。

    只补空缺、不覆盖规则结果——规则是从原文正则匹配出来的，比模型复述更可靠。
    """
    if not isinstance(model_slots, dict):
        return slots
    for key, value in model_slots.items():
        text = str(value or "").strip()
        if text and not slots.get(key):
            slots[key] = text
    return slots


# --------------------------------------------------------------------------- 转人工


def hit_transfer_keyword(question: str, keywords: list[str]) -> str | None:
    text = (question or "").strip()
    for word in keywords:
        if word and word in text:
            return word
    return None


def decide_escalation(
        *,
        question: str,
        setting: dict[str, Any],
        intents: list[dict[str, Any]],
        intent: dict[str, Any] | None,
        emotion: str,
        model: dict[str, Any] | None,
        unresolved_rounds: int,
        enough: bool,
        intent_from_rule: bool = False,
) -> tuple[bool, str, str]:
    """转人工决策：按优先级给出一条明确的原因。

    @return (是否转人工, 原因, 触发来源)

    顺序是有讲究的——"客户明确要人工"永远排第一，
    其次是"命中直接转人工的意图"，再是情绪，最后才是"机器人答不上来"。
    """
    keyword = hit_transfer_keyword(question, transfer_keywords(setting))
    if keyword:
        return True, f"客户明确要求转人工（命中关键词「{keyword}」）", "keyword"
    if intent is not None and bool(intent.get("escalate")):
        # 意图标了"命中即转人工"，但也得看这个意图是怎么判出来的：
        #   · 规则命中（客户的话和意图语料真的对上了）→ 算硬依据，直接转；
        #   · 只有模型判出来的 → 要求话面里还有"人工/客服/投诉…"这类字样，
        #     否则先按普通咨询处理。模型偶尔会把"你是谁""你能做什么"判成人工客服意图，
        #     真转了客户会觉得莫名其妙（这正是"我没说要转人工，怎么就转了"的来源）。
        signal = next((word for word in HUMAN_SIGNAL_WORDS if word in question), None)
        if intent_from_rule or signal:
            return True, f"命中需转人工的意图「{intent.get('name')}」", "intent"
        logger.info("意图「%s」标记了直接转人工，但这次只由模型判出、话面也没有人工/客服字样，按普通咨询处理",
                    intent.get("name"))
    if model and bool(model.get("need_human")):
        # 模型说"该转人工"只是一条建议：必须有能核对的话面依据（客户确实提到人工/客服/投诉…），
        # 否则一律按普通咨询处理。不然问一句"你是谁？"都可能被判成要人工——
        # 模型偶尔会把"想知道对面是谁"当成"想找人"。
        signal = next((word for word in HUMAN_SIGNAL_WORDS if word in question), None)
        reason = str(model.get("reason") or "").strip() or "模型判断需要人工介入"
        if signal:
            return True, f"模型判断需要人工介入：{reason}", "model"
        logger.info("模型建议转人工，但问题里没有可核对的依据（未提及人工/客服/投诉等），按普通咨询处理 "
                    "tenant_question=%s reason=%s", question[:30], reason)
    if bool(setting.get("transfer_on_anger", True)) and EMOTION_RANK.get(emotion, 0) >= EMOTION_RANK[EMOTION_ANGRY]:
        return True, f"客户情绪{emotion}，自动转人工避免激化", "emotion"
    limit = int(setting.get("transfer_after_unresolved") or 0)
    if limit > 0 and unresolved_rounds >= limit:
        return True, f"连续 {unresolved_rounds} 轮未能解决，自动转人工", "unresolved"
    # 知识库查不到 + 已经问过一轮 → 提前转（不等满 limit 轮）。
    # 注意必须跟着 limit 一起关：租户把"连续未解决轮次"设成 0 就是"别因为答不上来转人工"，
    # 这里要是还转，那个设置就等于没生效。
    if limit > 0 and not enough and unresolved_rounds >= 1:
        return True, "知识库中查不到依据，且已经重复咨询，建议人工介入", "unresolved"
    return False, "", ""


# --------------------------------------------------------------------------- 多轮状态


def load_dialogue(tenant_code: str, session_no: str) -> dict[str, Any]:
    """读一个会话的机器人对话状态；没有就返回初始状态。"""
    initial = {
        "turn_count": 0, "unresolved_rounds": 0, "last_intent": None, "last_intent_code": None,
        "last_confidence": None, "last_emotion": None, "emotion_score": None,
        "slots": {}, "transferred": False, "transfer_reason": None,
    }
    if not tenant_code or not session_no:
        return initial
    try:
        with connect() as conn:
            row = conn.execute(
                """
                SELECT turn_count, unresolved_rounds, last_intent, last_intent_code, last_confidence,
                       last_emotion, emotion_score, slots, transferred, transfer_reason
                  FROM bot_dialogue
                 WHERE tenant_code = %s AND session_no = %s AND is_deleted = FALSE
                 LIMIT 1
                """,
                (tenant_code, session_no),
            ).fetchone()
    except Exception as exc:  # noqa: BLE001
        logger.warning("读取多轮对话状态失败 tenant=%s session=%s error=%s",
                       tenant_code, session_no, exc)
        return initial
    if not row:
        return initial
    result = dict(initial)
    result.update({k: v for k, v in row.items() if k != "slots"})
    result["slots"] = parse_json_object(str(row.get("slots") or "")) or {}
    return result


def save_dialogue(tenant_code: str, session_no: str, state: dict[str, Any]) -> None:
    """把这一轮的结果写回状态（一个会话一条，靠唯一索引做 upsert）。"""
    if not tenant_code or not session_no:
        return
    slots = json.dumps(state.get("slots") or {}, ensure_ascii=False)
    last_message = str(state.get("last_message") or "")[:500]
    try:
        with connect() as conn, conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO bot_dialogue
                      (id, tenant_code, session_no, turn_count, unresolved_rounds,
                       last_intent, last_intent_code, last_confidence, last_emotion, emotion_score,
                       slots, transferred, transfer_reason, last_message, update_time)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, CURRENT_TIMESTAMP)
                ON CONFLICT (tenant_code, session_no) WHERE is_deleted = FALSE
                DO UPDATE SET turn_count = EXCLUDED.turn_count,
                              unresolved_rounds = EXCLUDED.unresolved_rounds,
                              last_intent = EXCLUDED.last_intent,
                              last_intent_code = EXCLUDED.last_intent_code,
                              last_confidence = EXCLUDED.last_confidence,
                              last_emotion = EXCLUDED.last_emotion,
                              emotion_score = EXCLUDED.emotion_score,
                              slots = EXCLUDED.slots,
                              transferred = EXCLUDED.transferred,
                              transfer_reason = EXCLUDED.transfer_reason,
                              last_message = EXCLUDED.last_message,
                              update_time = CURRENT_TIMESTAMP
                """,
                (
                    next_id(), tenant_code, session_no,
                    int(state.get("turn_count") or 0),
                    int(state.get("unresolved_rounds") or 0),
                    state.get("last_intent"), state.get("last_intent_code"),
                    state.get("last_confidence"), state.get("last_emotion"),
                    state.get("emotion_score"), slots,
                    bool(state.get("transferred")), state.get("transfer_reason"), last_message,
                ),
            )
            conn.commit()
    except Exception as exc:  # noqa: BLE001
        # 状态写不进去不能影响这次回复：最坏也就是"多轮记忆"退化成单轮
        logger.warning("保存多轮对话状态失败 tenant=%s session=%s error=%s",
                       tenant_code, session_no, exc)


def list_dialogues(tenant_code: str, limit: int = 50) -> list[dict[str, Any]]:
    """机器人接待记录（页面展示"机器人接待了什么、判成了什么、为什么转人工"）。"""
    if not tenant_code:
        return []
    try:
        with connect() as conn:
            rows = conn.execute(
                """
                SELECT session_no, turn_count, unresolved_rounds, last_intent, last_confidence,
                       last_emotion, emotion_score, slots, transferred, transfer_reason,
                       last_message, update_time
                  FROM bot_dialogue
                 WHERE tenant_code = %s AND is_deleted = FALSE
                 ORDER BY update_time DESC
                 LIMIT %s
                """,
                (tenant_code, max(1, min(limit, 200))),
            ).fetchall()
    except Exception as exc:  # noqa: BLE001
        logger.warning("读取机器人接待记录失败 tenant=%s error=%s", tenant_code, exc)
        return []
    result = []
    for row in rows:
        item = dict(row)
        item["slots"] = parse_json_object(str(row.get("slots") or "")) or {}
        if item.get("update_time") is not None:
            item["update_time"] = item["update_time"].strftime("%Y-%m-%d %H:%M:%S")
        if item.get("last_confidence") is not None:
            item["last_confidence"] = float(item["last_confidence"])
        if item.get("emotion_score") is not None:
            item["emotion_score"] = float(item["emotion_score"])
        result.append(item)
    return result
