# VoiceReply DE

Speak German to your phone and it answers in German, out loud.
**No accounts, no API keys, no server.** Everything runs on the phone.

```
You speak German
  → phone's speech recognition turns it into text   (Android, built in)
  → offline AI on the phone writes a German reply   (llama.cpp + open model)
  → phone's German voice reads the reply aloud      (Android text-to-speech)
```

## Install

Download the APK on your phone:
**https://github.com/nikhiltom369/DiceRoll/releases/download/voice-reply-latest/VoiceReply-DE.apk**

Open it and allow "Install unknown apps" if Android asks.

## First start (one time)

1. Tap **Download offline AI**. It downloads an open AI model (Gemma 3 1B, about 800 MB)
   for free, without any login. Wi-Fi is best. You can leave the app while it downloads.
2. Allow the microphone when asked.
3. If the app shows **Install a German voice for replies**, tap it and download the German voice.

After that: tap **Tap and speak German**, talk, and the reply plays automatically.
**New conversation** makes the AI forget the chat. **Say it again** repeats the last reply.

## Good to know

- The replies come from a small AI on your phone. It writes simple German and can make
  mistakes; it is much weaker than big online AIs.
- The voice is your phone's German voice, not yours.
- Speech recognition uses your phone's Google speech service. It usually needs internet;
  for fully offline use, download "German" in your phone's Google voice typing / offline
  speech settings.
- Needs a 64-bit ARM phone (practically every phone from the last years) with about
  1 GB free storage and 3 GB+ RAM.

## How it is built

- `android/app/src/main/java/...`: the app (Kotlin): speech recognition, model download, text-to-speech
- `android/app/src/main/cpp/`: the chat engine on [llama.cpp](https://github.com/ggml-org/llama.cpp)
  (release b12627), used by the app through JNI
- `.github/workflows/voice-reply.yml`: builds the APK and publishes it at the link above
- `.github/workflows/llm-eval.yml`: builds the same chat engine on a PC, downloads the model
  and checks that it holds a German conversation

To build yourself: open `voice-reply/android` in Android Studio (with the NDK installed) and press Run.
