#!/usr/bin/env python3
"""M14 E2E 断言：TTS 请求日志（status=200 且 content-type 为 audio/*）。
输入兼容标准 JSON 与去引号壳格式。"""
import json
import re
import sys

raw = sys.argv[1] if len(sys.argv) > 1 else ''
try:
    log = json.loads(raw)
except Exception:
    try:
        log = json.loads(raw.replace('\\', '"'))
    except Exception:
        log = None
if log is not None:
    ok = len(log) >= 1 and log[0].get('status') == 200 and 'audio' in log[0].get('ct', '')
else:
    # 终极兜底：正则直接匹配 status 200 与 audio content-type（容忍反斜杠壳）
    compact = raw.replace('\\', '').replace(' ', '')
    ok = re.search(r'status:200', compact) and re.search(r'audio/wav', compact)
print(1 if ok else 0)
