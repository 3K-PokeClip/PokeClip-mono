#!/usr/bin/env bash
# 편집기용 로컬 소스 생성 (POK-238) — 로컬 녹화 한 구간을 운영과 같은 형태로 잘라
# 정적 스텁(media-stub)에 올린다. 클립 편집기가 미디어 없이 목업으로만 서 있는 동안,
# 서버의 영상 출입증(POK-122)을 기다리지 않고 재생 배선을 먼저 끝내기 위한 발판이다.
#
# 값의 정본은 ADR-020(세그먼트 4초·GOP 2초)과 계약3이다 — 여기 적힌 4/2는 그 사본이다.
#
# 실행: ./gen-editor-source.sh <입력파일> [옵션]
#       ./gen-editor-source.sh ~/Downloads/녹화.mov --start 00:20:00 --duration 00:10:00
#
# 산출물은 gitignore(infra/compose/stub/data/) 대상이라 각자 로컬에서 1회 실행한다.
# 원본 영상은 리포 안으로 복사하지 마라 — 기가바이트 단위다.
set -euo pipefail

readonly SEGMENT_SECONDS=4 # ADR-020
readonly THUMB_INTERVAL=2  # 썸네일 간격(초) = GOP 길이. 키프레임만 디코딩하면 딱 맞는다
readonly THUMB_COLS=10
readonly THUMB_ROWS=10
readonly TILE_WIDTH=160 # 1920 → 160x90. 400% 줌(≈68px/s)에서 2초 타일이 화면 135px이라 딱 맞다
readonly PEAK_RATE=8000 # 파형용 다운샘플. 100ms bin에 800샘플
readonly PEAK_BIN_MS=100

HERE="$(cd "$(dirname "$0")" && pwd)"

INPUT=""
START_RAW="00:20:00"
DURATION_RAW="00:10:00"
NAME="editor-sample"
LABEL=""
REENCODE=false
PATCH_CODECS=false
KEEP_PARTIAL=false

die() {
  echo "gen-editor-source.sh: $*" >&2
  exit 1
}

usage() {
  cat <<'USAGE'
사용: ./gen-editor-source.sh <입력파일> [옵션]

  --start <시각>       잘라낼 시작 (기본 00:20:00). HH:MM:SS 또는 초
  --duration <길이>    잘라낼 길이 (기본 00:10:00)
  --name <이름>        출력 폴더·streamId (기본 editor-sample)
  --label <문구>       편집기 헤더에 뜰 이름 (기본: 파일명으로 만든다)
  --reencode           키프레임이 격자에 안 맞을 때 다시 인코딩한다 (느리다)
  --patch-codecs       마스터의 CODECS에 오디오 코덱을 덧붙인다 (재생 안 될 때만)
  --keep-partial       실패해도 중간 산출물을 남긴다 (디버깅용)
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
  --start)
    START_RAW="${2:-}"
    shift 2
    ;;
  --duration)
    DURATION_RAW="${2:-}"
    shift 2
    ;;
  --name)
    NAME="${2:-}"
    shift 2
    ;;
  --label)
    LABEL="${2:-}"
    shift 2
    ;;
  --reencode)
    REENCODE=true
    shift
    ;;
  --patch-codecs)
    PATCH_CODECS=true
    shift
    ;;
  --keep-partial)
    KEEP_PARTIAL=true
    shift
    ;;
  -h | --help)
    usage
    exit 0
    ;;
  -*) die "모르는 옵션: $1 (--help 참고)" ;;
  *)
    [ -z "$INPUT" ] || die "입력 파일은 하나만 받습니다"
    INPUT="$1"
    shift
    ;;
  esac
done

# --- 전제 확인 ---------------------------------------------------------------
[ -n "$INPUT" ] || {
  usage
  die "입력 파일이 필요합니다"
}
[ -r "$INPUT" ] || die "입력 파일을 읽을 수 없습니다: $INPUT"
for tool in ffmpeg ffprobe node; do
  command -v "$tool" >/dev/null || die "$tool 가 필요합니다 (brew install ffmpeg node)"
done
# 이름이 곧 경로 조각이자 streamId다. web 쪽 STREAM_ID_RE(mediaSource.ts)와 같은 규칙으로 막는다 —
# 검증 없이 받으면 ../ 로 리포 밖에 쓸 수 있다.
case "$NAME" in
*[!A-Za-z0-9_-]* | "") die "--name 은 영숫자·밑줄·하이픈만 됩니다 (받은 값: $NAME)" ;;
esac

# HH:MM:SS 또는 초 → 초. date 명령을 안 쓴다(macOS·GNU 비호환)
to_seconds() {
  awk -v v="$1" 'BEGIN {
    n = split(v, p, ":")
    if (n == 3)      { print p[1] * 3600 + p[2] * 60 + p[3] }
    else if (n == 2) { print p[1] * 60 + p[2] }
    else             { print v + 0 }
  }'
}

START="$(to_seconds "$START_RAW")"
DURATION="$(to_seconds "$DURATION_RAW")"
awk -v d="$DURATION" 'BEGIN { exit (d > 0 ? 0 : 1) }' || die "--duration 은 0보다 커야 합니다"

OUT="$HERE/data/live/$NAME"
TMP="$OUT.partial"
[ -z "$LABEL" ] && LABEL="로컬 샘플 · $(basename "$INPUT")"

cleanup() {
  local rc=$?
  if [ $rc -ne 0 ] && [ "$KEEP_PARTIAL" = false ]; then
    rm -rf "$TMP"
    echo "실패했습니다 — 중간 산출물을 지웠습니다 (남기려면 --keep-partial)" >&2
  fi
  exit $rc
}
trap cleanup EXIT

rm -rf "$TMP"
mkdir -p "$TMP"
STARTED_AT=$SECONDS

# --- 1. 원본 프로브 ----------------------------------------------------------
WIDTH="$(ffprobe -v error -select_streams v:0 -show_entries stream=width -of default=nw=1:nk=1 "$INPUT")"
HEIGHT="$(ffprobe -v error -select_streams v:0 -show_entries stream=height -of default=nw=1:nk=1 "$INPUT")"
FRAME_RATE="$(ffprobe -v error -select_streams v:0 -show_entries stream=r_frame_rate -of default=nw=1:nk=1 "$INPUT")"
# 한 번에 여러 필드를 뽑으면 ffprobe 가 **요청 순서가 아니라 내부 순서**로 뱉는다
# (channels,sample_rate 를 물어도 sample_rate 가 먼저 온다). 뒤바뀐 값이 조용히 실리므로 따로 묻는다.
AUDIO_CHANNELS="$(ffprobe -v error -select_streams a:0 -show_entries stream=channels -of default=nw=1:nk=1 "$INPUT")"
AUDIO_RATE="$(ffprobe -v error -select_streams a:0 -show_entries stream=sample_rate -of default=nw=1:nk=1 "$INPUT")"
CREATION_TIME="$(ffprobe -v error -show_entries format_tags=creation_time -of default=nw=1:nk=1 "$INPUT")"
TOTAL_SECONDS="$(ffprobe -v error -show_entries format=duration -of default=nw=1:nk=1 "$INPUT")"

[ -n "${WIDTH:-}" ] || die "영상 스트림을 찾지 못했습니다"
[ -n "${AUDIO_CHANNELS:-}" ] || die "오디오 스트림을 찾지 못했습니다"
[ -n "$CREATION_TIME" ] || die "컨테이너에 creation_time 이 없습니다 — 방송 절대축 기준점을 못 만듭니다"
FPS="$(awk -v r="$FRAME_RATE" 'BEGIN { split(r, p, "/"); printf "%.4g", p[1] / (p[2] ? p[2] : 1) }')"

awk -v s="$START" -v d="$DURATION" -v t="$TOTAL_SECONDS" \
  'BEGIN { exit (s + d <= t + 0.5 ? 0 : 1) }' ||
  die "요청 구간($START + $DURATION 초)이 원본 길이($TOTAL_SECONDS 초)를 넘습니다"

echo "원본: ${WIDTH}x${HEIGHT} @ ${FPS}fps · 오디오 ${AUDIO_CHANNELS}ch ${AUDIO_RATE}Hz · ${TOTAL_SECONDS}초"

# --- 2. 키프레임 스냅 --------------------------------------------------------
# 요청한 시각을 그대로 -ss 에 쓰면 안 된다. 키프레임 위상은 파일 구간마다 다르고(실측: 0초대는
# 짝수 초, 20분대는 .75초), 스냅 없이 copy 하면 선행 프레임과 음수 타임스탬프가 생긴다.
# 그것을 0으로 밀면 영상 시작과 방송 절대축 기준점이 최대 GOP 하나만큼 어긋난다.
# 스냅한 값 하나를 HLS·필름스트립·파형이 **모두** 공유해야 셋이 같은 0초를 가리킨다.
READ_FROM="$(awk -v s="$START" 'BEGIN { v = s - 6; if (v < 0) v = 0; printf "%.3f", v }')"
SNAP="$(ffprobe -v error -select_streams v:0 -read_intervals "${READ_FROM}%+12" \
  -show_entries packet=pts_time,flags -of csv=p=0 "$INPUT" |
  awk -F, -v s="$START" '$2 ~ /^K/ { if ($1 + 0 <= s + 0.001) { before = $1 } else if (after == "") { after = $1 } }
                          END { print (before != "" ? before : after) }')"
[ -n "$SNAP" ] || die "요청 시각 근처에서 키프레임을 찾지 못했습니다"
echo "키프레임 스냅: $START → $SNAP 초"

# --- 3. HLS (영상·오디오 렌디션 분리 + 마스터) --------------------------------
# POK-168 확정 형태 그대로다 — 오디오 트랙을 골라 클립을 만들려면 합본이 아니라 분리여야 한다.
# %v 는 파일명에만 둔다. 디렉터리에 두면 -master_pl_name 이 리터럴 %v 폴더로 떨어질 수 있다.
# 그래서 기존 스텁의 chunks.m3u8 대신 video.m3u8 / audio0.m3u8 이름을 쓴다.
if [ "$REENCODE" = true ]; then
  # GOP 를 2초로 다시 심는다. 화질 유지가 목적이라 crf 는 넉넉하게 잡는다.
  GOP="$(awk -v i="$THUMB_INTERVAL" -v f="$FPS" 'BEGIN { printf "%d", i * f + 0.5 }')"
  CODEC_ARGS=(-c:v libx264 -preset medium -crf 20 -profile:v high -pix_fmt yuv420p
    -g "$GOP" -keyint_min "$GOP" -sc_threshold 0 -c:a copy)
  echo "다시 인코딩합니다 — 몇 분 걸립니다"
else
  CODEC_ARGS=(-c copy)
fi

echo "HLS 조각을 만드는 중…"
(
  cd "$TMP"
  ffmpeg -y -loglevel error -ss "$SNAP" -i "$INPUT" -t "$DURATION" \
    -map 0:v:0 -map 0:a:0 "${CODEC_ARGS[@]}" \
    -avoid_negative_ts make_zero \
    -f hls -hls_time "$SEGMENT_SECONDS" -hls_playlist_type vod -hls_segment_type fmp4 \
    -hls_flags independent_segments \
    -hls_fmp4_init_filename "%v_init.mp4" \
    -hls_segment_filename "%v_%05d.m4s" \
    -var_stream_map "v:0,agroup:aud,name:video a:0,agroup:aud,name:audio0,default:yes" \
    -master_pl_name index.m3u8 \
    "%v.m3u8"
)

[ -f "$TMP/index.m3u8" ] || die "마스터 재생목록이 안 생겼습니다 — ffmpeg 판본의 %v 처리를 확인하세요"
grep -q 'EXT-X-MEDIA:TYPE=AUDIO' "$TMP/index.m3u8" ||
  die "마스터에 오디오 렌디션이 없습니다 — var_stream_map 을 확인하세요"
grep -q 'AUDIO=' "$TMP/index.m3u8" || die "마스터의 STREAM-INF 가 오디오 그룹을 안 가리킵니다"

if [ "$PATCH_CODECS" = true ]; then
  # ffmpeg 9.0.1 은 CODECS 에 오디오까지 실어 준다(실측: avc1.42c02a,mp4a.40.2). 판본에 따라
  # variant 의 스트림만으로 만들어 오디오 코덱이 빠지는 경우가 있어 남겨 둔 우회다 —
  # 이미 실려 있으면 정규식이 안 걸리므로 두 번 붙지 않는다.
  perl -pi -e 's/(CODECS="avc1\.[0-9A-Fa-f]+)"/$1,mp4a.40.2"/' "$TMP/index.m3u8"
  echo "마스터 CODECS 에 mp4a.40.2 를 덧붙였습니다"
fi

# --- 4. 필름스트립 스프라이트 ------------------------------------------------
# -skip_frame nokey 가 핵심이다. GOP 2초와 썸네일 간격이 같아서 키프레임만 디코딩해도
# 필요한 프레임이 정확히 다 나온다 — 전수 디코딩(36,000프레임)보다 8~10배 빠르다.
echo "필름스트립을 만드는 중…"
ffmpeg -y -loglevel error -skip_frame nokey -ss "$SNAP" -i "$INPUT" -t "$DURATION" \
  -an -sn \
  -vf "fps=1/${THUMB_INTERVAL},scale=${TILE_WIDTH}:-2,tile=${THUMB_COLS}x${THUMB_ROWS}:color=black" \
  -c:v mjpeg -q:v 4 "$TMP/thumbs_%03d.jpg"

TILE_HEIGHT="$(awk -v w="$TILE_WIDTH" -v sw="$WIDTH" -v sh="$HEIGHT" \
  'BEGIN { h = int(w * sh / sw + 0.5); if (h % 2) h += 1; print h }')"

# --- 5. 파형 피크 ------------------------------------------------------------
echo "파형을 재는 중…"
ffmpeg -loglevel error -ss "$SNAP" -i "$INPUT" -t "$DURATION" \
  -map 0:a:0 -vn -sn -ac 1 -ar "$PEAK_RATE" -f s16le - |
  node "$HERE/peaks.mjs" --rate "$PEAK_RATE" --bin-ms "$PEAK_BIN_MS" --out "$TMP/peaks_0.json"

# --- 6. 사이드카 -------------------------------------------------------------
node "$HERE/descriptor.mjs" \
  --dir "$TMP" \
  --name "$NAME" \
  --label "$LABEL" \
  --creation-time "$CREATION_TIME" \
  --requested-start "$START" \
  --start "$SNAP" \
  --requested-duration "$DURATION" \
  --width "$WIDTH" \
  --height "$HEIGHT" \
  --fps "$FPS" \
  --audio-channels "$AUDIO_CHANNELS" \
  --audio-rate "$AUDIO_RATE" \
  --input-file "$(basename "$INPUT")" \
  --video-copied "$([ "$REENCODE" = true ] && echo false || echo true)" \
  --thumb-interval "$THUMB_INTERVAL" \
  --thumb-cols "$THUMB_COLS" \
  --thumb-rows "$THUMB_ROWS" \
  --tile-width "$TILE_WIDTH" \
  --tile-height "$TILE_HEIGHT"

# --- 7. 원자적 교체 ----------------------------------------------------------
# 여기까지 온 것만 자리에 앉힌다. 중간에 죽으면 기존 산출물이 그대로 남는다.
rm -rf "$OUT"
mv "$TMP" "$OUT"

ELAPSED=$((SECONDS - STARTED_AT))
echo
echo "완료: $OUT ($(du -sh "$OUT" | cut -f1), 파일 $(find "$OUT" -type f | wc -l | tr -d ' ')개, ${ELAPSED}초)"
echo
echo "확인:"
echo "  docker compose restart media-stub   # nginx.conf 를 고쳤다면 필요"
echo "  curl -s http://localhost:8080/live/$NAME/index.m3u8"
echo "  curl -s http://localhost:8080/live/$NAME/source.json"
echo
echo "web/.env.local 에:"
echo "  NEXT_PUBLIC_EDITOR_SOURCE_URL=http://localhost:8080/live/$NAME/source.json"
