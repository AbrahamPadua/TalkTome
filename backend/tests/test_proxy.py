import asyncio
import json
import logging
from types import SimpleNamespace

import anthropic
import httpx
import httpx2
import pytest
from fastapi.testclient import TestClient

import main
from providers import AnthropicProvider, Completion, OpenAICompatibleProvider, ProviderError

TOKEN = "test-token"
AUTH = {"Authorization": f"Bearer {TOKEN}"}
BODY = {"system": "Be precise.", "messages": [{"role": "user", "content": "What is a prior?"}], "max_tokens": 1500}


class FakeProvider:
    name = "fake"
    model = "fake-model"

    def __init__(self, result=None, error=None):
        self.result = result or Completion("A prior is a belief.", "fake-model", 10, 5)
        self.error = error
        self.calls = []

    async def complete(self, system, messages, max_tokens):
        self.calls.append((system, messages, max_tokens))
        if self.error:
            raise self.error
        return self.result


def client_for(provider) -> TestClient:
    return TestClient(main.create_app(provider=provider, token=TOKEN))


# ---- App: auth, limits, success ------------------------------------------------------------------------------------

@pytest.mark.parametrize("value", [None, "", "   \n"])
def test_refuses_to_start_without_token(monkeypatch, value):
    if value is None:
        monkeypatch.delenv("PROXY_TOKEN", raising=False)
    else:
        monkeypatch.setenv("PROXY_TOKEN", value)
    with pytest.raises(RuntimeError, match="PROXY_TOKEN"):
        main.create_app(provider=FakeProvider())


def test_token_from_env_is_stripped(monkeypatch):
    monkeypatch.setenv("PROXY_TOKEN", f"  {TOKEN}\n")
    client = TestClient(main.create_app(provider=FakeProvider()))
    assert client.post("/v1/complete", json=BODY, headers=AUTH).status_code == 200


def test_non_numeric_content_length_is_400():
    r = client_for(FakeProvider()).post("/v1/complete", content=b"{}", headers={**AUTH, "Content-Length": "abc"})
    assert r.status_code == 400


def test_health():
    r = client_for(FakeProvider()).get("/health")
    assert r.status_code == 200
    assert r.json() == {"ok": True, "provider": "fake", "model": "fake-model"}


@pytest.mark.parametrize("headers", [{}, {"Authorization": "Bearer wrong"}, {"Authorization": TOKEN}])
def test_rejects_bad_auth(headers):
    provider = FakeProvider()
    r = client_for(provider).post("/v1/complete", json=BODY, headers=headers)
    assert r.status_code == 401
    assert provider.calls == []


def test_success_caps_max_tokens():
    provider = FakeProvider()
    r = client_for(provider).post("/v1/complete", json={**BODY, "max_tokens": 99999}, headers=AUTH)
    assert r.status_code == 200
    assert r.json() == {"text": "A prior is a belief.", "model": "fake-model"}
    assert provider.calls == [("Be precise.", [{"role": "user", "content": "What is a prior?"}], main.MAX_TOKENS_CAP)]


def test_body_too_large():
    big = {**BODY, "messages": [{"role": "user", "content": "x" * (main.MAX_BODY_BYTES + 1)}]}
    assert client_for(FakeProvider()).post("/v1/complete", json=big, headers=AUTH).status_code == 413


def test_too_many_messages():
    msgs = [{"role": "user" if i % 2 == 0 else "assistant", "content": "hi"} for i in range(main.MAX_MESSAGES + 1)]
    assert client_for(FakeProvider()).post("/v1/complete", json={**BODY, "messages": msgs}, headers=AUTH).status_code == 413


@pytest.mark.parametrize("body", [
    "not json",
    json.dumps({"messages": []}),
    json.dumps({"messages": [{"role": "system", "content": "x"}]}),
    json.dumps({"messages": [{"role": "assistant", "content": "prefill"}]}),
])
def test_malformed_requests_are_400_not_422(body):
    r = client_for(FakeProvider()).post("/v1/complete", content=body, headers={**AUTH, "Content-Type": "application/json"})
    assert r.status_code == 400


def test_provider_error_passes_through():
    provider = FakeProvider(error=ProviderError(429, "slow down"))
    r = client_for(provider).post("/v1/complete", json=BODY, headers=AUTH)
    assert r.status_code == 429
    assert r.json() == {"error": "slow down"}


def test_logs_usage_but_never_content(caplog):
    with caplog.at_level(logging.INFO, logger="livingbook.proxy"):
        client_for(FakeProvider()).post("/v1/complete", json=BODY, headers=AUTH)
    logged = caplog.text
    assert "status=200" in logged and "input_tokens=10" in logged
    assert "prior" not in logged.lower() and "precise" not in logged.lower()


# ---- Anthropic provider --------------------------------------------------------------------------------------------

def fake_anthropic(response=None, error=None):
    calls = []

    async def create(**kwargs):
        calls.append(kwargs)
        if error:
            raise error
        return response

    return SimpleNamespace(beta=SimpleNamespace(messages=SimpleNamespace(create=create))), calls


def anthropic_response(stop_reason="end_turn"):
    return SimpleNamespace(
        stop_reason=stop_reason,
        model="claude-opus-5-5",
        content=[SimpleNamespace(type="thinking", thinking=""), SimpleNamespace(type="text", text="Hello "), SimpleNamespace(type="text", text="there.")],
        usage=SimpleNamespace(input_tokens=12, output_tokens=3, cache_read_input_tokens=8),
    )


def test_anthropic_request_shape(monkeypatch):
    monkeypatch.delenv("ANTHROPIC_MODEL", raising=False)
    monkeypatch.delenv("LLM_EFFORT", raising=False)
    client, calls = fake_anthropic(anthropic_response())

    result = asyncio.run(AnthropicProvider(client).complete("Be precise.", [{"role": "user", "content": "Hi"}], 1500))

    assert result.text == "Hello there."
    assert (result.model, result.input_tokens, result.output_tokens, result.cache_read_tokens) == ("claude-opus-5-5", 12, 3, 8)
    sent = calls[0]
    assert sent["model"] == "claude-opus-5-5"
    assert sent["max_tokens"] == 1500
    assert sent["output_config"] == {"effort": "low"}
    assert sent["betas"] == ["server-side-fallback-2026-07-01"]
    assert sent["fallbacks"] == "default"
    assert sent["system"][0]["text"] == "Be precise."
    assert sent["messages"] == [{"role": "user", "content": "Hi"}]
    assert not {"temperature", "top_p", "thinking"} & sent.keys()


def test_real_sdk_builds_the_expected_http_request(monkeypatch):
    """Runs the real SDK against a mock transport (no network) to check the wire format it sends."""
    monkeypatch.delenv("ANTHROPIC_MODEL", raising=False)
    seen = {}

    def handler(request):
        seen["beta"] = request.headers.get("anthropic-beta")
        seen["body"] = json.loads(request.content)
        return httpx2.Response(200, json={
            "id": "msg_1", "type": "message", "role": "assistant", "model": "claude-opus-5-5",
            "content": [{"type": "text", "text": "Hi"}], "stop_reason": "end_turn", "stop_sequence": None,
            "usage": {"input_tokens": 5, "output_tokens": 1},
        })

    sdk = anthropic.AsyncAnthropic(api_key="test", http_client=anthropic.DefaultAsyncHttpxClient(transport=httpx2.MockTransport(handler)))
    result = asyncio.run(AnthropicProvider(sdk).complete("Be precise.", [{"role": "user", "content": "Hi"}], 1500))

    assert result.text == "Hi"
    assert "server-side-fallback-2026-07-01" in seen["beta"]
    body = seen["body"]
    assert body["model"] == "claude-opus-5-5"
    assert body["fallbacks"] == "default"
    assert body["output_config"] == {"effort": "low"}
    assert body["system"] == [{"type": "text", "text": "Be precise.", "cache_control": {"type": "ephemeral"}}]


def test_anthropic_refusal_is_422():
    client, _ = fake_anthropic(anthropic_response(stop_reason="refusal"))
    with pytest.raises(ProviderError) as e:
        asyncio.run(AnthropicProvider(client).complete("", [{"role": "user", "content": "Hi"}], 100))
    assert (e.value.status, e.value.message) == (422, "The model declined this request.")


def test_refusal_reaches_the_app_as_422():
    client, _ = fake_anthropic(anthropic_response(stop_reason="refusal"))
    r = client_for(AnthropicProvider(client)).post("/v1/complete", json=BODY, headers=AUTH)
    assert r.status_code == 422
    assert r.json() == {"error": "The model declined this request."}


REQ = httpx2.Request("POST", "https://api.anthropic.com/v1/messages")


def status_error(cls, code):
    return cls("boom", response=httpx2.Response(code, request=REQ), body=None)


@pytest.mark.parametrize("error, status", [
    (status_error(anthropic.AuthenticationError, 401), 502),
    (status_error(anthropic.RateLimitError, 429), 429),
    (status_error(anthropic.InternalServerError, 529), 502),
    (status_error(anthropic.BadRequestError, 400), 502),
    (anthropic.APITimeoutError(request=REQ), 504),
    (anthropic.APIConnectionError(request=REQ), 502),
])
def test_anthropic_error_mapping(error, status):
    client, _ = fake_anthropic(error=error)
    with pytest.raises(ProviderError) as e:
        asyncio.run(AnthropicProvider(client).complete("", [{"role": "user", "content": "Hi"}], 100))
    assert e.value.status == status


# ---- OpenAI-compatible provider ------------------------------------------------------------------------------------

def openai_provider(monkeypatch, handler):
    monkeypatch.setenv("OPENAI_BASE_URL", "http://localhost:11434/v1/")
    monkeypatch.setenv("OPENAI_MODEL", "llama3.1")
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    return OpenAICompatibleProvider(httpx.AsyncClient(transport=httpx.MockTransport(handler)))


def test_openai_compatible_success(monkeypatch):
    seen = {}

    def handler(request):
        seen["url"] = str(request.url)
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json={
            "model": "llama3.1",
            "choices": [{"message": {"content": "Hi there."}, "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 7, "completion_tokens": 2},
        })

    result = asyncio.run(openai_provider(monkeypatch, handler).complete("Be precise.", [{"role": "user", "content": "Hi"}], 500))

    assert (result.text, result.model, result.input_tokens, result.output_tokens) == ("Hi there.", "llama3.1", 7, 2)
    assert seen["url"] == "http://localhost:11434/v1/chat/completions"
    assert seen["body"]["messages"][0] == {"role": "system", "content": "Be precise."}
    assert seen["body"]["max_tokens"] == 500


@pytest.mark.parametrize("response, status", [
    (httpx.Response(401, json={}), 502),
    (httpx.Response(429, json={}), 429),
    (httpx.Response(500, text="oops"), 502),
    (httpx.Response(200, text="not json"), 502),
    (httpx.Response(200, json={"choices": [{"message": {"content": ""}, "finish_reason": "content_filter"}]}), 422),
])
def test_openai_compatible_errors(monkeypatch, response, status):
    with pytest.raises(ProviderError) as e:
        asyncio.run(openai_provider(monkeypatch, lambda _: response).complete("", [{"role": "user", "content": "Hi"}], 100))
    assert e.value.status == status


def test_openai_compatible_unreachable(monkeypatch):
    def handler(request):
        raise httpx.ConnectError("refused", request=request)

    with pytest.raises(ProviderError) as e:
        asyncio.run(openai_provider(monkeypatch, handler).complete("", [{"role": "user", "content": "Hi"}], 100))
    assert e.value.status == 502


# ---- Timeouts ------------------------------------------------------------------------------------------------------

def test_provider_timeout_is_configurable(monkeypatch):
    monkeypatch.setenv("OPENAI_MODEL", "llama3.1")
    monkeypatch.delenv("LLM_TIMEOUT_SECONDS", raising=False)
    assert AnthropicProvider().client.timeout == 80.0
    monkeypatch.setenv("LLM_TIMEOUT_SECONDS", "300")
    assert AnthropicProvider().client.timeout == 300.0
    assert OpenAICompatibleProvider().http.timeout == httpx.Timeout(300.0)
