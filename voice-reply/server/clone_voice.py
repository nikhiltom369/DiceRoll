"""Create a clone of YOUR voice on ElevenLabs and print its voice ID.

Usage:
    export ELEVENLABS_API_KEY=...
    python clone_voice.py my_voice_1.mp3 [my_voice_2.mp3 ...]

Tips: record 1-3 minutes of clear speech in a quiet room, no music.
You can talk in any language; the clone can still speak German.
Only clone your own voice.
"""

import os
import sys

from elevenlabs import ElevenLabs


def main() -> None:
    paths = sys.argv[1:]
    if not paths:
        sys.exit(__doc__)

    client = ElevenLabs(api_key=os.environ.get("ELEVENLABS_API_KEY"))
    files = [open(p, "rb") for p in paths]
    try:
        voice = client.voices.ivc.create(
            name="My voice (VoiceReply)",
            files=files,
            remove_background_noise=True,
        )
    finally:
        for f in files:
            f.close()

    print("Voice created. Put this in the server settings:")
    print(f"ELEVENLABS_VOICE_ID={voice.voice_id}")


if __name__ == "__main__":
    main()
