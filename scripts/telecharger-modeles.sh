#!/usr/bin/env bash
# Télécharge les modèles et bibliothèques de reconnaissance (visage, voix) avant la compilation.
# Chaque fichier est vérifié (empreinte SHA-256) : si l'empreinte ne correspond pas, la compilation s'arrête.
set -euo pipefail
cd "$(dirname "$0")/.."

ASSETS=app/src/main/assets
JNI=app/src/main/jniLibs/arm64-v8a
TMP=$(mktemp -d)
mkdir -p "$ASSETS" "$JNI"

check() { echo "$2  $1" | sha256sum -c - >/dev/null 2>&1; }

fetch() { # url fichier sha256
  local url=$1 out=$2 sha=$3
  if [ -f "$out" ] && check "$out" "$sha"; then return 0; fi
  echo "Téléchargement de $(basename "$out")"
  curl -fsSL --retry 5 --retry-delay 3 -o "$out" "$url"
  check "$out" "$sha" || { echo "Empreinte incorrecte pour $out" >&2; exit 1; }
}

# Visage : MobileFaceNet (licence MIT, syaringan357)
fetch "https://github.com/syaringan357/Android-MobileFaceNet-MTCNN-FaceAntiSpoofing/raw/d635649e711f5e16e6cd3dc06cbb329cd8d1d3b7/app/src/main/assets/MobileFaceNet.tflite" \
  "$ASSETS/MobileFaceNet.tflite" d8ba40c0127fb8ca9917e8fddc79bbbda063657bc92a496d34da0bc8a760443b

# Voix : modèle WeSpeaker (VoxCeleb, ResNet34) via sherpa-onnx
fetch "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/wespeaker_en_voxceleb_resnet34.onnx" \
  "$ASSETS/wespeaker_en_voxceleb_resnet34.onnx" 5ef208a9da1453335308a6b6f4e6dfbd7e183a38b604de0a57664f45d257fe94

# Voix : bibliothèques natives (arm64, sherpa-onnx 1.13.8)
if ! { [ -f "$JNI/libonnxruntime.so" ] && check "$JNI/libonnxruntime.so" 33847ad43bffe204699fd4a27f7f3603452a8cdaf2f9a44983a0bc31ffcf2da1 \
     && [ -f "$JNI/libsherpa-onnx-jni.so" ] && check "$JNI/libsherpa-onnx-jni.so" 3c6492ea91ea68fd3b72b9495efdde6dd913f6b58c387e3b07389cf335f4d26f; }; then
  fetch "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-android.tar.bz2" \
    "$TMP/sherpa.tar.bz2" 2ff63469a71cb6009aa2e3ed5f4a670f8abdcbe4bb9ffd23776afc792a6b4f44
  tar -xjf "$TMP/sherpa.tar.bz2" -C "$TMP" jniLibs/arm64-v8a/libonnxruntime.so jniLibs/arm64-v8a/libsherpa-onnx-jni.so
  cp "$TMP/jniLibs/arm64-v8a/libonnxruntime.so" "$TMP/jniLibs/arm64-v8a/libsherpa-onnx-jni.so" "$JNI/"
  check "$JNI/libonnxruntime.so" 33847ad43bffe204699fd4a27f7f3603452a8cdaf2f9a44983a0bc31ffcf2da1
  check "$JNI/libsherpa-onnx-jni.so" 3c6492ea91ea68fd3b72b9495efdde6dd913f6b58c387e3b07389cf335f4d26f
fi
rm -rf "$TMP"
echo "Modèles prêts."
