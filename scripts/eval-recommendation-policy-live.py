#!/usr/bin/env python3
"""真实推荐回答策略验收。仅创建并清理自己的临时会话，不新增课程、学习或订单数据。
环境：KZAI_EVAL_USERNAME/PASSWORD；可选 KZAI_EVAL_BASE_URL、KZAI_POLICY_EVAL_OUTPUT。
真实模型调用会产生正常 API 用量；结果与异常均原样报告，不保证课程目录中一定有对应课程。
"""
import json
import os
import time
import urllib.request
from pathlib import Path

BASE = os.environ.get('KZAI_EVAL_BASE_URL', 'http://127.0.0.1:18080')
OUTPUT = Path(os.environ.get('KZAI_POLICY_EVAL_OUTPUT', '/tmp/kzai-recommendation-policy-live.json'))

def api(path, token, body=None, method=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(BASE + path, headers=headers,
        data=None if body is None else json.dumps(body).encode(), method=method)
    with urllib.request.urlopen(request, timeout=55) as response:
        result = json.load(response)
    assert result['code'] == 200, result
    return result['data']

def main():
    token = api('/auth/login', None, {'username': os.environ['KZAI_EVAL_USERNAME'],
        'password': os.environ['KZAI_EVAL_PASSWORD']})['token']
    sessions = []
    report = {'scope': 'temporary isolated sessions, current catalog and real configured provider', 'cases': [], 'cleanup': []}
    try:
        for _ in range(2):
            sessions.append(api('/session', token, {}, 'POST')['sessionId'])
        for name, session, message in [
            ('python-first', sessions[0], '我要学 Python'),
            ('explicit-conditions', sessions[0], '我想学 Java 微服务，预算500元，每周5小时，4周学完'),
            ('separate-session', sessions[1], '我要学 Python'),
            ('budget-change', sessions[0], '预算改成1元'),
            ('clear-conditions', sessions[0], '取消预算，时间不限')]:
            started = time.monotonic()
            request = urllib.request.Request(BASE + '/chat',
                headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token},
                data=json.dumps({'sessionId': session, 'message': message}).encode())
            with urllib.request.urlopen(request, timeout=55) as response:
                raw = response.read().decode()
            frames = [json.loads(line[5:]) for line in raw.splitlines() if line.startswith('data:')]
            reply = ''.join(e['data'].get('content', '') for e in frames if e['code'] == 1001)
            terminals = [e['data'] for e in frames if e['code'] == 1002]
            preferences = api('/chat/recommendations/' + session, token)
            entry = {'case': name, 'input': message, 'reply': reply, 'terminal': terminals,
                'preferences': preferences, 'seconds': round(time.monotonic() - started, 3)}
            report['cases'].append(entry)
            assert len(terminals) == 1 and terminals[0]['code'] == 'OK' and terminals[0]['agent'] == 'RECOMMEND'
            assert reply and not any(s in reply for s in ('现在调用', '默认零基础', '30000 分', '未直接使用'))
            if name in ('python-first', 'separate-session'):
                assert all(preferences.get(k) is None for k in ('foundation', 'budgetCents', 'hoursPerWeek', 'weeks'))
            elif name == 'explicit-conditions':
                assert preferences['budgetCents'] == 50000 and preferences['hoursPerWeek'] == 5 and preferences['weeks'] == 4
                assert preferences['foundation'] is None
            elif name == 'budget-change':
                assert preferences['budgetCents'] == 100 and preferences['hoursPerWeek'] == 5 and preferences['weeks'] == 4
            else:
                assert all(preferences.get(k) is None for k in ('budgetCents', 'hoursPerWeek', 'weeks'))
            entry['policyAssertionsPassed'] = True
            print(json.dumps(entry, ensure_ascii=False), flush=True)
    except Exception as error:
        report['executionError'] = type(error).__name__ + ': ' + str(error)
        raise
    finally:
        for session in sessions:
            try:
                deleted = api('/session/history', token, {'sessionId': session}, 'DELETE')['deleted']
                report['cleanup'].append({'sessionId': session, 'deleted': deleted})
            except Exception as error:
                report['cleanup'].append({'sessionId': session, 'deleted': False, 'error': str(error)})
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        OUTPUT.write_text(json.dumps(report, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    main()
