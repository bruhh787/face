# FaceSort (Android, on-device)

Sorts videos on the phone into one folder per person, using face recognition the user teaches
(by naming auto-found groups, or with "Add person from photo" using their own photos).
Starts with ZERO known people; no face database/gallery of people is bundled or downloaded.

## Build
1. `bash scripts/fetch_model.sh` – downloads InsightFace `buffalo_l.zip` from a pinned GitHub release URL,
   verifies sha256 of the zip and of `w600k_r50.onnx`, installs it as `app/src/main/assets/arcface_w600k_r50.onnx`
   (git-ignored, 174 MB). Gradle fails with a clear message if it is missing.
2. `./gradlew :app:testDebugUnitTest :app:assembleDebug` (needs JDK 17 + Android SDK 35; `local.properties`
   with `sdk.dir=...` or ANDROID_HOME).

GitHub Actions (`.github/workflows/build.yml`) does the same on every push / manual run, caches the model
download, fails if any checksum differs, and uploads the APK as artifact `FaceSort-debug-apk`.

## Pipeline
MediaStore videos -> frames via MediaMetadataRetriever (rotation-aware, <=1280 px; scan modes
Fast/Balanced/Thorough = every 4/2/1 s, max 100/200/400 frames, Thorough uses exact seeking) -> skip repeated
frames (16x16 greyscale mean diff <= 1) -> ML Kit face detection (bundled, landmarks, min face 5%) ->
least-squares similarity alignment of eyes + mouth corners onto the 112x112 ArcFace template ->
ArcFace w600k_r50 (ONNX Runtime CPU, RGB NCHW (x-127.5)/127.5, original+mirror averaged, L2-normalised) ->
per-video condensing (best 6 faces per person-in-video, weighted by frame count) -> match to named people
(mean of top-3 cosine vs exemplars incl. user photos) -> cluster the rest -> dominant person per video, else Review.
Organise: MediaStore.createWriteRequest consent, then RELATIVE_PATH update to Movies/FaceSort/<Name>/.

## Thresholds (ArcFace cosine similarity)
- Grouping 0.48: different people rarely exceed ~0.3 with ArcFace R50, the same person across shoots is usually
  0.5-0.8; 0.48 favours never merging two people (splits are fixed with Merge).
- Matching 0.42: compared against many exemplars (mean of top-3, plus a 0.04 margin over the runner-up), which is
  more robust than one centroid, so it can be a bit looser to catch harder frames.
Both are adjustable in Settings (0.25-0.75).

## Model licence
InsightFace code is MIT, but the pretrained models (incl. buffalo_l / w600k_r50) are for NON-COMMERCIAL
RESEARCH purposes only. ML Kit face detection is under Google's ML Kit terms.
