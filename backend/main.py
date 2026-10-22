"""Living Book AI proxy. Keeps provider keys off the phone and forwards small, bounded completion requests.

Run: uvicorn main:create_app --factory --host 0.0.0.0 --port 8787
"""

import hmac
import logging
import os
import time
from typing import Literal

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, ValidationError

from providers import ProviderError, make_provider

MAX_BODY_BYTES = 200 * 1024
MAX_MESSAGES = 40
MAX_TOKENS_CAP = 4000

log = logging.getLogger("livingbook.proxy")


class Message(BaseModel):
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1)


class CompleteRequest(BaseModel):
    system: str = ""
    messages: list[Message] = Field(min_length=1)
    max_tokens: int = Field(default=1500, ge=1)


def error(status: int, message: str) -> JSONResponse:
    return JSONResponse({"error": message}, status_code=status)


def create_app(provider=None, token: str | None = None) -> FastAPI:
    token = (token if token is not None else os.environ.get("PROXY_TOKEN", "")).strip()
    if not token:
        raise RuntimeError("PROXY_TOKEN is not set. Set it to a long random secret; the proxy will not run without auth.")
    provider = provider or make_provider()
    logging.basicConfig(level=logging.INFO)
    app = FastAPI(title="Living Book AI proxy", docs_url=None, redoc_url=None, openapi_url=None)

    @app.get("/health")
    async def health():
        return {"ok": True, "provider": provider.name, "model": provider.model}

    @app.post("/v1/complete")
    async def complete(request: Request):
        started = time.monotonic()
        response, result = await handle(request)
        # Privacy: status, latency and token counts only, never message content.
        log.info(
            "complete status=%s latency_ms=%d input_tokens=%s output_tokens=%s cache_read_tokens=%s stop_reason=%s",
            response.status_code, (time.monotonic() - started) * 1000,
            getattr(result, "input_tokens", None), getattr(result, "output_tokens", None),
            getattr(result, "cache_read_tokens", None), getattr(result, "stop_reason", None),
        )
        return response

    async def handle(request: Request):
        scheme, _, given = request.headers.get("authorization", "").partition(" ")
        if scheme.lower() != "bearer" or not hmac.compare_digest(given.strip().encode(), token.encode()):
            return error(401, "Invalid or missing proxy token."), None

        try:
            declared = int(request.headers.get("content-length") or 0)
        except ValueError:
            return error(400, "Invalid Content-Length header."), None
        body = None if declared > MAX_BODY_BYTES else await read_limited(request)
        if body is None:
            return error(413, f"Request body is over {MAX_BODY_BYTES // 1024} KB."), None
        try:
            req = CompleteRequest.model_validate_json(body)
        except ValidationError:
            # Not 422: the app reads 422 as "the model declined".
            return error(400, "Malformed request. Expected {system, messages: [{role, content}], max_tokens}."), None
        if len(req.messages) > MAX_MESSAGES:
            return error(413, f"At most {MAX_MESSAGES} messages per request."), None
        if req.messages[0].role != "user":
            return error(400, "The first message must come from the user."), None

        try:
            result = await provider.complete(
                req.system,
                [m.model_dump() for m in req.messages],
                min(req.max_tokens, MAX_TOKENS_CAP),
            )
        except ProviderError as e:
            return error(e.status, e.message), None
        return JSONResponse({"text": result.text, "model": result.model}), result

    return app


async def read_limited(request: Request) -> bytes | None:
    """The body, or None once it passes MAX_BODY_BYTES. Streams so an oversized body is never held in memory."""
    chunks, size = [], 0
    async for chunk in request.stream():
        size += len(chunk)
        if size > MAX_BODY_BYTES:
            return None
        chunks.append(chunk)
    return b"".join(chunks)
