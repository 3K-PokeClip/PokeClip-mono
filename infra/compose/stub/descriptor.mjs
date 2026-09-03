#!/usr/bin/env node
// 편집기 로컬 소스의 사이드카(source.json) 생성 — gen-editor-source.sh 의 마지막 단계 (POK-238).
//
// 왜 셸이 아니라 node인가:
//  · 시각 변환 — macOS `date -j -f` 와 GNU `date -d` 가 비호환이다. Date.parse 하나면 둘 다 없다.
//  · 문자열 이스케이프 — 라벨에 한글·따옴표가 들어가도 JSON.stringify 가 알아서 한다.
//  · 산출물 대조 — 재생목록 EXTINF 합·시트 개수·피크 개수가 서로 맞는지 여기서 한 번에 검사한다.
//    (요청한 길이가 아니라 **실제로 만들어진 길이**가 정본이다. 키프레임 스냅과 마지막 조각
//     잔여 때문에 요청값과 다르다.)
//
// 사용: node descriptor.mjs --dir <출력폴더> --name editor-sample --label "..." \
//         --creation-time 2026-08-31T11:26:47.000000Z --requested-start 1200 --start 1199.75 \
//         --requested-duration 600 --width 1920 --height 1080 --fps 60 \
//         --audio-channels 2 --audio-rate 48000 --input-file "원본.mov" --video-copied true \
//         --thumb-interval 2 --thumb-cols 10 --thumb-rows 10 --tile-width 160 --tile-height 90

import { readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

/** 이 사이드카의 모양이 바뀌면 올린다 — 소비자(web)가 모르는 판이면 거부하게 하기 위해서다 */
const SCHEMA = 'pokeclip-editor-local-source/1';

const VIDEO_PLAYLIST = 'video.m3u8';
const AUDIO_PLAYLIST = 'audio0.m3u8';
const PEAKS_FILE = 'peaks_0.json';
const MASTER_PLAYLIST = 'index.m3u8';
/** 영상·오디오 조각은 서로 다른 경계에서 잘린다(키프레임 vs AAC 프레임). 이 정도 차이는 정상 */
const PLAYLIST_DRIFT_TOLERANCE_SECONDS = 0.5;

function die(message) {
  process.stderr.write(`descriptor.mjs: ${message}\n`);
  process.exit(1);
}

function parseArgs(argv) {
  const args = new Map();
  for (let i = 0; i < argv.length; i += 1) {
    const key = argv[i];
    if (!key.startsWith('--')) continue;
    args.set(key.slice(2), argv[i + 1] ?? '');
    i += 1;
  }
  return args;
}

const args = parseArgs(process.argv.slice(2));

function str(key) {
  const value = args.get(key);
  if (value === undefined || value === '') die(`--${key} 가 필요합니다`);
  return value;
}

function num(key) {
  const value = Number(args.get(key));
  if (!Number.isFinite(value)) die(`--${key} 가 숫자가 아닙니다 (받은 값: ${args.get(key)})`);
  return value;
}

/** 재생목록의 EXTINF 합 — 실제로 만들어진 길이 */
function playlistDuration(dir, file) {
  let text;
  try {
    text = readFileSync(join(dir, file), 'utf8');
  } catch {
    return die(`${file} 을 읽을 수 없습니다 — ffmpeg 단계가 실패했는지 확인하세요`);
  }
  let total = 0;
  let segments = 0;
  for (const line of text.split('\n')) {
    if (!line.startsWith('#EXTINF:')) continue;
    const value = Number.parseFloat(line.slice('#EXTINF:'.length));
    if (!Number.isFinite(value)) die(`${file} 의 EXTINF 를 읽지 못했습니다: ${line}`);
    total += value;
    segments += 1;
  }
  if (segments === 0) die(`${file} 에 조각이 없습니다`);
  return { seconds: Math.round(total * 1000) / 1000, segments };
}

const dir = str('dir');
const video = playlistDuration(dir, VIDEO_PLAYLIST);
const audio = playlistDuration(dir, AUDIO_PLAYLIST);

if (Math.abs(video.seconds - audio.seconds) > PLAYLIST_DRIFT_TOLERANCE_SECONDS) {
  die(
    `영상(${video.seconds}s)과 오디오(${audio.seconds}s) 길이 차이가 ` +
      `${PLAYLIST_DRIFT_TOLERANCE_SECONDS}s 를 넘습니다 — 매핑을 확인하세요`,
  );
}

// 영상이 정본이다. 편집기의 시간축은 영상 프레임이 정하고, 오디오는 거기 맞춰 붙는다.
const durationSeconds = video.seconds;

// --- 필름스트립 --------------------------------------------------------------
const intervalSeconds = num('thumb-interval');
const columns = num('thumb-cols');
const rows = num('thumb-rows');
const perSheet = columns * rows;

const sheets = readdirSync(dir)
  .filter((name) => /^thumbs_\d+\.jpg$/.test(name))
  .sort();
if (sheets.length === 0) die('썸네일 시트가 없습니다 — 필름스트립 단계가 실패했습니다');

// fps=1/interval 은 t=0, interval, 2·interval… 에서 한 장씩 뽑는다. 마지막 장은 duration 미만이므로
// 총 장수는 ceil(duration/interval) 이다 (duration 이 interval 의 배수면 마지막 칸이 딱 안 들어간다).
const count = Math.ceil(durationSeconds / intervalSeconds);
const expectedSheets = Math.ceil(count / perSheet);
if (sheets.length !== expectedSheets) {
  die(
    `시트 개수가 안 맞습니다 — 파일 ${sheets.length}장, 계산 ${expectedSheets}장 ` +
      `(길이 ${durationSeconds}s / 간격 ${intervalSeconds}s = ${count}장)`,
  );
}
// tile 필터는 마지막 시트의 남는 칸을 검게 채운다. 그리는 쪽이 그 칸을 프레임으로 오해하지 않게
// 유효 개수를 실어 보낸다.
const lastSheetCount = count - (sheets.length - 1) * perSheet;

// --- 오디오 피크 -------------------------------------------------------------
let peaks;
try {
  peaks = JSON.parse(readFileSync(join(dir, PEAKS_FILE), 'utf8'));
} catch {
  die(`${PEAKS_FILE} 을 읽을 수 없습니다 — 파형 단계가 실패했습니다`);
}
const expectedBins = Math.ceil((durationSeconds * 1000) / peaks.binMs);
// 파형은 오디오 길이에서 나오고 durationSeconds 는 영상 길이라, 위 허용 오차만큼 어긋날 수 있다
if (Math.abs(peaks.count - expectedBins) > (PLAYLIST_DRIFT_TOLERANCE_SECONDS * 1000) / peaks.binMs) {
  die(`피크 개수가 안 맞습니다 — 파일 ${peaks.count}개, 계산 ${expectedBins}개`);
}

// --- 방송 절대축 -------------------------------------------------------------
// 계약6의 모든 시각은 방송 절대축(UTC epoch ms)이다. 로컬 소스의 편집기 시간축은 파일 로컬 초라
// 여기서는 매핑의 기준점만 실어 둔다 — 컨테이너 creation_time + 잘라낸 시작 오프셋.
// (파일명의 시각이 아니라 creation_time 을 쓴다. 실측 결과 둘이 1초 어긋난다.)
const creationTime = str('creation-time');
const creationMs = Date.parse(creationTime);
if (!Number.isFinite(creationMs)) die(`--creation-time 을 해석하지 못했습니다: ${creationTime}`);
const startSeconds = num('start');
const sourceStartAtMs = Math.round(creationMs + startSeconds * 1000);

const descriptor = {
  schema: SCHEMA,
  streamId: str('name'),
  label: str('label'),
  generatedAtMs: Date.now(),
  sourceStartAtMs,
  sourceStartAtIso: new Date(sourceStartAtMs).toISOString(),
  durationSeconds,
  width: num('width'),
  height: num('height'),
  fps: num('fps'),
  playlist: MASTER_PLAYLIST,
  videoPlaylist: VIDEO_PLAYLIST,
  filmstrip: {
    sheets,
    columns,
    rows,
    tileWidth: num('tile-width'),
    tileHeight: num('tile-height'),
    intervalSeconds,
    count,
    lastSheetCount,
    mimeType: 'image/jpeg',
  },
  audioTracks: [
    {
      // ADR-017: 0 = 최종 믹스, 1~5 = 소스별. 로컬 녹화는 합쳐진 한 트랙뿐이라 0이다.
      trackId: 0,
      kind: 'mix',
      label: '오디오 · 최종 믹스',
      playlist: AUDIO_PLAYLIST,
      channels: num('audio-channels'),
      sampleRate: num('audio-rate'),
      peaks: {
        file: PEAKS_FILE,
        binMs: peaks.binMs,
        count: peaks.count,
        /** 값이 어떤 기준의 0..1인가 — abs16 = |s16|/32768 */
        scale: 'abs16',
        maxPeak: peaks.maxPeak,
      },
    },
  ],
  // 재현·디버깅용 출처. 나중에 "이 샘플 어디서 잘랐더라"를 파일 하나로 답하게 한다.
  source: {
    file: str('input-file'),
    containerCreationTime: creationTime,
    requestedStartSeconds: num('requested-start'),
    startSeconds,
    requestedDurationSeconds: num('requested-duration'),
    keyframeSnapped: num('requested-start') !== startSeconds,
    videoCopied: args.get('video-copied') === 'true',
    videoSegments: video.segments,
    audioSegments: audio.segments,
  },
};

writeFileSync(join(dir, 'source.json'), `${JSON.stringify(descriptor, null, 2)}\n`);
process.stderr.write(
  `descriptor.mjs: ${durationSeconds}초 · 조각 영상 ${video.segments}/오디오 ${audio.segments}개 · ` +
    `썸네일 ${count}장(시트 ${sheets.length}) · 피크 ${peaks.count}개\n`,
);
