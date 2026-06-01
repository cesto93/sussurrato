# Sussurrato

On-device audio transcription for Android using [LiteRT-LM](https://ai.google.dev/edge/litert-lm) with Jetpack Compose and Material 3.

## Requirements

- Android 9+ (API 28)
- A multi-modal `.litertlm` model file that supports audio

## Setup

The app can download models from Hugging Face directly. Supported models:

| Model | Size |
|-------|------|
| [Gemma 4 E4B](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm) | 3.7 GB |
| [Gemma 4 E2B](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) | 2.6 GB |

You can also place a `.litertlm` file manually in the app's files directory (`/sdcard/Android/data/com.pierfrancescocontino.sussurrato/files/`) or in `/sdcard/`. The app picks the most recently modified `.litertlm` file automatically.

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

1. Open the app — if no model is found, you'll be prompted to download one.
2. Select a model (Gemma 4 E4B or E2B) and tap **Download** — the model loads automatically once downloaded.
3. Tap the file picker card to select an audio file (MP3, WAV, M4A, OGG).
4. Tap **Transcribe** — the audio is decoded to 16 kHz mono PCM and sent to the model.
5. The transcribed text appears in the output card.

## Architecture

- `TranscriptionViewModel` — Manages the LiteRT-LM `Engine` lifecycle, model downloading (with progress/cancel), and transcription state via `StateFlow`.
- `MainActivity.kt` (includes `TranscriptionScreen` composable) — UI with file picker, model download cards, loading indicator, error display, and transcription output.
- Audio decoding to 16 kHz mono PCM via `MediaExtractor` / `MediaCodec` with sample rate conversion and channel downmix.

## License & Legal

### LiteRT

This application uses [LiteRT](https://ai.google.dev/edge/litert-lm), which is licensed under the Apache License, Version 2.0:

```
Copyright 2024 Google LLC. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

### Gemma

This application includes or uses the Gemma 4 model, which is made available by Google under the [Apache License, Version 2.0](https://ai.google.dev/gemma/apache_2).
