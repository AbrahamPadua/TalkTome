"""Model providers behind the proxy. Each turns (system, messages, max_tokens) into a Completion or a ProviderError."""

import os
from dataclasses import dataclass

import anthropic
import httpx

# Answer before the app's 90 s read timeout in the common case.
TIMEOUT_SECONDS = 80.0


@dataclass
class Completion:
    text: str
    model: str
    input_tokens: int | None = None
    output_tokens: int | None = None
    cache_read_tokens: int | None = None
    stop_reason: str | None = None


class ProviderError(Exception):
    """A provider failure already mapped to the HTTP status and short message the app expects."""

    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status
        self.message = message


def declined() -> ProviderError:
    return ProviderError(422, "The model declined this request.")


class AnthropicProvider:
    name = "anthropic"

    def __init__(self, client=None):
        self.model = os.environ.get("ANTHROPIC_MODEL", "claude-opus-5-5")
        self.effort = os.environ.get("LLM_EFFORT", "low")
        # Reads ANTHROPIC_API_KEY.
        self.client = client or anthropic.AsyncAnthropic(timeout=TIMEOUT_SECONDS, max_retries=1)

    async def complete(self, system: str, messages: list[dict], max_tokens: int) -> Completion:
        extra = {}
        if system:
            # The app keeps its system prompts constant, so they cache across calls.
            extra["system"] = [{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}]
        try:
            resp = await self.client.beta.messages.create(
                model=self.model,
                max_tokens=max_tokens,
                messages=messages,
                output_config={"effort": self.effort},
                betas=["server-side-fallback-2026-07-01"],
                fallbacks="default",
                **extra,
            )
        except anthropic.AuthenticationError:
            raise ProviderError(502, "The proxy's AI provider key was rejected.")
        except anthropic.RateLimitError:
            raise ProviderError(429, "The AI provider is rate limiting requests. Try again shortly.")
        except anthropic.APIStatusError as e:
            raise ProviderError(502, f"The AI provider returned an error ({e.status_code}).")
        except anthropic.APITimeoutError:  # subclass of APIConnectionError, so it goes first
            raise ProviderError(504, "The AI provider timed out.")
        except anthropic.APIConnectionError:
            raise ProviderError(502, "Can't reach the AI provider.")

        if resp.stop_reason == "refusal":
            raise declined()
        usage = resp.usage
        return Completion(
            text="".join(block.text for block in resp.content if block.type == "text"),
            model=resp.model,
            input_tokens=usage.input_tokens,
            output_tokens=usage.output_tokens,
            cache_read_tokens=getattr(usage, "cache_read_input_tokens", None),
            stop_reason=resp.stop_reason,
        )


class OpenAICompatibleProvider:
    """Any OpenAI-style /chat/completions endpoint: OpenAI, Ollama (http://localhost:11434/v1), other hosts."""

    name = "openai_compatible"

    def __init__(self, http: httpx.AsyncClient | None = None):
        self.base_url = os.environ.get("OPENAI_BASE_URL", "https://api.openai.com/v1").rstrip("/")
        self.model = os.environ.get("OPENAI_MODEL", "")
        if not self.model:
            raise RuntimeError("OPENAI_MODEL is not set. Name the model to use, for example llama3.1 for Ollama.")
        key = os.environ.get("OPENAI_API_KEY")  # optional for Ollama
        self.http = http or httpx.AsyncClient(
            headers={"Authorization": f"Bearer {key}"} if key else {},
            timeout=TIMEOUT_SECONDS,
        )

    async def complete(self, system: str, messages: list[dict], max_tokens: int) -> Completion:
        payload = {
            "model": self.model,
            "max_tokens": max_tokens,
            "messages": ([{"role": "system", "content": system}] if system else []) + messages,
        }
        try:
            r = await self.http.post(f"{self.base_url}/chat/completions", json=payload)
        except httpx.TimeoutException:
            raise ProviderError(504, "The AI provider timed out.")
        except httpx.HTTPError:
            raise ProviderError(502, "Can't reach the AI provider.")

        if r.status_code in (401, 403):
            raise ProviderError(502, "The proxy's AI provider key was rejected.")
        if r.status_code == 429:
            raise ProviderError(429, "The AI provider is rate limiting requests. Try again shortly.")
        if r.status_code >= 400:
            raise ProviderError(502, f"The AI provider returned an error ({r.status_code}).")
        try:
            data = r.json()
            choice = data["choices"][0]
            text = choice["message"]["content"] or ""
        except (ValueError, KeyError, IndexError, TypeError):
            raise ProviderError(502, "The AI provider sent a reply the proxy couldn't read.")
        if choice.get("finish_reason") == "content_filter":
            raise declined()
        usage = data.get("usage") or {}
        return Completion(
            text=text,
            model=data.get("model") or self.model,
            input_tokens=usage.get("prompt_tokens"),
            output_tokens=usage.get("completion_tokens"),
            stop_reason=choice.get("finish_reason"),
        )


def make_provider():
    name = os.environ.get("LLM_PROVIDER", "anthropic")
    if name == "anthropic":
        return AnthropicProvider()
    if name == "openai_compatible":
        return OpenAICompatibleProvider()
    raise RuntimeError(f"Unknown LLM_PROVIDER {name!r}. Use anthropic or openai_compatible.")
