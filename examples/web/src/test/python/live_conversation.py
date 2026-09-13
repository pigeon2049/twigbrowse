"""Run the real HTTP/SSE + model + browser flow against a running demo.

Usage: python3 src/test/python/live_conversation.py [http://localhost:8080]
The server already owns its API key; this script needs no credentials.
"""
import http.cookiejar
import json
import pathlib
import sys
import time
import urllib.request

base = sys.argv[1] if len(sys.argv) > 1 else 'http://localhost:8080'
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
output = pathlib.Path('target/live-conversation')
output.mkdir(parents=True, exist_ok=True)
questions = [
    '帮我总结今天 Hacker News 上都在说什么？',
    'AI Agent 的“撒谎、作弊、合谋”与责任归属，详情说了什么，评论区在讨论什么？',
    '评论区里支持和反对的分别怎么说？',
]
for index, question in enumerate(questions, 1):
    request = urllib.request.Request(base + '/api/chat/stream', data=json.dumps({'message': question}).encode(),
        headers={'Content-Type': 'application/json', 'Accept': 'text/event-stream'})
    started = time.monotonic()
    events, fields = [], []
    with client.open(request, timeout=210) as response:
        for line in response:
            line = line.decode().rstrip('\r\n')
            if line:
                fields.append(line)
                continue
            event = next((x[6:].strip() for x in fields if x.startswith('event:')), 'message')
            data = '\n'.join(x[5:].removeprefix(' ') for x in fields if x.startswith('data:'))
            if data:
                events.append({'event': event, 'data': json.loads(data), 'elapsed': round(time.monotonic() - started, 2)})
            fields = []
    (output / f'turn-{index}.json').write_text(json.dumps({'question': question, 'events': events}, ensure_ascii=False, indent=2))
    errors = [e['data'] for e in events if e['event'] == 'error']
    assert not errors, errors
    assert any(e['event'] == 'done' for e in events), 'Missing done event'
    answer = ''.join(e['data'] for e in events if e['event'] == 'delta')
    assert answer.strip(), 'Empty answer'
    print(f'turn={index} completed seconds={time.monotonic()-started:.1f} chars={len(answer)}', flush=True)
