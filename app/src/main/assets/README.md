# Mythara wake-word assets

This directory is where the three ONNX files that power the **Lumi**
wake-word listener live. The build doesn't crash without them — the
Settings panel shows a "model files missing" state and the toggle is
inert — but you need them for actual on-device wake detection.

## Files (must match exact filenames)

| Filename                | Size  | Source                                  |
|-------------------------|-------|-----------------------------------------|
| `melspectrogram.onnx`   | ~150K | openWakeWord v0.6.0 release assets      |
| `embedding_model.onnx`  | ~1.5M | openWakeWord v0.6.0 release assets      |
| `lumi.onnx`             | ~50K  | trained yourself (see below)            |

## Getting the shared models

`melspectrogram.onnx` and `embedding_model.onnx` are shared across all
wake-word classifiers — anyone using openWakeWord uses the same
pre-processing front-end. Grab the latest from the
[openWakeWord releases page](https://github.com/dscripka/openWakeWord/releases)
or pull from the `openwakeword/resources/models/` directory of the
upstream pip package (`pip show -f openwakeword`).

## Training `lumi.onnx`

openWakeWord ships a Colab notebook that trains custom wake-word
classifiers using synthetic data — no real recordings of "Lumi"
required. The pipeline:

1. Open the [automatic_model_training.ipynb](https://github.com/dscripka/openWakeWord/blob/main/notebooks/automatic_model_training.ipynb) notebook on Colab.
2. Set `target_phrase = "Lumi"` (capitalisation doesn't matter to the trainer).
3. Run all cells. The notebook generates ~10k synthetic "Lumi" samples
   via TTS, mixes with background noise, and trains a small classifier
   on top of the shared embedding output. Takes ~45 min on a Colab T4.
4. Download the resulting `.onnx` file and rename to `lumi.onnx`.
5. Drop it in this directory next to the two shared models.
6. Rebuild the debug APK (`./gradlew :app:assembleDebug`) and reinstall.

## Verifying

After install, open Mythara → main Settings → "Lumi wake word" panel.
If all three files are present and RECORD_AUDIO is granted, the panel
shows `● listening for 'Lumi'`. Trigger by speaking the wake word —
fires log to `Mythara/Wake` in logcat with a confidence score.

## Why not Picovoice Porcupine?

Porcupine has a slicker integration, but custom phrases require a free
AccessKey from picovoice.ai which the runtime validates against their
service on first start. openWakeWord is fully offline both at training
time (Colab is optional — you can run the notebook locally on any
machine with PyTorch) and at runtime. No cloud round-trips, no soft
dependencies, no AccessKey friction. Matches Mythara's "no Mythara
backend" posture.
