#!/usr/bin/env python3
"""HTTP + real Kafka dev profile demonstration. Run with DEV_PASSWORD in the environment."""
import base64
import http.cookiejar
import json
import os
import time
import urllib.request
import urllib.parse
import uuid

BASE = os.environ.get('API_URL', 'http://localhost:8080')
PRODUCT = '1'
COMPONENT = '1'
PART = '1'
PNG = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aWQ0AAAAASUVORK5CYII=')

class Client:
    def __init__(self, username):
        self.http = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.token = self.call('GET', '/api/auth/csrf')['token']
        self.call('POST', '/api/auth/login', urllib.parse.urlencode({'username': username, 'password': os.environ['DEV_PASSWORD']}).encode(), 'application/x-www-form-urlencoded')
        self.token = self.call('GET', '/api/auth/csrf')['token']
    def call(self, method, path, data=None, content_type='application/json'):
        headers = {'Content-Type': content_type}
        if method != 'GET': headers['X-CSRF-TOKEN'] = self.token
        if data is not None and not isinstance(data, bytes): data = json.dumps(data, ensure_ascii=False).encode()
        req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
        with self.http.open(req, timeout=15) as response:
            return json.load(response)
    def multipart(self, path, fields, files):
        boundary = 'repair-' + uuid.uuid4().hex
        chunks = []
        for name, (value, content_type) in fields.items():
            chunks += [f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\nContent-Type: {content_type}\r\n\r\n'.encode(), value.encode(), b'\r\n']
        for name in files:
            chunks += [f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"; filename="wheel.png"\r\nContent-Type: image/png\r\n\r\n'.encode(), PNG, b'\r\n']
        chunks += [f'--{boundary}--\r\n'.encode()]
        return self.call('POST', path, b''.join(chunks), 'multipart/form-data; boundary=' + boundary)

def wait_for(read, predicate, label):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        result = read()
        if predicate(result): return result
        time.sleep(.5)
    raise AssertionError('Timed out: ' + label)

def intake(client, scenario):
    data = {'productId': PRODUCT, 'symptoms': '바퀴가 움직이지 않음 — 기사 원문', 'errorCodes': ['E01', 'E02'], 'firmware': '1.2.3', 'userInput': {'scenario': scenario, 'customerDescription': '원본 보존'}}
    return client.multipart('/api/repairs', {'data': (json.dumps(data, ensure_ascii=False), 'application/json')}, ['files'])

def diagnose(client, scenario):
    created = intake(client, scenario)
    repair, task = created['repairId'], created['taskId']
    rounds = 0
    while True:
        state = wait_for(lambda: client.call('GET', f'/api/repairs/{repair}/diagnosis'), lambda t: t['status'] in ['WAITING_INPUT', 'SUCCEEDED', 'FAILED'], scenario)
        assert state['id'] == task
        if state['status'] != 'WAITING_INPUT': break
        rounds += 1
        detail = client.call('GET', f'/api/repairs/{repair}')
        request = next(r for r in detail['photoRequests'] if r['submitted_at'] is None)
        uploaded = client.multipart(f'/api/repairs/{repair}/attachments?requestId={request["id"]}', {}, ['file'])
        body = {'requestId': request['id'], 'submissionKey': str(uuid.uuid4()), 'attachmentIds': [uploaded['attachmentId']]}
        resumed = client.call('POST', f'/api/repairs/{repair}/diagnosis/resume', body)
        duplicate = client.call('POST', f'/api/repairs/{repair}/diagnosis/resume', body)
        assert resumed == duplicate
    assert state['status'] == ('FAILED' if scenario == 'manual' else 'SUCCEEDED')
    assert rounds == {'complete': 0, 'photo': 1, 'manual': 3}[scenario]
    print(f'{scenario}: {state["status"]}, photo rounds={rounds}, repairId={repair}')
    return repair

def dashboard_counts(client):
    rows = client.call('GET', '/api/dashboard?from=2000-01-01&to=2100-01-01&groupBy=AREA')
    return {row['dimension']: row['count'] for row in rows}

def first_sse_id(client, repair, cursor=None):
    headers = {'Accept': 'text/event-stream'}
    if cursor is not None: headers['Last-Event-ID'] = str(cursor)
    req = urllib.request.Request(BASE + f'/api/repairs/{repair}/events', headers=headers)
    with client.http.open(req, timeout=10) as response:
        for line in response:
            if line.startswith(b'id:'): return int(line.split(b':', 1)[1].strip())
    raise AssertionError('SSE did not return an event ID')

def main():
    tech, quality = Client('tech1'), Client('quality')
    repair = diagnose(tech, 'complete')
    procurement = wait_for(lambda: tech.call('GET', f'/api/repairs/{repair}/procurement'), lambda p: p['status'] in ['SUCCEEDED', 'FAILED'], 'procurement')
    assert procurement['status'] == 'SUCCEEDED'
    order = procurement['orders'][0]['id']
    quality.call('POST', f'/api/orders/{order}/approve', {'reason': '개발 시연 승인'})
    shipped = tech.call('POST', f'/api/orders/{order}/ship', {'reason': '개발 시연 출고'})
    assert shipped['status'] == 'ORDERED'
    assert tech.call('POST', f'/api/orders/{order}/receive', {'reason': '로컬 수령 확인'})['status'] == 'RECEIVED'
    baseline = dashboard_counts(tech)
    content = {'actualCause': '바퀴 모터 고장', 'faults': [{'area': 'MOTOR', 'componentId': COMPONENT, 'partId': PART}], 'actualAction': '모터 교체', 'technicianOpinion': '재현 완료', 'repairSuccessful': True, 'aiAcceptance': 'PARTIAL'}
    draft = tech.call('PUT', f'/api/repairs/{repair}/report/draft', {'version': 0, 'content': content})
    report = draft['id']
    confirmed = tech.call('POST', f'/api/reports/{report}/confirm', {'version': 1, 'revision': 1})
    assert dashboard_counts(tech).get('MOTOR', 0) == baseline.get('MOTOR', 0) + 1
    comment = {'revision': 1, 'body': '동일 lot 점검 요청', 'requestKey': str(uuid.uuid4())}
    quality.call('POST', f'/api/reports/{report}/comments', comment)
    content['actualCause'] = '펌웨어 제어 오류'
    content['faults'] = [{'area': 'SW'}]
    tech.call('PUT', f'/api/repairs/{repair}/report/draft', {'version': confirmed['version'], 'content': content})
    assert dashboard_counts(tech).get('MOTOR', 0) == baseline.get('MOTOR', 0) + 1
    tech.call('POST', f'/api/reports/{report}/confirm', {'version': 3, 'revision': 2})
    assert dashboard_counts(tech).get('MOTOR', 0) == baseline.get('MOTOR', 0)
    assert dashboard_counts(tech).get('SW', 0) == baseline.get('SW', 0) + 1
    assert len(tech.call('GET', f'/api/reports/{report}/comments')) == 1
    assert len(tech.call('GET', f'/api/reports/{report}/history')) == 2
    print('procurement -> approval -> shipping -> report v1 -> HQ comment -> report v2: OK')
    diagnose(tech, 'photo')
    manual = diagnose(tech, 'manual')
    assert tech.call('GET', f'/api/repairs/{manual}')['status'] == 'MANUAL_REVIEW'
    cursor = first_sse_id(tech, repair)
    assert first_sse_id(tech, repair, cursor) > cursor
    print('Dashboard draft/confirmation update and SSE Last-Event-ID replay: OK')
    print('All HTTP/Kafka demonstration scenarios passed.')

if __name__ == '__main__': main()
