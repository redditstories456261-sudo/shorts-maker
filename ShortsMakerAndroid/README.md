# Shorts Maker (Android)

A native Android app that takes a long video (a local file, or a pasted YouTube-style link) and
produces short, 9:16 vertical clips: on-device transcription, automatic "best moment" detection,
face-tracked reframing (with split-screen for multi-person scenes), word-by-word karaoke captions,
and a title + hook + virality score per clip. Everything runs **on the phone** - internet is only
used to download a pasted video link.

This is source code for Android Studio, **not a ready-made APK** - there's no Android build
toolchain available in the environment that generated this project, so it couldn't be compiled or
tested here. You (or any Android developer) need to open it in Android Studio, do the one-time
native setup below, and build it. Expect to spend a little time fixing small build issues - this
is a real, fairly advanced native-Android project (on-device speech recognition + computer vision +
video encoding), not a trivial app.

> **No PC/laptop at all?** See `GITHUB_BUILD.md` instead - it builds the APK on GitHub's free
> cloud servers, triggered entirely from your phone (via a one-time Termux setup to upload the
> code). The in-app "Import model" buttons (see the app's main screen) also let you load the
> Whisper/face model files from your phone's Downloads with no `adb`/PC needed.

## What you need

- **Android Studio** (free, latest stable) - https://developer.android.com/studio
- A phone or emulator with **Android 8.0 (API 26) or newer**, arm64 (virtually all phones sold
  since ~2017)
- About **15-20 minutes** for the one-time native setup below (downloading whisper.cpp source +
  the AI model files)

## One-time setup (do this before building)

### 1. Open the project
Android Studio -> Open -> select this `ShortsMakerAndroid` folder. Let it sync Gradle once (it
will show errors until steps 2-4 below are done - that's expected).

### 2. Vendor whisper.cpp (on-device speech-to-text engine)
This project builds whisper.cpp from source via CMake; the source itself isn't bundled here
(it's a large third-party C/C++ project). From a terminal in the project root:
```
git submodule add https://github.com/ggerganov/whisper.cpp app/src/main/cpp/whisper.cpp
git submodule update --init --recursive
```
If a function name in `app/src/main/cpp/jni_bridge.cpp` doesn't match the version you pull (the
whisper.cpp API occasionally changes), compare against
`whisper.cpp/examples/whisper.android` in the same repo and adjust - the core functions used here
(`whisper_init_from_file_with_params`, `whisper_full`, `whisper_full_get_segment_text`, etc.) have
been stable for a long time, so this is usually a non-issue.

### 3. Download a Whisper model (speech-to-text brain)
Pick ONE multilingual GGML model from
https://huggingface.co/ggerganov/whisper.cpp/tree/main (these officially support English, Urdu,
Hindi and ~95 other languages - do not pick an "...-en" file, those are English-only):

| Model | File | Size | Speed on phone | Accuracy |
|---|---|---|---|---|
| tiny | `ggml-tiny.bin` | ~75 MB | fastest | lowest - OK for English, rough for Urdu/Hindi |
| **base** (recommended) | `ggml-base.bin` | ~142 MB | good | solid for all 3 languages |
| small | `ggml-small.bin` | ~466 MB | slower | noticeably better for Urdu/Hindi |

Push it to the device at first run, e.g. with the app installed once:
```
adb push ggml-base.bin /sdcard/Android/data/com.shortsmaker.app/files/
```
then, in the app's "files" folder, move/rename it to `models/ggml-base.bin` (or change the path
MainActivity looks for, near `File(context.filesDir, "models/ggml-base.bin")`, to match whichever
model you downloaded). A later version of this app should add an in-app "download model" button
using the same `INTERNET` permission already declared - this first version keeps it a manual copy
to stay simple.

### 4. Fonts for Urdu / Hindi captions
Android's default font doesn't render Arabic/Devanagari script well for burned-in captions.
Download these (free, Google's Noto fonts) and place them in `app/src/main/assets/fonts/`:
- Urdu: "Noto Nastaliq Urdu" - https://fonts.google.com/noto/specimen/Noto+Nastaliq+Urdu
- Hindi: "Noto Sans Devanagari" - https://fonts.google.com/noto/specimen/Noto+Sans+Devanagari
- English: "Noto Sans" - https://fonts.google.com/noto/specimen/Noto+Sans

Then set the exact font family name (as installed) in `CaptionBuilder.build(... fontName = ...)`
per language, or just pick one that covers all three reasonably (Noto Sans Nastaliq covers Arabic
script; for mixed-language videos you may need per-script styles - start simple with one language
at a time).

### 5. Face detection models (MediaPipe)
Download these two `.task` files and place them in `app/src/main/assets/`:
- `face_detector.task` - https://ai.google.dev/edge/mediapipe/solutions/vision/face_detector
- `face_landmarker.task` - https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker

(Both pages link directly to the model file under "Models" - grab the default/float16 variant.)

### 6. Build
Android Studio -> **Build > Make Project**, then run on a connected phone (an emulator will be
very slow for the Whisper/face-tracking steps - a real phone is strongly recommended).

## Using the app
1. Paste a video link (or use Android's Share menu from the YouTube app -> "Shorts Maker"), or tap
   "Pick a video file instead" to choose a file already on the phone.
2. Choose how many clips and which language (or leave on Auto).
3. Tap **Make shorts**. This can take several minutes per clip on a mid-range phone - on-device AI
   is much slower than a desktop GPU.
4. Each finished clip shows a title, hook line, and virality score, with **Share** / **Play**
   buttons.

## Honest limitations (please read)
- **Quality vs. the Windows/desktop version**: on-device `tiny`/`base` Whisper models are smaller
  and less accurate than the desktop pipeline's `large-v3`, especially for Urdu/Hindi. `small` is
  a good middle ground if your phone can handle the extra time.
- **Word-level caption timing**: whisper.cpp reports per-token (not always per-whole-word,
  especially in Urdu/Hindi) timestamps. Captions will look right most of the time but check your
  own content - bump the model size if timing looks consistently off.
- **Split-screen panels** use one crop position per segment rather than continuously panning each
  panel individually (the single-speaker view still pans smoothly) - a deliberate simplification
  to keep the video-render step reliable.
- **Downloading videos**: only do this for content you have the rights to repurpose. Automated
  downloading of other people's videos without permission can violate that platform's Terms of
  Service and copyright law.
- **No paid API required** for the core pipeline (download, transcribe, detect, reframe, caption,
  render all run free, on-device). The title/hook/score step works fine with the free built-in
  heuristic; an API key (OpenAI/Anthropic/any OpenAI-compatible server) is optional and only
  improves title wording.

## Project layout
```
app/src/main/java/com/shortsmaker/app/
  MainActivity.kt            UI: pick input, run, show results
  ShortsMakerApp.kt           Application class (inits yt-dlp)
  model/Models.kt             shared data classes
  pipeline/
    Lexicon.kt                EN/UR/HI cue-word lists + text normalization
    MomentDetector.kt         scoring + picking the best clip windows
    WhisperBridge.kt          Kotlin side of the whisper.cpp JNI bridge
    FaceTracker.kt            MediaPipe face detection + simple multi-face tracker
    ReframePlanner.kt         smoothed virtual-camera path, single vs split-screen
    CaptionBuilder.kt         karaoke-style .ass caption generation
    Renderer.kt                FFmpeg filter graph + encode
    Downloader.kt              yt-dlp-android wrapper
    TitleScorer.kt             heuristic + optional pluggable LLM
    ShortsPipeline.kt           orchestrates all of the above
app/src/main/cpp/
  CMakeLists.txt, jni_bridge.cpp   whisper.cpp native bridge (vendor whisper.cpp here, see step 2)
```
