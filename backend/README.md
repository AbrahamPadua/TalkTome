# Living Book AI proxy

A small FastAPI service that sits between the Living Book app and a model provider. The provider key stays on this server, so it never ships inside the app. The app sends short excerpts (the current topic, a few retrieved passages, the question and a short learning-profile summary) and gets back the model's text.

## API

`POST /v1/complete` needs `Authorization: Bearer <PROXY_TOKEN>`.

```json
{"system": "...", "messages": [{"role": "user", "content": "..."}], "max_tokens": 1500}
```

It returns `{"text": "...", "model": "..."}`. Errors come back as `{"error": "..."}`:

| Status | Meaning |
|---|---|
| 400 | Malformed request |
| 401 | Missing or wrong proxy token |
| 413 | Body over 200 KB, or more than 40 messages |
| 422 | The model declined the request |
| 429 | The provider is rate limiting |
| 502 | Provider unreachable, provider key rejected, or another provider error |
| 504 | Provider timed out |

`max_tokens` is capped at 4000. `GET /health` returns `{"ok": true, "provider": "...", "model": "..."}` and needs no token.

Logs record the status, latency, token counts and stop reason of each request. Message content is never logged.

## Run locally

Python 3.11 or newer.

```bash
cd backend
python -m venv .venv
.venv/Scripts/activate          # Windows; on macOS or Linux: source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env            # then fill in PROXY_TOKEN and ANTHROPIC_API_KEY
uvicorn main:create_app --factory --host 0.0.0.0 --port 8787 --env-file .env
```

The server won't start without `PROXY_TOKEN`. One way to generate a token: `python -c "import secrets; print(secrets.token_urlsafe(32))"`.

## Connect a phone on the same Wi-Fi

1. Find the computer's local IP address: `ipconfig` on Windows (the IPv4 address of the Wi-Fi adapter), `ipconfig getifaddr en0` on macOS, `hostname -I` on Linux.
2. Allow inbound connections on port 8787. Windows asks the first time uvicorn listens; choose private networks.
3. Check from the phone's browser that `http://<that-ip>:8787/health` answers.
4. In the app, open Settings, set AI to the proxy mode, enter `http://<that-ip>:8787` as the proxy URL and your `PROXY_TOKEN` as the token, and accept the AI consent.

Plain HTTP is fine on your own network. Anywhere else, put the proxy behind HTTPS. The app only accepts an `http://` proxy URL for loopback, private network addresses (10.x, 172.16 to 172.31, 192.168.x), `.local` names and the Android emulator's `10.0.2.2`.

## Environment variables

| Variable | Default | Notes |
|---|---|---|
| `PROXY_TOKEN` | none (required) | Shared secret the app sends as a bearer token |
| `LLM_PROVIDER` | `anthropic` | `anthropic` or `openai_compatible` |
| `ANTHROPIC_API_KEY` | none | Read by the Anthropic SDK |
| `ANTHROPIC_MODEL` | `claude-opus-5-5` | |
| `LLM_EFFORT` | `low` | `low`, `medium`, `high`, `xhigh` or `max`. Higher is slower and costs more |
| `LLM_TIMEOUT_SECONDS` | `80` | How long to wait for the provider before returning 504. Slow local models need more, for example `300` for Ollama on a CPU. The app gives up after 120 s, so above that it shows its own timeout message |
| `OPENAI_BASE_URL` | `https://api.openai.com/v1` | For Ollama: `http://localhost:11434/v1` |
| `OPENAI_API_KEY` | none | Not needed for Ollama |
| `OPENAI_MODEL` | none | Required with `openai_compatible`, for example `llama3.1` |

With Anthropic, the proxy turns on server-side refusal fallbacks (`fallbacks: "default"`). If the model declines a request, the API retries it on a fallback model, and the app only sees a 422 when every model declined. The system prompt is sent with a cache breakpoint because the app keeps it the same on every call.

## Deploy

The included `Dockerfile` runs anywhere that runs a container (Fly.io, Railway, Render, Cloud Run, a VPS):

```bash
docker build -t livingbook-proxy backend
docker run -p 8787:8787 --env-file backend/.env livingbook-proxy
```

On a hosting platform, set the same variables in its dashboard, expose port 8787 and use the HTTPS address it gives you as the proxy URL in the app.

## Tests

```bash
pip install -r requirements-dev.txt
python -m pytest
```

The tests mock every provider, so they make no network calls.
