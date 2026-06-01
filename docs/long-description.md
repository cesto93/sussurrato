Sussurrato is a privacy-first, on-device AI transcription app for Android. It runs entirely on your device using Google AI Edge LiteRT LM and Gemma 4 models, with no internet connection required after the initial model download.

Key features:

- On-device transcription powered by Gemma 4 LLMs (E2B and E4B), downloaded directly from Hugging Face.
- Support for custom .litertlm model files via URL or manual placement on the device.
- Language selection: Italian, English, French, Spanish, and German, with auto-detect option.
- Audio file input via the system file picker or by sharing audio files from other apps.
- Decodes common audio formats (MP3, AAC, FLAC, OGG, WAV, etc.) using Android's MediaCodec API, resamples to 16 kHz mono 32-bit float PCM for the model.
- Clean Material 3 UI built with Jetpack Compose.
- ViewModel-driven architecture with reactive state flows for model loading, download progress, and transcription status.
- Integrated download manager with progress tracking, error handling, retry, and cancellation.
- Model switching at runtime without restarting the app.
- GPL-3.0 licensed open source.
