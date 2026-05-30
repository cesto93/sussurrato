# Sussurrato

On-device audio transcription for Android using [LiteRT-LM](https://ai.google.dev/edge/litert-lm) with Jetpack Compose and Material 3.

## Requirements

- Android 9+ (API 28)
- A multi-modal `.litertlm` model file that supports audio (e.g., [Gemma3n](https://huggingface.co/litert-community/Gemma3n-1B-IT))

## Setup

Place a `.litertlm` model file in the app's files directory (`/sdcard/Android/data/com.pierfrancescocontino.sussurrato/files/`) or in `/sdcard/`. The app picks the most recently modified `.litertlm` file automatically.

## Build & run

```bash
make build     # assemble debug APK
make install   # build + install via Waydroid
make run       # build + install + launch
```

Or with Gradle directly:

```bash
./gradlew :app:assembleDebug
```

## Usage

1. Open the app — the model loads automatically on launch.
2. Tap the file picker card to select an audio file (MP3, WAV, M4A, OGG).
3. Tap **Transcribe** — the audio is decoded to PCM and sent to the model.
4. The transcribed text appears in the output card.

## Architecture

- `TranscriptionViewModel` — Manages the LiteRT-LM `Engine` lifecycle and transcription state via `StateFlow`.
- `TranscriptionScreen` — Composable UI with file picker, model loading indicator, error display, and transcription output.
- Audio decoding to 16 kHz mono PCM via `MediaExtractor` / `MediaCodec`.
