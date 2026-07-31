#!/usr/bin/env bash
# 정적 세그먼트 스텁 생성 (M1 1-2) — testsrc 5분을 계약3 규격(fMP4 CMAF·세그먼트 4s·GOP 2s)으로 사전 생성
# 실행: ./gen-segments.sh   → data/live/stub/ 에 index.m3u8(master) + chunks.m3u8 + init.mp4 + seg_*.m4s
# 생성물은 gitignore — 각자 로컬에서 1회 실행 후 docker compose up
set -euo pipefail

cd "$(dirname "$0")"
OUT="data/live/stub"

command -v ffmpeg >/dev/null || { echo "ffmpeg가 필요합니다: brew install ffmpeg"; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT"

# GOP 2초(30fps -g 60, ADR-020) · 세그먼트 4s(-hls_time 4) · fMP4(CMAF)
ffmpeg -y -loglevel error \
  -f lavfi -i "testsrc2=size=1280x720:rate=30" \
  -f lavfi -i "sine=frequency=440:sample_rate=48000" \
  -t 300 \
  -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 60 -keyint_min 60 -sc_threshold 0 \
  -c:a aac -b:a 128k \
  -f hls -hls_time 4 -hls_playlist_type vod -hls_segment_type fmp4 \
  -hls_flags independent_segments \
  -hls_fmp4_init_filename init.mp4 \
  -hls_segment_filename "$OUT/seg_%05d.m4s" \
  -master_pl_name index.m3u8 \
  "$OUT/chunks.m3u8"

echo "생성 완료: $OUT ($(ls "$OUT" | wc -l | tr -d ' ')개 파일)"
echo "확인: docker compose up -d media-stub 후 curl http://localhost:8080/live/stub/index.m3u8"
