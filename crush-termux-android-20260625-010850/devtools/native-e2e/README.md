# Native E2E Fake OpenAI Responses Endpoint

This directory is an external native test harness for Crush. It does not wire
anything into the production CLI. Crush talks to it through the normal OpenAI
Responses HTTP protocol.

Run the endpoint:

```sh
python3 devtools/native-e2e/fake_openai_responses.py \
  --script devtools/native-e2e/session_title.jsonl \
  --log /tmp/crush-native-e2e.jsonl
```

Run Crush against it from a temporary working directory:

```sh
mkdir -p /tmp/crush-native-e2e-work
cp devtools/native-e2e/crush.json /tmp/crush-native-e2e-work/crush.json
cd /tmp/crush-native-e2e-work
/tmp/crush-title-test run --quiet "rename this session"
```

Use `session_title_direct_get.jsonl` to verify that title lookup can call the
native `get_session_title` tool directly without first loading the
`session-title` skill document.

Built-in values:

- Endpoint: `http://127.0.0.1:18080/v1`
- API key: `crush-native-e2e-key`
- Model: `gpt-5.1-crush-native-e2e`

Script JSONL format:

```jsonl
{"type":"text","text":"hello"}
{"type":"tool_call","name":"set_session_title","arguments":{"title":"Native E2E Title"}}
{"match":{"tools_contains":"set_session_title"},"type":"tool_call","name":"set_session_title","arguments":{"title":"Native E2E Title"}}
```

Each request first tries matching steps with a `match` object. Supported match
keys are `has_tools`, `no_tools`, `tools_contains`, `tools_not_contains`,
`input_contains`, and `input_not_contains`. If no match applies, unmatched
script lines are used sequentially as a fallback. The server logs every request
and every SSE event as JSONL to stdout and to `--log` when provided.
