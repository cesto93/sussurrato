# Sussurrato

On-device audio transcription for Android. Supports two backends:

- **[LiteRT-LM](https://ai.google.dev/edge/litert-lm)** — Google's on-device ML engine (`.litertlm` models)
- **[llama.cpp](https://github.com/ggml-ai/llama.cpp) + Qwen3-ASR** — via a native JNI bridge (`.gguf` models)

## Requirements

- Android 9+ (API 28)
- A model file — either a `.litertlm` file or a `.gguf` decoder + mmproj pair

## Setup

The app can download models from Hugging Face directly. Supported models:

| Model | Type | Size |
|-------|------|------|
| [Qwen3-ASR 1.7B](https://huggingface.co/ggml-org/Qwen3-ASR-1.7B-GGUF) | GGUF (Q4_K_M) | 1.8 GB + 30 MB mmproj |
| [Gemma 4 E4B](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm) | LiteRT-LM | 3.7 GB |
| [Gemma 4 E2B](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) | LiteRT-LM | 2.6 GB |

You can also place model files manually in the app's files directory (`/sdcard/Android/data/com.pierfrancescocontino.sussurrato/files/`) or in `/sdcard/`. The app picks the most recently modified `.litertlm` or `.gguf` file automatically. GGUF models require both the decoder and mmproj files.

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
2. Select a model (Qwen3-ASR, Gemma 4 E4B, or E2B) and tap **Download**:
   - For GGUF models, the decoder downloads first, then the mmproj file. The model loads automatically once both are ready.
   - For LiteRT-LM models, a single file downloads and loads on completion.
3. Tap the file picker card to select an audio file (MP3, WAV, M4A, OGG).
4. Tap **Transcribe** — the audio is decoded to 16 kHz mono PCM F32 and sent to the selected backend.
5. The transcribed text appears in the output card.

## Architecture

- **`TranscriptionViewModel`** — Manages dual-engine lifecycle: routes `.gguf` files to the llama.cpp `AsrEngine` and `.litertlm` files to LiteRT-LM `Engine`. Handles model downloading (decoder + optional mmproj), progress reporting, cancellation, and transcription state via `StateFlow`.
- **`llama` module** — A standalone Android library that builds `libsussurrato-llama.so` via CMake with llama.cpp's mtmd (multimodal) pipeline. The JNI bridge (`qwen3_asr.cpp`) feeds PCM F32 audio directly to the Qwen3-ASR encoder and returns transcribed text.
- **GGUF metadata reader** — Pure-Kotlin binary parser for GGUF files (architecture, tokenizer, quantization type, tensor info). Useful for model introspection without loading the full model.
- **`MainActivity.kt`** — Compose UI with file picker, model download cards, language selector, loading indicator, error display, and transcription output.
- **Audio pipeline** — Decodes any audio to 16 kHz mono PCM F32 via `MediaExtractor`/`MediaCodec` with sample rate conversion and channel downmix.
- **Model download** — Uses Android's `DownloadManager` system service. Polls progress every 500ms. On completion, moves files from external storage to internal `filesDir`.

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

### llama.cpp

This application uses [llama.cpp](https://github.com/ggml-ai/llama.cpp), which is licensed under the MIT License.

Copyright (c) 2023-2025 The llama.cpp authors

### Qwen3-ASR

This application supports the [Qwen3-ASR model](https://huggingface.co/Qwen/Qwen3-ASR-1.7B), which is made available under the [Apache License, Version 2.0](https://huggingface.co/datasets/choosealicense/licenses/blob/main/markdown/apache-2.0.md).
