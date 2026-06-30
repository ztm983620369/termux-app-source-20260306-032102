#!/usr/bin/env python3
"""Local OpenAI Responses test endpoint for Crush native E2E runs.

This server intentionally implements the wire protocol Crush already uses for
OpenAI Responses streaming. It is not a model mock inside Crush: Crush still
performs real HTTP requests, sends real tools, parses real SSE events, runs
real tools, and persists real sessions/messages.
"""

from __future__ import annotations

import argparse
import json
import os
import signal
import sys
import threading
import time
import uuid
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any


DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 18080
DEFAULT_KEY = "crush-native-e2e-key"
DEFAULT_MODEL = "gpt-5.1-crush-native-e2e"


@dataclass(frozen=True)
class Step:
    kind: str
    match: dict[str, Any] | None = None
    text: str = ""
    name: str = ""
    arguments: str = "{}"


class State:
    def __init__(self, *, api_key: str, model: str, script: list[Step], log_path: Path | None):
        self.api_key = api_key
        self.model = model
        self.script = script
        self.log_path = log_path
        self.lock = threading.Lock()
        self.request_count = 0
        self.sequence = 0

    def next_request_index(self) -> int:
        with self.lock:
            self.request_count += 1
            return self.request_count

    def next_sequence(self) -> int:
        with self.lock:
            self.sequence += 1
            return self.sequence

    def log(self, event: str, **fields: Any) -> None:
        payload = {
            "ts": time.time(),
            "event": event,
            **fields,
        }
        line = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
        print(line, flush=True)
        if self.log_path is None:
            return
        with self.lock:
            self.log_path.parent.mkdir(parents=True, exist_ok=True)
            with self.log_path.open("a", encoding="utf-8") as f:
                f.write(line + "\n")

    def step_for_request(self, request_index: int, request: dict[str, Any]) -> Step:
        if not self.script:
            return Step("text", text=f"native-e2e response #{request_index}")
        for step in self.script:
            if step.match and match_request(step.match, request):
                return step
        unmatched = [step for step in self.script if not step.match]
        if unmatched:
            idx = min(request_index - 1, len(unmatched) - 1)
            return unmatched[idx]
        idx = min(request_index - 1, len(self.script) - 1)
        return self.script[idx]


def load_script(path: Path | None) -> list[Step]:
    if path is None:
        return [Step("text", text="native-e2e ok")]
    steps: list[Step] = []
    for line_no, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        try:
            item = json.loads(line)
        except json.JSONDecodeError as exc:
            raise SystemExit(f"{path}:{line_no}: invalid JSON: {exc}") from exc
        kind = item.get("type") or item.get("kind")
        match = item.get("match")
        if match is not None and not isinstance(match, dict):
            raise SystemExit(f"{path}:{line_no}: match must be an object")
        if kind == "text":
            steps.append(Step("text", match=match, text=str(item.get("text", ""))))
            continue
        if kind == "tool_call":
            arguments = item.get("arguments", {})
            if not isinstance(arguments, str):
                arguments = json.dumps(arguments, ensure_ascii=False, separators=(",", ":"))
            steps.append(Step("tool_call", match=match, name=str(item.get("name", "")), arguments=arguments))
            continue
        raise SystemExit(f"{path}:{line_no}: unsupported step type {kind!r}")
    return steps


def as_list(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, list):
        return [str(v) for v in value]
    return [str(value)]


def request_tool_names(request: dict[str, Any]) -> set[str]:
    names: set[str] = set()
    for tool in request.get("tools") or []:
        if isinstance(tool, dict):
            name = tool.get("name")
            if isinstance(name, str):
                names.add(name)
            function = tool.get("function")
            if isinstance(function, dict) and isinstance(function.get("name"), str):
                names.add(function["name"])
    return names


def request_text(request: dict[str, Any]) -> str:
    return json.dumps(request.get("input"), ensure_ascii=False, separators=(",", ":")).lower()


def match_request(match: dict[str, Any], request: dict[str, Any]) -> bool:
    tools = request_tool_names(request)
    text = request_text(request)

    if match.get("has_tools") is True and not tools:
        return False
    if match.get("no_tools") is True and tools:
        return False

    for name in as_list(match.get("tools_contains")):
        if name not in tools:
            return False
    for name in as_list(match.get("tools_not_contains")):
        if name in tools:
            return False

    for needle in as_list(match.get("input_contains")):
        if needle.lower() not in text:
            return False
    for needle in as_list(match.get("input_not_contains")):
        if needle.lower() in text:
            return False

    return True


def compact_input(value: Any) -> Any:
    if isinstance(value, str):
        if len(value) <= 500:
            return value
        return value[:500] + "...<truncated>"
    if isinstance(value, list):
        return [compact_input(v) for v in value[:20]]
    if isinstance(value, dict):
        return {k: compact_input(v) for k, v in list(value.items())[:50]}
    return value


def response_base(response_id: str, model: str, *, status: str = "in_progress") -> dict[str, Any]:
    return {
        "id": response_id,
        "object": "response",
        "created_at": int(time.time()),
        "status": status,
        "model": model,
        "output": [],
        "parallel_tool_calls": True,
        "previous_response_id": None,
        "error": None,
        "incomplete_details": None,
        "usage": {
            "input_tokens": 1,
            "input_tokens_details": {"cached_tokens": 0},
            "output_tokens": 1,
            "output_tokens_details": {"reasoning_tokens": 0},
            "total_tokens": 2,
        },
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "CrushNativeE2E/1.0"

    @property
    def state(self) -> State:
        return self.server.state  # type: ignore[attr-defined]

    def log_message(self, fmt: str, *args: Any) -> None:
        self.state.log("http_log", client=self.client_address[0], message=fmt % args)

    def do_GET(self) -> None:
        if self.path == "/healthz":
            self.send_json(200, {"ok": True, "model": self.state.model})
            return
        if self.path == "/v1/models":
            self.send_json(
                200,
                {
                    "object": "list",
                    "data": [
                        {
                            "id": self.state.model,
                            "object": "model",
                            "created": 0,
                            "owned_by": "crush-native-e2e",
                        }
                    ],
                },
            )
            return
        self.send_json(404, {"error": {"message": f"unknown path: {self.path}"}})

    def do_POST(self) -> None:
        if self.path != "/v1/responses":
            self.send_json(404, {"error": {"message": f"unknown path: {self.path}"}})
            return
        auth = self.headers.get("Authorization", "")
        if auth != f"Bearer {self.state.api_key}":
            self.state.log("auth_failed", authorization=auth)
            self.send_json(401, {"error": {"message": "invalid e2e key", "type": "invalid_request_error"}})
            return

        length = int(self.headers.get("Content-Length", "0") or "0")
        body = self.rfile.read(length)
        try:
            request = json.loads(body.decode("utf-8")) if body else {}
        except json.JSONDecodeError as exc:
            self.send_json(400, {"error": {"message": f"invalid JSON: {exc}"}})
            return

        request_index = self.state.next_request_index()
        self.state.log(
            "request",
            index=request_index,
            method="POST",
            path=self.path,
            model=request.get("model"),
            stream=request.get("stream"),
            input=compact_input(request.get("input")),
            tools=compact_input(request.get("tools")),
        )
        step = self.state.step_for_request(request_index, request)
        if request.get("stream") is False:
            self.send_non_stream_response(request, request_index, step)
            return
        self.send_stream_response(request, request_index, step)

    def send_json(self, status: int, payload: dict[str, Any]) -> None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def send_sse_event(self, event_type: str, payload: dict[str, Any]) -> None:
        payload.setdefault("type", event_type)
        payload.setdefault("sequence_number", self.state.next_sequence())
        data = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
        self.state.log("sse", type=event_type, data=payload)
        self.wfile.write(f"event: {event_type}\n".encode("utf-8"))
        self.wfile.write(f"data: {data}\n\n".encode("utf-8"))
        self.wfile.flush()

    def send_stream_response(self, request: dict[str, Any], request_index: int, step: Step) -> None:
        response_id = f"resp_{uuid.uuid4().hex}"
        model = str(request.get("model") or self.state.model)

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.end_headers()

        self.send_sse_event(
            "response.created",
            {"response": response_base(response_id, model, status="in_progress")},
        )
        if step.kind == "tool_call":
            self.emit_tool_call(response_id, model, step)
        else:
            self.emit_text(response_id, model, step.text)
        completed = response_base(response_id, model, status="completed")
        self.send_sse_event("response.completed", {"response": completed})
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()
        self.close_connection = True

    def send_non_stream_response(self, request: dict[str, Any], request_index: int, step: Step) -> None:
        response_id = f"resp_{uuid.uuid4().hex}"
        model = str(request.get("model") or self.state.model)
        response = response_base(response_id, model, status="completed")
        if step.kind == "tool_call":
            call_id = f"call_{uuid.uuid4().hex}"
            response["output"] = [
                {
                    "id": f"fc_{uuid.uuid4().hex}",
                    "type": "function_call",
                    "status": "completed",
                    "call_id": call_id,
                    "name": step.name,
                    "arguments": step.arguments,
                }
            ]
        else:
            response["output"] = [
                {
                    "id": f"msg_{uuid.uuid4().hex}",
                    "type": "message",
                    "role": "assistant",
                    "status": "completed",
                    "content": [{"type": "output_text", "text": step.text, "annotations": []}],
                }
            ]
        self.state.log("response", index=request_index, response=response)
        self.send_json(200, response)

    def emit_text(self, response_id: str, model: str, text: str) -> None:
        item_id = f"msg_{uuid.uuid4().hex}"
        item_added = {
            "id": item_id,
            "type": "message",
            "role": "assistant",
            "status": "in_progress",
            "content": [],
        }
        self.send_sse_event("response.output_item.added", {"output_index": 0, "item": item_added})

        for chunk in split_text(text):
            self.send_sse_event(
                "response.output_text.delta",
                {
                    "output_index": 0,
                    "content_index": 0,
                    "item_id": item_id,
                    "delta": chunk,
                    "logprobs": [],
                },
            )
            time.sleep(0.01)

        item_done = {
            "id": item_id,
            "type": "message",
            "role": "assistant",
            "status": "completed",
            "content": [{"type": "output_text", "text": text, "annotations": []}],
        }
        self.send_sse_event("response.output_item.done", {"output_index": 0, "item": item_done})

    def emit_tool_call(self, response_id: str, model: str, step: Step) -> None:
        if not step.name:
            raise RuntimeError("tool_call step requires name")
        item_id = f"fc_{uuid.uuid4().hex}"
        call_id = f"call_{uuid.uuid4().hex}"
        item_added = {
            "id": item_id,
            "type": "function_call",
            "status": "in_progress",
            "call_id": call_id,
            "name": step.name,
            "arguments": "",
        }
        self.send_sse_event("response.output_item.added", {"output_index": 0, "item": item_added})

        for chunk in split_text(step.arguments):
            self.send_sse_event(
                "response.function_call_arguments.delta",
                {
                    "output_index": 0,
                    "item_id": item_id,
                    "delta": chunk,
                },
            )
            time.sleep(0.01)

        self.send_sse_event(
            "response.function_call_arguments.done",
            {
                "output_index": 0,
                "item_id": item_id,
                "name": step.name,
                "arguments": step.arguments,
            },
        )
        item_done = {
            "id": item_id,
            "type": "function_call",
            "status": "completed",
            "call_id": call_id,
            "name": step.name,
            "arguments": step.arguments,
        }
        self.send_sse_event("response.output_item.done", {"output_index": 0, "item": item_done})


def split_text(text: str, width: int = 32) -> list[str]:
    if text == "":
        return [""]
    return [text[i : i + width] for i in range(0, len(text), width)]


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Fake OpenAI Responses endpoint for Crush native E2E.")
    parser.add_argument("--host", default=os.environ.get("CRUSH_E2E_HOST", DEFAULT_HOST))
    parser.add_argument("--port", type=int, default=int(os.environ.get("CRUSH_E2E_PORT", DEFAULT_PORT)))
    parser.add_argument("--key", default=os.environ.get("CRUSH_E2E_KEY", DEFAULT_KEY))
    parser.add_argument("--model", default=os.environ.get("CRUSH_E2E_MODEL", DEFAULT_MODEL))
    parser.add_argument("--script", type=Path, default=None, help="JSONL response script.")
    parser.add_argument("--log", type=Path, default=None, help="Optional JSONL log file.")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    state = State(api_key=args.key, model=args.model, script=load_script(args.script), log_path=args.log)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.state = state  # type: ignore[attr-defined]

    def shutdown(signum: int, _frame: Any) -> None:
        state.log("shutdown", signal=signum)
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)

    state.log("listen", host=args.host, port=args.port, key=args.key, model=args.model)
    server.serve_forever()
    server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
