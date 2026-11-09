#!/usr/bin/env python3
"""
[T-session-list-stub-preview] Python mirror of the Kotlin contract in
PreviewText.sessionListPreview / DisplayParts.isStub — the session list's
`last_message` must never show the offload placeholder.

Mirrors:
  src/android/app/src/main/java/com/openminis/app/data/repository/PreviewText.kt
  src/android/app/src/main/java/com/openminis/app/data/display/DisplayParts.kt
  src/android/app/src/main/java/com/openminis/app/data/repository/InterruptedTail.kt
  src/android/app/src/test/java/com/openminis/app/data/repository/SessionListPreviewTest.kt

Run: python3 scripts/session_preview_verify.py   (EXIT=0 => all green)
"""
import json
import re
import sys

STUB_PREFIX = "body stored ("
OMITTED_PREFIX = "body omitted"
PREVIEW_SENTINEL = "\n\u241e"
PREVIEW_CLEAN_CHARS = 400


def note(text):
    """DisplayParts.note — the stub shape storeBody writes for oversized bodies."""
    return json.dumps([{"type": "text", "value": text}], ensure_ascii=False)


def is_stub(json_str):
    """DisplayParts.isStub — single text part starting with a stub prefix."""
    if json_str is None or not json_str.strip():
        return False
    try:
        parts = json.loads(json_str)
    except Exception:
        return False
    if not isinstance(parts, list) or len(parts) != 1:
        return False
    part = parts[0]
    if not isinstance(part, dict) or part.get("type") != "text":
        return False
    value = part.get("value", "")
    return value.startswith(STUB_PREFIX) or value.startswith(OMITTED_PREFIX)


def clean_preview(raw):
    """PreviewText.cleanPreview — the subset of transforms the cases hit."""
    src = raw if len(raw) <= PREVIEW_CLEAN_CHARS else raw[:PREVIEW_CLEAN_CHARS]
    src = re.sub(r"[\r\n]+", " ", src)
    src = re.sub(r"#{1,6}\s", "", src)
    src = re.sub(r"\*{1,3}(?=\S)(.*?\S)\*{1,3}", r"\1", src)
    src = re.sub(r"_{2,3}(?=\S)(.*?\S)_{2,3}", r"\1", src)
    src = src.replace("~~", "")
    src = re.sub(r"`{1,3}", "", src)
    src = re.sub(r"^\s*[-*+]\s", "", src, flags=re.MULTILINE)
    src = re.sub(r"^\s*\d+\.\s", "", src, flags=re.MULTILINE)
    src = re.sub(r"^\s*>\s?", "", src, flags=re.MULTILINE)
    src = re.sub(r"\[([^]]+)]\([^)]+\)", r"\1", src)
    src = re.sub(r"!\[([^]]*)]\([^)]+\)", r"\1", src)
    src = re.sub(r"\s{2,}", " ", src)
    return src.strip()[:100]


def extract_text_preview(parts_json):
    """PreviewText.extractTextPreview — text / mediaRef / toolUse only."""
    if parts_json is None or not parts_json.strip():
        return None
    try:
        array = json.loads(parts_json)
    except Exception:
        return clean_preview(parts_json)
    if not isinstance(array, list):
        return None
    has_media = False
    last_tool_use = None
    for obj in array:
        if not isinstance(obj, dict):
            continue
        t = obj.get("type")
        if t == "text":
            text = obj.get("value", "")
            if text and text.strip():
                cleaned = clean_preview(text)
                if cleaned.strip():
                    return cleaned
        elif t == "mediaRef":
            has_media = True
        elif t == "toolUse":
            v = obj.get("value")
            if isinstance(v, dict):
                last_tool_use = v
    if has_media:
        return "[Image]"
    if last_tool_use is not None:
        desc = (last_tool_use.get("description") or "").strip()
        return (desc if desc else "\U0001f527 " + last_tool_use.get("name", "tool"))[:100]
    return None


def message_preview_cell(parts_json):
    """InterruptedTail.messagePreviewCell — raw head + sentinel + human."""
    raw = parts_json[:8192]
    human = extract_text_preview(parts_json)
    if human is None:
        return raw
    return raw + PREVIEW_SENTINEL + human


def session_list_preview(inline_parts_json, preview_cell):
    """PreviewText.sessionListPreview — the function under test."""
    if inline_parts_json is None or not inline_parts_json.strip():
        return None
    if not is_stub(inline_parts_json):
        return extract_text_preview(inline_parts_json)
    if preview_cell is None or not preview_cell.strip():
        return None
    head, _sep, tail = preview_cell.partition(PREVIEW_SENTINEL)
    tail = tail.strip()
    human = tail if tail else extract_text_preview(head)
    if human is None or not human.strip():
        return None
    return human


CASES = []


def case(name):
    def deco(fn):
        CASES.append((name, fn))
        return fn
    return deco


@case("an offloaded stub row rebuilds the preview from the preview cell")
def _t1():
    inline = note("body stored (2232 bytes)")
    cell = message_preview_cell(
        json.dumps([{"type": "text", "value": "真正的消息正文"}], ensure_ascii=False))
    got = session_list_preview(inline, cell)
    assert got == "真正的消息正文", repr(got)


@case("the stub text itself is never treated as content")
def _t2():
    inline = note("body stored (2232 bytes)")
    assert extract_text_preview(inline) == "body stored (2232 bytes)"
    assert session_list_preview(inline, None) is None


@case("an omitted stub row rebuilds the same way")
def _t3():
    inline = note("body omitted; store failed")
    cell = message_preview_cell(
        json.dumps([{"type": "text", "value": "正文还在"}], ensure_ascii=False))
    got = session_list_preview(inline, cell)
    assert got == "正文还在", repr(got)


@case("a stub row whose preview cell has no sentinel falls back to the raw head")
def _t4():
    inline = note("body stored (13 bytes)")
    raw_head = json.dumps([{"type": "toolResult", "value": {"output": "ok"}}])
    assert session_list_preview(inline, raw_head) is None


@case("a stub row with a null or blank preview cell yields null")
def _t5():
    s = note("body stored (5 bytes)")
    assert session_list_preview(s, None) is None
    assert session_list_preview(s, "") is None
    assert session_list_preview(s, "   ") is None


@case("a normal text row keeps the extractTextPreview behaviour")
def _t6():
    inline = json.dumps([{"type": "text", "value": "普通消息"}], ensure_ascii=False)
    assert session_list_preview(inline, None) == "普通消息"


@case("a tool-use row still yields its summary")
def _t7():
    inline = json.dumps(
        [{"type": "toolUse", "value": {"name": "shell_execute", "description": "Install Git"}}])
    assert session_list_preview(inline, None) == extract_text_preview(inline)


@case("a tool-result row still yields null (flicker guard intact)")
def _t8():
    inline = json.dumps([{"type": "toolResult", "value": {"output": "ok"}}])
    assert session_list_preview(inline, None) is None


@case("real text starting with the stub prefix still shows its text")
def _t9():
    inline = json.dumps(
        [{"type": "text", "value": "body stored ( 是什么意思"}], ensure_ascii=False)
    cell = message_preview_cell(inline)
    got = session_list_preview(inline, cell)
    assert got == "body stored ( 是什么意思", repr(got)


@case("null and blank inline yield null")
def _t10():
    assert session_list_preview(None, None) is None
    assert session_list_preview("", None) is None
    x = json.dumps([{"type": "text", "value": "x"}])
    assert session_list_preview("   ", x) is None


def main():
    passed = failed = 0
    for name, fn in CASES:
        try:
            fn()
            print("[PASS] %s" % name)
            passed += 1
        except AssertionError as e:
            print("[FAIL] %s: %s" % (name, e))
            failed += 1
        except Exception as e:
            print("[FAIL] %s: %s: %s" % (name, type(e).__name__, e))
            failed += 1
    print("%d/%d green" % (passed, passed + failed))
    sys.exit(0 if failed == 0 else 1)


if __name__ == "__main__":
    main()
