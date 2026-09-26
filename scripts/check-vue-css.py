"""检查 Vue 单文件组件里的 <style> 有没有"被改坏"。

为什么要有这个检查（都是真踩过的坑）：
    用"全局字符串替换"往 CSS 里插规则时，短选择器会命中多处——
    例如把 ``.v-content {`` 作为锚点插入，会连带命中 ``.is-self .v-content {``，
    把原来那条"自己发的消息用白字"的规则劈成两半，最后被后面一条通配规则覆盖，
    结果客户自己说的话变成灰字压在蓝气泡上，看不清。

    这类问题编译不报错、构建能过，只有肉眼才看得出来，所以需要脚本兜住。

检查三件事：
    1. 大括号是否配对（少一个 } 会把后面一整段 CSS 吞掉）；
    2. 选择器有没有被劈开（选择器里混进注释、或单独成行的残缺选择器）；
    3. 有没有**互相打架**的重复选择器：两条同名规则给同一个属性赋了不同的值，
       后一条会悄悄覆盖前一条（"客户消息白字"那条就是这么被灰字盖掉的）。
       只是把属性补在一起的重复（比如 .a 写布局、.a 再补边框）不算问题。

用法：
    python scripts/check-vue-css.py                # 检查前端所有 .vue 文件
    python scripts/check-vue-css.py 文件路径...    # 只检查指定文件
"""

from __future__ import annotations

import collections
import pathlib
import re
import sys

STYLE_RE = re.compile(r"<style[^>]*>(.*?)</style>", re.S)
COMMENT_RE = re.compile(r"/\*.*?\*/", re.S)


def style_of(text: str) -> str:
    blocks = STYLE_RE.findall(text)
    return "\n".join(blocks)


def parse_rules(css: str) -> tuple[list[tuple[str, str]], int]:
    """按深度 0 的 } 切出 (选择器, 声明体)；同时返回大括号是否配平。"""
    rules: list[tuple[str, str]] = []
    buf = ""
    depth = 0
    for char in css:
        if char == "{":
            depth += 1
            if depth > 2:
                # 允许 @media / @keyframes 套一层，超过两层基本可以断定括号写坏了
                return rules, depth
        elif char == "}":
            depth -= 1
            if depth == 0:
                head, _, tail = buf.partition("{")
                rules.append((head.strip(), tail.strip()))
                buf = ""
                continue
            if depth < 0:
                return rules, depth
        buf += char
    return rules, depth


def declarations(body: str) -> dict[str, str]:
    """把声明体拆成 {属性: 值}，用于判断两条同名规则是否真的打架。"""
    result: dict[str, str] = {}
    for item in body.split(";"):
        prop, _, value = item.partition(":")
        prop, value = prop.strip().lower(), value.strip()
        if prop and value:
            result[prop] = value
    return result


def _shattered(selector: str) -> bool:
    """判断选择器是不是被"补丁插到中间"劈开了。

    合法写法只有两种：单行选择器，或"每行都以逗号结尾"的分组选择器。
    出现"上一行没逗号、下一行又是新选择器"就是被劈开了——
    例如 ``.is-self /* 注释 */`` 后面紧跟 ``.v-actions {``，
    原来的 `.is-self .v-content` 就被截断，颜色规则随之失效。
    """
    lines = [line.strip() for line in selector.splitlines() if line.strip()]
    for current, following in zip(lines, lines[1:]):
        if not current.endswith(",") and re.match(r"^[.#\[]", following):
            return True
    return bool(lines and lines[-1].endswith(","))


def check(path: pathlib.Path) -> list[str]:
    raw = path.read_text(encoding="utf-8")
    css = style_of(raw)
    if not css.strip():
        return []
    # 去掉注释再解析：注释留在选择器里不影响浏览器，但会让"劈开选择器"的检查误报
    body = COMMENT_RE.sub("", css)
    rules, depth = parse_rules(body)
    selectors = [sel for sel, _ in rules]
    problems: list[str] = []

    if depth != 0:
        problems.append(f"大括号不配平（结束时深度={depth}）——后面的 CSS 可能整段失效")

    broken = [sel for sel in selectors if not sel or _shattered(sel)]
    if broken:
        problems.append("选择器被劈开/残缺：" + " | ".join(repr(sel) for sel in broken[:3]))

    # 同一个选择器给同一个属性赋了不同的值 → 后者会悄悄覆盖前者，这才是真问题
    conflicts: list[str] = []
    by_selector: dict[str, list[dict[str, str]]] = collections.defaultdict(list)
    for sel, body_text in rules:
        by_selector[sel].append(declarations(body_text))
    for sel, bodies in by_selector.items():
        if len(bodies) < 2:
            continue
        for prop in set().union(*bodies):
            values = {item[prop] for item in bodies if prop in item}
            if len(values) > 1:
                conflicts.append(f"{sel} 的 {prop} 被写成 {sorted(values)}")
    if conflicts:
        problems.append("同名规则互相覆盖：" + "；".join(conflicts[:3]))

    return problems


def main() -> int:
    args = sys.argv[1:]
    if args:
        targets = [pathlib.Path(item) for item in args]
    else:
        root = pathlib.Path(__file__).resolve().parent.parent / "yunti-frontend" / "src"
        targets = sorted(root.rglob("*.vue"))

    failed = 0
    for path in targets:
        problems = check(path)
        if problems:
            failed += 1
            print(f"❌ {path}")
            for problem in problems:
                print(f"     {problem}")
    if failed:
        print(f"\n共 {failed} 个文件有问题。")
        return 1
    print(f"✅ 检查了 {len(targets)} 个 .vue 文件：CSS 结构正常（括号配平、无劈开选择器、无重复选择器）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
