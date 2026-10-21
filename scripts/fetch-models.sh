#!/usr/bin/env bash
#
# Downloads the ONNX models used by the VeriQra biometric pipeline.
#
# Two pairs of models are supported:
#
#   The permissive pair, which is the default:
#     * face_detection_yunet_2026may.onnx   - face detection + 5 point landmarks
#     * face_recognition_sface_2021dec.onnx - 128 dimensional face embedding
#   Both come from the OpenCV model zoo, under the MIT and the Apache-2.0 licence.
#
#   The strongest pair:
#     * scrfd_10g.onnx         - face detection + 5 point landmarks
#     * arcface_w600k_r50.onnx - 512 dimensional face embedding
#   Both come from the InsightFace model family, whose weights are published for
#   non commercial research.
#
# SHA-256 digests are verified so a corrupted or substituted file cannot reach the
# inference pipeline.
#
# Usage:  ./scripts/fetch-models.sh [target-directory] [--strong]

set -euo pipefail

TARGET_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/models"
STRONG=0

for argument in "$@"; do
    case "$argument" in
        --strong) STRONG=1 ;;
        *) TARGET_DIR="$argument" ;;
    esac
done

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

if [[ "$STRONG" == "1" ]]; then
    echo "The models of the InsightFace family are published for non commercial research."
    echo "A deployment that is not a college project has to settle that question first."
    echo ""

    download "scrfd_10g.onnx" \
        "https://huggingface.co/globalnebula/insightface-buffalo-l-onnx/resolve/main/scrfd_10g.onnx" \
        "5838f7fe053675b1c7a08b633df49e7af5495cee0493c7dcf6697200b85b5b91"

    download "arcface_w600k_r50.onnx" \
        "https://huggingface.co/globalnebula/insightface-buffalo-l-onnx/resolve/main/arcface_w600k_r50.onnx" \
        "4c06341c33c2ca1f86781dab0e829f88ad5b64be9fba56e56bc9ebdefc619e43"
else
    download "face_detection_yunet_2026may.onnx" \
        "https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2026may.onnx" \
        "ebafce4e3c118d6554634be5c27ab333b4c047a9a8c3faf1d7cf93101c22f0f0"

    download "face_recognition_sface_2021dec.onnx" \
        "https://github.com/opencv/opencv_zoo/raw/main/models/face_recognition_sface/face_recognition_sface_2021dec.onnx" \
        "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79"
fi

echo ""
echo "Models ready in $TARGET_DIR"
if [[ "$STRONG" == "1" ]]; then
    echo "Set BIOMETRIC_PROFILE=scrfd-arcface in the .env file to use them."
    echo "Students have to run the identity check again, because the descriptors changed."
else
    echo "Set VERIQRA_MODELS_DIR if you want to keep them elsewhere."
fi
