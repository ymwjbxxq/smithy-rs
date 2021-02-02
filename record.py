"""
Basic skeleton of a mitmproxy addon.

Run as follows: mitmproxy -s anatomy.py
"""
import json
from dataclasses import asdict
from typing import Any, Dict, List, Optional

from flask import Flask
from mitmproxy import ctx, http
from mitmproxy.addons import asgiapp

from protocol_test import BASE_DIR, serialize, Action, ProtocolTest

app = Flask("proxapp")

recording: List[Action] = []
test_id: Optional[str] = None


@app.route("/record/start/<_test_id>")
def start_recording(_test_id) -> Dict[str, Any]:
    global recording
    global test_id
    recording = []
    test_id = _test_id
    return dict(status="ok")


@app.route("/record/stop")
def stop_recording() -> Dict[str, Any]:
    save()
    global recording
    resp = dict(status="ok", actions=len(recording))
    global test_id
    test_id = None
    recording = []
    test_id = None
    return resp


def save():
    test_dir = BASE_DIR / test_id
    test_dir.mkdir(exist_ok=True)
    for i, action in enumerate(recording):
        with open(test_dir / f'{i}.request.json', 'w') as f:
            f.write(serialize(action.request))
        with open(test_dir / f'{i}.response.json', 'w') as f:
            f.write(serialize(action.response))
        with open(test_dir / f'{i}.protocolTest.json', 'w') as f:
            json.dump(asdict(ProtocolTest.from_request(action.request)), f, indent=2)


class Record:
    def response(self, flow: http.HTTPFlow):
        if test_id and flow.request.host != 'crucible':
            recording.append(Action(flow.request.copy(), flow.response.copy(), None))
            ctx.log("action recorded")
        else:
            ctx.log("action recieved but no recording in progress")


addons = [
    asgiapp.WSGIApp(app, "crucible", 80),
    Record(),
]
