import logging
from dataclasses import dataclass
from typing import Optional, Dict, Any, List

from mitmproxy import ctx, http, command

from protocol_test import TestCase, load_testcase
from flask import Flask
from mitmproxy.addons import asgiapp

app = Flask("proxapp")


@dataclass
class TestState:
    requests: List[http.HTTPRequest]
    test_case: TestCase

    def next_response(self) -> Optional[http.HTTPResponse]:
        if len(self.test_case.actions) > len(self.requests):
            return self.test_case.actions[len(self.requests)].response
        else:
            return None


test_state: Optional[TestState] = None


@app.route('/start_test/<test_id>')
def start_testcase(test_id) -> Dict[str, Any]:
    global test_state
    if test_state is not None:
        return {"status": "error", "msg": f"test case {test_state.test_case.id} is already in progress"}
    try:
        test_state = TestState(requests=[], test_case=load_testcase(test_id))
    except Exception as ex:
        logging.exception(ex)
        return {"status": "error", "msg": str(ex)}
    return {"status": "ok"}


@app.route('/clear_test')
def clear_test() -> Dict[str, Any]:
    global test_state
    test_state = None
    return {"status": "ok"}


@app.route('/check_test')
def check_test() -> Dict[str, Any]:
    global test_state
    if test_state is None:
        return {"status": "error", "msg": "No test case in progress"}
    if len(test_state.requests) != len(test_state.test_case.actions):
        return {"status": "ok", "msg": "Wrong number of requests received"}
    elif dataclass:
        errors = []
        for request, action in zip(test_state.requests, test_state.test_case.actions):
            if action.request_validator:
                errors += action.request_validator.validate_request(request)
            else:
                errors.append("Action did not have validator")

        return {"status": "ok", "errors": errors}


class Test:
    def request(self, flow: http.HTTPFlow):
        if test_state is not None and flow.request.host != "crucible":
            ctx.log("got response")
            flow.intercept()
            next_response = test_state.next_response()
            test_state.requests.append(flow.request.copy())
            if next_response is None:
                flow.kill()
            else:
                flow.response = next_response
                flow.is_replay = "response"
            flow.resume()


addons = [
    asgiapp.WSGIApp(app, "crucible", 80),
    Test(),
]
