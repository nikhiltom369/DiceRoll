"""Tests with fake ElevenLabs/Claude clients - no API keys or network needed.

Run:  pip install pytest httpx && pytest -q
"""

import base64
import json
import os
from types import SimpleNamespace

os.environ.setdefault("ANTHROPIC_API_KEY", "test")
os.environ.setdefault("ELEVENLABS_API_KEY", "test")
os.environ["ELEVENLABS_VOICE_ID"] = "voice123"
os.environ["APP_TOKEN"] = "secret"

import pytest
from fastapi.testclient import TestClient

import app as server


class FakeEleven:
    def __init__(self, transcript="Wie geht es dir?"):
        self.stt_calls, self.tts_calls = [], []
        self.speech_to_text = SimpleNamespace(convert=self._stt)
        self.text_to_speech = SimpleNamespace(convert=self._tts)
        self.transcript = transcript

    def _stt(self, **kw):
        self.stt_calls.append(kw)
        return SimpleNamespace(text=self.transcript, language_code="deu")

    def _tts(self, voice_id, **kw):
        self.tts_calls.append((voice_id, kw))
        return iter([b"ID3", b"fake-mp3"])


class FakeClaude:
    def __init__(self, stop_reason="end_turn"):
        self.calls = []
        self.stop_reason = stop_reason
        self.beta = SimpleNamespace(messages=SimpleNamespace(create=self._create))

    def _create(self, **kw):
        self.calls.append(kw)
        return SimpleNamespace(
            stop_reason=self.stop_reason,
            content=[
                SimpleNamespace(type="thinking", thinking=""),
                SimpleNamespace(type="text", text="Mir geht es gut, danke!"),
            ],
        )


@pytest.fixture
def fakes(monkeypatch):
    eleven, claude = FakeEleven(), FakeClaude()
    monkeypatch.setattr(server, "eleven", eleven)
    monkeypatch.setattr(server, "claude", claude)
    return eleven, claude


client = TestClient(server.app)
AUTH = {"X-App-Token": "secret"}


def post(files=None, data=None, headers=AUTH):
    files = files or {"audio": ("rec.m4a", b"\x00\x01audio", "audio/mp4")}
    return client.post("/reply", files=files, data=data or {}, headers=headers)


def test_full_round_trip(fakes):
    eleven, claude = fakes
    history = [
        {"role": "user", "text": "Hallo"},
        {"role": "assistant", "text": "Hallo! Wie kann ich helfen?"},
    ]
    r = post(data={"history": json.dumps(history)})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["transcript"] == "Wie geht es dir?"
    assert body["reply"] == "Mir geht es gut, danke!"
    assert base64.b64decode(body["audio_base64"]) == b"ID3fake-mp3"

    stt = eleven.stt_calls[0]
    assert stt["file"] == ("rec.m4a", b"\x00\x01audio", "audio/mp4")
    assert stt["language_code"] == "de"

    sent = claude.calls[0]
    assert sent["model"] == "claude-opus-5-5"
    assert sent["messages"] == [
        {"role": "user", "content": "Hallo"},
        {"role": "assistant", "content": "Hallo! Wie kann ich helfen?"},
        {"role": "user", "content": "Wie geht es dir?"},
    ]
    assert sent["fallbacks"] == "default"

    voice_id, tts = eleven.tts_calls[0]
    assert voice_id == "voice123"
    assert tts["text"] == "Mir geht es gut, danke!"


def test_wrong_token_rejected(fakes):
    assert post(headers={"X-App-Token": "nope"}).status_code == 401
    assert post(headers={}).status_code == 401


def test_empty_audio_rejected(fakes):
    r = post(files={"audio": ("rec.m4a", b"", "audio/mp4")})
    assert r.status_code == 400


def test_no_speech(fakes):
    fakes[0].transcript = "   "
    r = post()
    assert r.status_code == 422
    assert fakes[1].calls == []  # Claude is not called


def test_refusal_is_reported(monkeypatch, fakes):
    monkeypatch.setattr(server, "claude", FakeClaude(stop_reason="refusal"))
    assert post().status_code == 422


def test_history_cleanup():
    raw = json.dumps(
        [
            {"role": "assistant", "text": "dropped: history must start with user"},
            {"role": "system", "text": "dropped: bad role"},
            {"role": "user", "text": "  "},
            {"role": "user", "text": "Hallo"},
            {"role": "assistant", "text": 5},
        ]
    )
    assert server.parse_history(raw) == [{"role": "user", "content": "Hallo"}]


def test_bad_history_json(fakes):
    assert post(data={"history": "{not json"}).status_code == 400


def test_health():
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json()["voice_id_set"] is True
