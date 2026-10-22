# TalkTome

An Android app that turns PDFs and EPUBs into a book you can read or listen to one topic at a time, and talk to
whenever you want. You can interrupt the narration, ask a question about the passage, get an answer grounded in the
book with links back to the source, and then go back to the sentence you were on.

Books, progress, notes and conversations stay on the phone. Reading and narration work offline. The AI part goes
through a small backend proxy that you run, so no AI provider key is ever stored in the app.

## What's in the repo

```
app/                Android app (Kotlin, Jetpack Compose, Material 3, Room, DataStore, Media3)
  src/main/java/com/livingbook/
    model/          Document model: blocks, sections, topics, answers, guided renditions
    data/           Room database, repositories, settings
    doc/            PDF and EPUB parsing, topic segmentation, import pipeline
    session/        Reading session: the read/listen/conversation state machine
    speech/         Text-to-speech narration, background playback service, push-to-talk input
    ai/             Retrieval, prompts, conversation manager, tutor, guided rendition, proxy client
    ui/             Screens (library, reader, listen controls, conversation, settings, learning profile)
backend/            FastAPI proxy that forwards AI requests to Anthropic or any OpenAI-compatible API
samples/            An original sample primer as Markdown, EPUB and PDF for trying the app
```

## Building the app

You need JDK 17 and an Android SDK with platform 36 and build-tools 36.0.0.

On this machine there is no separate JDK or Android Studio, so the build uses the JDK and SDK that ship with Unity:

```bash
export JAVA_HOME="/c/Program Files/Unity/Hub/Editor/6000.5.10f1/Editor/Data/PlaybackEngines/AndroidPlayer/OpenJDK"
```

`local.properties` (not committed) points at the SDK:

```
sdk.dir=C:/Program Files/Unity/Hub/Editor/6000.5.10f1/Editor/Data/PlaybackEngines/AndroidPlayer/SDK
```

With Android Studio installed, delete `local.properties` and let Studio create its own.

Build a debug APK and run the unit tests:

```bash
./gradlew :app:assembleDebug
```

```bash
./gradlew :app:testDebugUnitTest
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk` (or under `LIVINGBOOK_BUILD_ROOT`, see Tests). Install it with `adb install -r` on that path.

Why the versions are pinned: the newest AndroidX releases need compileSdk 37. The Unity SDK has platform 37
but Gradle can't use it without accepting the SDK license, so the project compiles against 36 and caps a few
libraries (see the comment in `gradle/libs.versions.toml`).

## Setting up AI

The app has three AI modes, set in Settings:

- Off: no AI. Reading, narration, bookmarks and notes all work.
- Demo: no network and no model. Questions are answered with the passages from the book that match best, and
  the reply says clearly that it is not AI. Good for trying the flow.
- My proxy: real answers through the backend in `backend/`. The app asks for consent first and explains what is
  sent: your question, the current topic's text, a few matching passages and a short summary of your learning
  profile, but never the whole book.

To run the proxy, see [backend/README.md](backend/README.md). In short:

```bash
cd backend
python -m venv .venv
.venv/Scripts/pip install -r requirements.txt
```

Then set `ANTHROPIC_API_KEY` and `PROXY_TOKEN` (any long random string) and start it on port 8787. In the app,
open Settings, choose "My proxy", enter `http://<your computer's LAN IP>:8787` and the same token, and tap
"Test connection".

The default model is `claude-opus-5-5`. Set `ANTHROPIC_MODEL` to use another one, or set
`LLM_PROVIDER=openai_compatible` to use OpenAI, Ollama or any OpenAI-style endpoint.

## How it works

Import turns a PDF or EPUB into an ordered list of blocks (headings, paragraphs, list
items, tables, equations, figures, captions, footnotes). Each block keeps its index, and its page or EPUB location
when the format has one. Reading progress, bookmarks, citations and conversations all point at block indices, so they
stay attached to the right text when you switch topic size.

The segmenter then groups blocks into topics at all three sizes (bite-sized, balanced,
deep). Headings start topics. Long stretches split at paragraph boundaries near a length target. Captions stay with
their figure and lists stay together. Every block lands in exactly one topic, and tests check this.

`ReadingSession` owns the open book. It is the state machine (reading, listening, conversation,
optional guidance, paused). It runs outside the screens because narration keeps going in the background. Opening the
conversation pauses narration and saves the exact sentence. Closing it goes back to the start of that sentence, and
resumes playback only if it was playing. Lock-screen and headset controls, calls and unplugged headphones all go
through the same state.

Narration uses Android's TextToSpeech, one sentence at a time, inside a Media3 media session. That gives
background playback, lock-screen controls and audio focus handling. Positions are sentence-level. Word-level sync is
not promised.

For each question the app sends the current topic and a few full-text matches from the rest
of the book, labeled by block id. The model replies in JSON segments marked as "from the book" (with citations),
"explanation" or "beyond the book". The app drops any citation the model wasn't shown, and downgrades unsupported
"book" claims to explanations. Tapping a citation jumps to that passage, and "Back to where you were" returns you.

The guided rendition asks the model for a clearer retelling of the topic. Added analogies and examples are marked as
added. Anything shown as a quote must match the source word for word, or it is shown as a restatement. The original
is always one tap away. Renditions are cached per block range.

Commands such as "continue reading", "go back", "skip this topic", "repeat that" and "remember this" are matched
by plain app logic, not the model. A bare "continue" means "continue explaining" when the AI was just explaining.

The learning profile records only explicit evidence: asking for examples or analogies, quiz and teach-back
results. It never infers anything from reading speed or pauses. You can see, edit and delete every entry.

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

About 180 JVM tests (Robolectric where Android is needed) cover parsing and segmentation, the real sample EPUB and
PDF end to end, the session state machine (pause, discuss, resume; read/listen switching; autoplay; guidance;
citation jumps; granularity changes), narration and audio focus, speech input errors, retrieval, prompts, reply
parsing and citation checks, the proxy client, command parsing, and one UI walk-through of the real app: import,
read, next topic, ask (demo mode), back to the book, jump from the table of contents.

The backend has its own tests (`cd backend && .venv/Scripts/python -m pytest`).

`LiveProxyTest` asks a real model through a running proxy. It is skipped unless `LIVE_PROXY_URL` and
`LIVE_PROXY_TOKEN` are set.

If the project sits in OneDrive, set `LIVINGBOOK_BUILD_ROOT` to a local folder (for example
`C:/Users/you/lb-build`). OneDrive locks files under `app/build` and breaks incremental builds.

## Status and limitations

Working, with tests: import, the library, topic reading at three sizes, the table of contents, bookmarks,
highlights and notes, listen mode with background playback, read/listen switching, the conversation sheet
with typed questions, demo mode, grounded answers through the proxy, guided renditions, tutoring suggestions,
the learning profile, settings and privacy controls.

Checked on a Galaxy Z Flip7 (Android 17): import, reading, narration, playback continuing with the screen locked
(including autoplay into the next topic), the lock-screen media bar, media and headset keys, a spoken question
answered in demo mode, and narration resuming at the interrupted sentence after a conversation.

Known limits:

- PDF: layout detection is heuristic. Tables become a "view original page" block rather than a real table.
  Figures are placeholders that link to the page. Scanned PDFs are rejected because there is no OCR. Very large PDFs
  are parsed in memory.
- Equations: kept verbatim, never rewritten, and read aloud as "an equation appears here".
- Selection: "Ask about this" works on a whole paragraph (long-press), not an arbitrary text selection.
- Narration: uses the device's language, not the book's. Rate changes restart the current sentence.
- Voice input: tap to talk. The recognizer decides when you've finished speaking.
- Retrieval: keyword full-text search. Embeddings could replace it later.
- AI: the proxy has one shared token and no per-user accounts or rate limits. The default model is
  `claude-opus-5-5` at low effort. It hasn't been called with a real Anthropic key yet, only mocked and checked
  against a local Ollama model.
