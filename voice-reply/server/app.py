"""VoiceReply backend.

The Android app sends a German audio clip here. This server:
  1. turns the audio into German text      (ElevenLabs speech-to-text)
  2. asks Claude for a short German reply  (Anthropic API)
  3. speaks the reply in your cloned voice (ElevenLabs text-to-speech)
and sends back the text plus an MP3 of the reply.

API keys live only on this server, never inside the APK.
"""

from __future__ import annotations

import base64
import hmac
import json
import logging
import os

import anthropic
from elevenlabs import ElevenLabs
from elevenlabs.core.api_error import ApiError as ElevenLabsError
from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile

log = logging.getLogger("voice-reply")
logging.basicConfig(level=logging.INFO)

# ---- Settings (environment variables) -------------------------------------
ELEVENLABS_VOICE_ID = os.environ.get("ELEVENLABS_VOICE_ID", "")
APP_TOKEN = os.environ.get("APP_TOKEN", "")
CLAUDE_MODEL = os.environ.get("CLAUDE_MODEL", "claude-opus-5-5")
CLAUDE_EFFORT = os.environ.get("CLAUDE_EFFORT", "low")  # low = fastest replies
STT_MODEL = os.environ.get("STT_MODEL", "scribe_v2")
TTS_MODEL = os.environ.get("TTS_MODEL", "eleven_multilingual_v2")

MAX_AUDIO_BYTES = 25 * 1024 * 1024
MAX_HISTORY_MESSAGES = 20

SYSTEM_PROMPT = (
    "Du bist ein freundlicher Gesprächspartner. Der Nutzer spricht Deutsch mit dir, "
    "und deine Antwort wird laut vorgelesen.\n"
    "- Antworte immer auf Deutsch, auch wenn die Eingabe Fehler enthält.\n"
    "- Antworte kurz und natürlich, wie im gesprochenen Gespräch: meist 1 bis 3 Sätze.\n"
    "- Kein Markdown, keine Listen, keine Emojis, keine Sonderzeichen – nur gesprochener Text.\n"
    "- Die Eingabe stammt aus einer automatischen Spracherkennung und kann kleine Fehler "
    "enthalten. Wenn du etwas nicht verstehst, frag kurz nach."
)

# Clients read ANTHROPIC_API_KEY / ELEVENLABS_API_KEY from the environment.
claude = anthropic.Anthropic()
eleven = ElevenLabs(api_key=os.environ.get("ELEVENLABS_API_KEY"))

app = FastAPI(title="VoiceReply")


# ---- Helpers ---------------------------------------------------------------
def check_token(token: str | None) -> None:
    if APP_TOKEN and not hmac.compare_digest(token or "", APP_TOKEN):
        raise HTTPException(status_code=401, detail="Wrong app token.")


def parse_history(raw: str) -> list[dict]:
    """History from the app: [{"role": "user"|"assistant", "text": "..."}, ...]."""
    if not raw:
        return []
    try:
        items = json.loads(raw)
    except json.JSONDecodeError:
        raise HTTPException(status_code=400, detail="history is not valid JSON.")
    if not isinstance(items, list):
        raise HTTPException(status_code=400, detail="history must be a list.")

    messages = []
    for item in items[-MAX_HISTORY_MESSAGES:]:
        if (
            isinstance(item, dict)
            and item.get("role") in ("user", "assistant")
            and isinstance(item.get("text"), str)
            and item["text"].strip()
        ):
            messages.append({"role": item["role"], "content": item["text"]})
    # The conversation sent to Claude must start with the user.
    while messages and messages[0]["role"] != "user":
        messages.pop(0)
    return messages


def transcribe(audio: bytes, filename: str, content_type: str) -> str:
    result = eleven.speech_to_text.convert(
        file=(filename, audio, content_type),
        model_id=STT_MODEL,
        language_code="de",
    )
    return (getattr(result, "text", "") or "").strip()


def ask_claude(history: list[dict], user_text: str) -> str:
    response = claude.beta.messages.create(
        model=CLAUDE_MODEL,
        max_tokens=16000,
        system=SYSTEM_PROMPT,
        output_config={"effort": CLAUDE_EFFORT},
        # If Claude's safety check declines, the API retries on a fallback model.
        betas=["server-side-fallback-2026-07-01"],
        fallbacks="default",
        messages=history + [{"role": "user", "content": user_text}],
    )
    if response.stop_reason == "refusal":
        raise HTTPException(status_code=422, detail="Claude declined to answer this message.")
    reply = " ".join(b.text for b in response.content if b.type == "text").strip()
    if not reply:
        raise HTTPException(status_code=502, detail="Claude returned an empty reply.")
    return reply


def speak(text: str) -> bytes:
    chunks = eleven.text_to_speech.convert(
        ELEVENLABS_VOICE_ID,
        text=text,
        model_id=TTS_MODEL,
        output_format="mp3_44100_128",
    )
    return b"".join(chunks)


# ---- Routes ----------------------------------------------------------------
@app.get("/health")
def health() -> dict:
    return {
        "ok": True,
        "anthropic_key_set": bool(os.environ.get("ANTHROPIC_API_KEY")),
        "elevenlabs_key_set": bool(os.environ.get("ELEVENLABS_API_KEY")),
        "voice_id_set": bool(ELEVENLABS_VOICE_ID),
        "app_token_required": bool(APP_TOKEN),
    }


@app.post("/reply")
def reply(
    audio: UploadFile = File(...),
    history: str = Form(""),
    x_app_token: str | None = Header(default=None),
) -> dict:
    check_token(x_app_token)
    if not ELEVENLABS_VOICE_ID:
        raise HTTPException(status_code=500, detail="ELEVENLABS_VOICE_ID is not set on the server.")

    data = audio.file.read(MAX_AUDIO_BYTES + 1)
    if not data:
        raise HTTPException(status_code=400, detail="The audio file is empty.")
    if len(data) > MAX_AUDIO_BYTES:
        raise HTTPException(status_code=413, detail="The audio file is too big (max 25 MB).")

    messages = parse_history(history)

    try:
        transcript = transcribe(
            data, audio.filename or "audio.m4a", audio.content_type or "application/octet-stream"
        )
        if not transcript:
            raise HTTPException(status_code=422, detail="No speech was found in the audio.")
        answer = ask_claude(messages, transcript)
        mp3 = speak(answer)
    except HTTPException:
        raise
    except ElevenLabsError as e:
        log.warning("ElevenLabs error %s: %s", e.status_code, e.body)
        raise HTTPException(status_code=502, detail=f"ElevenLabs error ({e.status_code}): {e.body}")
    except anthropic.AuthenticationError:
        raise HTTPException(status_code=502, detail="The Anthropic API key on the server is invalid.")
    except anthropic.RateLimitError:
        raise HTTPException(status_code=503, detail="Claude is rate limited. Try again in a minute.")
    except anthropic.APIStatusError as e:
        log.warning("Claude error %s: %s", e.status_code, e.message)
        raise HTTPException(status_code=502, detail=f"Claude error ({e.status_code}): {e.message}")
    except anthropic.APIConnectionError:
        raise HTTPException(status_code=502, detail="The server could not reach Claude.")

    log.info("heard=%r replied=%r", transcript, answer)
    return {
        "transcript": transcript,
        "reply": answer,
        "audio_mime": "audio/mpeg",
        "audio_base64": base64.b64encode(mp3).decode("ascii"),
    }
