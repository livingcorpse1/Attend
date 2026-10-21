#!/usr/bin/env bash
#
# Downloads the ONNX models used by the VeriQra biometric pipeline.
#
#   * face_detection_yunet_2023mar.onnx   - face detection + 5 point landmarks
#   * face_recognition_sface_2021dec.onnx - 128 dimensional face embedding
#
# Both are published by the OpenCV model zoo (Apache-2.0). SHA-256 digests are
# verified so a corrupted or substituted file cannot reach the inference
# pipeline.
#
# Usage:  ./scripts/fetch-models.sh [target-directory]

set -euo pipefail

TARGET_DIR="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/models}"
mkdir -p "$TARGET_DIR"

download() {
    local name="$1" url="$2" expected="$3"
    local dest="$TARGET_DIR/$name"

    if [[ -f "$dest" ]]; then
        local actual
        actual="$(sha256sum "$dest" | cut -d' ' -f1)"
        if [[ "$actual" == "$expected" ]]; then
            echo "SKIP  $name already present and verified"
            return
        fi
        echo "STALE $name checksum mismatch, re-downloading"
        rm -f "$dest"
    fi

    echo "GET   $name"
    curl -fsSL --retry 3 --retry-delay 2 -o "$dest.part" "$url"
    local actual
    actual="$(sha256sum "$dest.part" | cut -d' ' -f1)"
    if [[ "$actual" != "$expected" ]]; then
        rm -f "$dest.part"
        echo "FAIL  $name checksum mismatch: expected $expected got $actual" >&2
        exit 1
    fi
    mv "$dest.part" "$dest"
    echo "OK    $name"
}

download "face_detection_yunet_2023mar.onnx" \
    "https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx" \
    "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"

download "face_recognition_sface_2021dec.onnx" \
    "https://github.com/opencv/opencv_zoo/raw/main/models/face_recognition_sface/face_recognition_sface_2021dec.onnx" \
    "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79"

echo ""
echo "Models ready in $TARGET_DIR"
echo "Set VERIQRA_MODELS_DIR if you want to keep them elsewhere."
