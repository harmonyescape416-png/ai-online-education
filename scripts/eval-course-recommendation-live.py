#!/usr/bin/env python3
"""只读现有课程，在独立临时会话验收推荐 API；不创建订单/支付，不改课程状态。
凭据用 KZAI_EVAL_USERNAME/PASSWORD 和 KZAI_EVAL_OTHER_USERNAME/PASSWORD 注入。
删除临时会话会同时清理偏好，报告保留 sessionId 便于核对测试数据生命周期。
"""
import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = os.environ.get('KZAI_EVAL_BASE_URL', 'http://127.0.0.1:18080')

def api(path, token=None, body=None, method=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(BASE + path, headers=headers,
        data=None if body is None else json.dumps(body).encode(), method=method)
    try:
        with urllib.request.urlopen(request, timeout=28) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)

def login(prefix):
    status, result = api('/auth/login', body={
        'username': os.environ[prefix + 'USERNAME'], 'password': os.environ[prefix + 'PASSWORD']})
    assert status == 200 and result['code'] == 200, 'test account login failed'
    return result['data']['token']

token = login('KZAI_EVAL_')
other = login('KZAI_EVAL_OTHER_')
status, created = api('/session', token, {}, 'POST')
assert status == 200 and created['code'] == 200
session = created['data']['sessionId']
report = {'scope': 'live model + ES + current course/learning/trade services; temporary session; no orders',
          'sessionId': session, 'cases': []}
try:
    for name, preferences in [
        ('advanced', {'goal': '我会 Spring Boot，想学习微服务的注册中心、配置中心和网关', 'keyword': 'Spring Cloud', 'budgetCents': 50000}),
        ('no_match', {'goal': '我想学习火星土豆种植和农业育种', 'keyword': '', 'clearBudget': True}),
        ('budget_zero', {'goal': '想学习 Java 微服务', 'keyword': '微服务', 'budgetCents': 0}),
        ('vague', {'goal': '推荐课程', 'keyword': '', 'clearBudget': True})]:
        started = time.monotonic()
        status, response = api('/chat/recommendations', token, {'sessionId': session, 'preferences': preferences})
        assert status == 200 and response['code'] == 200
        result = response['data']
        cards = result['courses']
        for card in cards:
            detail_status, detail = api('/courses/' + card['courseId'], token)
            assert detail_status == 200 and detail['code'] == 200
            assert card['name'] == detail['data']['name'] and card['price'] == detail['data']['price']
            assert card['purchasable'] is True and not card['owned']
        if name == 'advanced':
            assert cards, 'live positive case must recall actual courses'
            assert all(c['price'] <= 50000 for c in cards)
        if name == 'no_match':
            assert not cards and result['status'] == 'NOT_FOUND'
        if name == 'budget_zero':
            assert all(c['price'] == 0 for c in cards)
        if name == 'vague':
            assert not cards and result['status'] == 'NEEDS_CLARIFICATION'
        report['cases'].append({'case': name, 'status': result['status'], 'mode': result['retrievalMode'],
            'seconds': round(time.monotonic() - started, 3),
            'courses': [{k: c.get(k) for k in ('courseId', 'name', 'price', 'validDuration', 'owned')} for c in cards], 'passed': True})
    status, _ = api('/chat/recommendations/' + session, other)
    assert status == 403
    report['foreignSessionDenied'] = True
    status, invalid = api('/chat/recommendations', token, {'sessionId': session, 'preferences': {'budgetCents': -1}})
    # 本项目统一异常处理返回 HTTP 200 + R.code=400，前端按 R.code 拒绝。
    assert status == 200 and invalid['code'] == 400
    report['invalidBudgetDenied'] = True
    report['invalidBudgetResponse'] = {'httpStatus': status, 'code': invalid['code']}
    if os.environ.get('KZAI_EVAL_CHAT') == 'true':
        started = time.monotonic()
        request = urllib.request.Request(BASE + '/chat', method='POST',
            headers={'Content-Type': 'application/json', 'Accept': 'text/event-stream', 'Authorization': 'Bearer ' + token},
            data=json.dumps({'sessionId': session, 'message': '请推荐能学习服务注册发现、网关和微服务治理的真实课程，我会 Java 和 Spring Boot，预算 500 元。'}).encode())
        with urllib.request.urlopen(request, timeout=55) as response:
            stream = response.read().decode()
        frames=[]
        for block in stream.replace('\r\n','\n').split('\n\n'):
            event=next((line[6:].strip() for line in block.splitlines() if line.startswith('event:')),None)
            data='\n'.join(line[5:].strip() for line in block.splitlines() if line.startswith('data:'))
            if data:
                envelope=json.loads(data)
                frames.append((event,envelope.get('data',envelope)))
        cards=[body['payload'] for event,body in frames if event=='1003' and body.get('type')=='COURSE_CARD']
        terminals=[body for event,body in frames if event=='1002']
        report['chat']={'terminal':terminals,'cards':[{k:c.get(k) for k in ('courseId','price','retrievalMode','recommendationEvidence')} for c in cards]}
        assert len(terminals)==1 and terminals[0].get('agent')=='RECOMMEND', 'recommendation routing/termination failed'
        assert terminals[0].get('code')=='OK', 'upstream chat execution failed; see chat.terminal'
        assert cards and all(c.get('recommendationEvidence') and c['price'] <= 50000 for c in cards), 'real recommendation tool did not produce cards'
        report['chat']={'agent':terminals[0].get('agent'),'seconds':round(time.monotonic()-started,3),
            'courseIds':[c['courseId'] for c in cards], 'passed':True}
finally:
    status, result = api('/session/history', token, {'sessionId': session}, 'DELETE')
    report['temporarySessionDeleted'] = status == 200 and result.get('code') == 200
    output = Path(os.environ.get('KZAI_EVAL_OUTPUT', '/tmp/kzai-course-recommend-live.json'))
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps(report, ensure_ascii=False))
