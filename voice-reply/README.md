# VoiceReply DE

You speak German, or give it a German audio file. The app answers in German **in your own voice**.

```
Phone app ──audio──▶ Your server ──▶ ElevenLabs: speech → German text
                                 ──▶ Claude:     writes a German reply
                                 ──▶ ElevenLabs: reply → audio in your cloned voice
Phone app ◀──text + MP3─────────┘
```

- `android/`: the Android app (Kotlin)
- `server/`: a small Python server that holds your API keys, so the keys are never inside the APK

---

## Step 1: Get two API keys

1. **Anthropic (Claude):** at <https://console.anthropic.com>, go to **API Keys → Create key** and add some credit.
2. **ElevenLabs:** at <https://elevenlabs.io>, open **Profile → API Keys** and create a key.
   Voice cloning needs a paid plan (Starter or higher).

## Step 2: Clone your voice (one time)

Record **1–3 minutes** of yourself talking clearly in a quiet room (phone voice recorder is fine).
Any language works: the clone can still speak German.

**Option A (website):** ElevenLabs → **Voices → Add a new voice → Instant Voice Clone** → upload the recording.
Then open the voice and **copy its Voice ID**.

**Option B (script):**
```bash
cd server
pip install -r requirements.txt
export ELEVENLABS_API_KEY=your_key
python clone_voice.py my_voice.mp3
# prints: ELEVENLABS_VOICE_ID=xxxxxxxx
```

Only clone your own voice.

## Step 3: Run the server

The server needs these settings (environment variables):

| Name | What |
|---|---|
| `ANTHROPIC_API_KEY` | from Step 1 |
| `ELEVENLABS_API_KEY` | from Step 1 |
| `ELEVENLABS_VOICE_ID` | from Step 2 |
| `APP_TOKEN` | make up a long password; you type the same one in the app |

Optional: `CLAUDE_MODEL` (default `claude-opus-5-5`), `CLAUDE_EFFORT` (default `low` for fast replies),
`STT_MODEL` (default `scribe_v2`), `TTS_MODEL` (default `eleven_multilingual_v2`).

### Option A: on your computer (for testing)
```bash
cd voice-reply/server
python -m venv .venv && source .venv/bin/activate     # Windows: .venv\Scripts\activate
pip install -r requirements.txt
export ANTHROPIC_API_KEY=...  ELEVENLABS_API_KEY=...  ELEVENLABS_VOICE_ID=...  APP_TOKEN=...
uvicorn app:app --host 0.0.0.0 --port 8000
```
Phone and computer must be on the **same Wi-Fi**. In the app, use `http://YOUR-PC-IP:8000`
(find the IP with `ipconfig` on Windows or `ip addr` on Linux/Mac).

### Option B: online, so it works anywhere (e.g. Render)
1. On <https://render.com>: **New → Web Service** → connect this GitHub repo.
2. **Root directory:** `voice-reply/server`. **Runtime:** Docker.
3. Add the 4 environment variables from the table above.
4. Deploy. Your URL looks like `https://something.onrender.com`.

On Render's free plan the server sleeps when unused, so the first message after a break can take about a minute.

## Step 4: Get the APK

Every push to GitHub builds the APK automatically:

1. GitHub repo → **Actions** → **VoiceReply build** → open the newest green run.
2. Under **Artifacts**, download **VoiceReply-apk** (a zip). Unzip it to get `app-debug.apk`.
3. Copy it to your phone and open it. Allow "Install unknown apps" when asked.

(Or open `voice-reply/android` in Android Studio and press Run.)

## Step 5: Use it

1. Open the app → **Settings** → enter the server URL and the app token → **Save and test connection**.
   It should say "Connected. The server is ready."
2. Tap **Tap and speak German**, talk, then tap **Stop and send**.
3. A few seconds later you'll see what it heard, the German reply, and hear the reply in your voice.

You can also tap **Pick audio file**, or **Share** a WhatsApp voice message to *VoiceReply DE*.
**New conversation** clears the chat memory.

## Costs (roughly)

- Claude: a fraction of a cent per reply.
- ElevenLabs: speech-to-text and voice output count against your monthly plan's credits.

## Run the server tests

```bash
cd voice-reply/server
pip install -r requirements.txt pytest httpx
pytest -q
```
The tests use fake ElevenLabs and Claude clients, so no keys are needed.
