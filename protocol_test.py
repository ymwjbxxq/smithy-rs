import json
from dataclasses import dataclass
from pathlib import Path
from typing import Optional, List, Dict, Any

from mitmproxy import http

MUST_MATCH_HEADERS = ["Host", "X-Amz-Target", "Content-Type"]
MUST_EXIST_HEADERS = ["Authorization", "X-Amz-Date"]


@dataclass
class ProtocolTest:
    id: str
    # protocol: str
    body: Optional[str]

    method: str
    uri: str
    authScheme: Optional[str]

    queryParams: List[str]
    forbidQueryParams: List[str]
    requireQueryParams: List[str]

    headers: Dict[str, str]
    forbidHeaders: List[str]
    requireHeaders: List[str]

    params: Dict[str, Any]
    vendorParams: Dict[str, Any]

    documentation: str

    @classmethod
    def from_request(cls, request: http.HTTPRequest):
        headers = {name: request.headers[name] for name in MUST_MATCH_HEADERS if name in request.headers}
        return ProtocolTest(
            id="todo",
            uri=request.url,
            method=request.method,
            body=request.content.decode('utf-8'),
            authScheme=None,
            queryParams=[],
            forbidQueryParams=[],
            requireQueryParams=[],

            headers=headers,
            forbidHeaders=[],
            requireHeaders=MUST_EXIST_HEADERS,
            params=dict(todo="todo"),
            vendorParams=dict(),

            documentation="todo"
        )

    def validate_request(self, request: http.HTTPRequest):
        errors = []
        if request.url != self.uri:
            errors.append(f"Incorrect URL actual: {request.url} expected: {self.uri}")
        return errors


@dataclass
class Action:
    request: http.HTTPRequest
    response: http.HTTPResponse
    request_validator: Optional[ProtocolTest]


@dataclass
class TestCase:
    id: str
    actions: List[Action]


BASE_DIR = Path('tests')


def load_testcase(test_id: str) -> TestCase:
    test_dir = BASE_DIR / test_id
    if not test_dir.exists():
        raise Exception("test case does not exist")
    actions = []
    for i in range(100):
        f = test_dir / f'{i}.request.json'
        if not f.exists():
            break
        with open(f) as f:
            request = http.HTTPRequest.from_state(deserialize(f.read()))

        with open(test_dir / f'{i}.response.json', 'r') as f:
            response = http.HTTPResponse.from_state(deserialize(f.read()))

        with open(test_dir / f'{i}.protocolTest.json', 'r') as f:
            protocol_test_data = json.load(f)
            protocol_test = ProtocolTest(**protocol_test_data)

        actions.append(Action(request, response, protocol_test))
    return TestCase(id=test_id, actions=actions)


def dec(b: bytes) -> str:
    return b.decode('utf-8')


def enc(s: str) -> bytes:
    return s.encode('utf-8')


FIELDS = ['content', 'http_version', 'scheme', 'authority', 'path', 'method', 'reason']


def serialize(operation):
    state = operation.get_state()
    for field in FIELDS:
        if field in state:
            state[field] = dec(state[field])
    state['headers'] = [(dec(k), dec(v)) for k, v in state['headers']]
    return json.dumps(state, indent=2)


def deserialize(data):
    state = json.loads(data)
    for field in FIELDS:
        if field in state:
            state[field] = enc(state[field])
    state['headers'] = [(enc(k), enc(v)) for k, v in state['headers']]
    return state
