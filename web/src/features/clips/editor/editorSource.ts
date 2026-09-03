// 편집기가 재생할 소스의 서술 — 사이드카 JSON 의 타입·검증·URL 해석 (POK-238).
//
// 지금은 로컬 개발용 정적 파일(infra/compose/stub/gen-editor-source.sh 산출물)을 읽는다.
// 서버가 영상 출입증(POK-122)을 열면 이 자리에 그 응답이 들어온다 — 화면과 상태 허브는
// 어디서 왔는지 모르고 이 모양만 본다. mediaSource.ts 가 라이브에서 하는 일과 같다.
//
// 검증을 손으로 쓰는 이유: 이 리포는 스키마 라이브러리를 쓰지 않는다(api/client.ts 관례).
// 대신 **모르는 모양이면 거부**한다 — 필드가 조용히 undefined 로 흘러 화면이 빈 채로 서는 것보다
// 어디가 틀렸는지 말하고 멈추는 편이 낫다.

import { MIN_RANGE_SECONDS, type ClipRange } from './timelineMath';

/** 아는 사이드카 판. 다른 값이면 거부한다 */
export const EDITOR_SOURCE_SCHEMA = 'pokeclip-editor-local-source/1';

/** 타임라인 영상 레인에 깔리는 스프라이트 시트 묶음 */
export interface FilmstripSheets {
  /** 시트 파일들 (사이드카 기준 상대 경로) */
  sheets: readonly string[];
  columns: number;
  rows: number;
  tileWidth: number;
  tileHeight: number;
  /** 썸네일 한 장이 덮는 시간(초) */
  intervalSeconds: number;
  /** 전체 썸네일 장수 */
  count: number;
  /** 마지막 시트의 유효 칸 수 — 나머지 칸은 패딩이라 그리면 안 된다 */
  lastSheetCount: number;
}

/** 오디오 레인의 파형 — bin 마다 최대 진폭 0..1 */
export interface AudioPeaks {
  binMs: number;
  count: number;
  /** 값의 기준. abs16 = |s16|/32768 */
  scale: string;
  maxPeak: number;
  peaks: readonly number[];
}

export interface EditorAudioTrack {
  /** ADR-017: 0 = 최종 믹스, 1~5 = 소스별 */
  trackId: number;
  kind: string;
  label: string;
  channels: number;
  sampleRate: number;
  /** 피크 JSON 의 절대 URL (사이드카 기준으로 이미 풀었다) */
  peaksUrl: string;
}

/** 사이드카를 읽어 URL 까지 푼 결과 — 화면·허브가 쓰는 모양 */
export interface EditorMediaSource {
  streamId: string;
  label: string;
  /** 방송 절대축 기준점(UTC epoch ms). 편집기 시간축은 파일 로컬 초라 지금은 보관만 한다 */
  sourceStartAtMs: number;
  durationSeconds: number;
  width: number;
  height: number;
  fps: number;
  /** 마스터 재생목록 절대 URL */
  playlistUrl: string;
  filmstrip: FilmstripSheets & { sheetUrls: readonly string[] };
  audioTracks: readonly EditorAudioTrack[];
}

function fail(what: string): never {
  throw new Error(`편집기 소스 서술이 올바르지 않습니다 — ${what}`);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function num(source: Record<string, unknown>, key: string, where: string): number {
  const value = source[key];
  // 유한수만 받는다 — NaN·Infinity 가 통과하면 좌표 계산이 통째로 무너지고, 원인이
  // 그리는 쪽에서 발견돼 추적이 길어진다
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    fail(`${where}.${key} 가 유한한 수가 아닙니다`);
  }
  return value;
}

function str(source: Record<string, unknown>, key: string, where: string): string {
  const value = source[key];
  if (typeof value !== 'string' || value === '') fail(`${where}.${key} 가 비어 있습니다`);
  return value;
}

/**
 * 사이드카 기준 상대 경로를 절대 URL 로. 화면이 상대 경로를 그대로 쓰면 라우트가 바뀔 때
 * 엉뚱한 곳을 가리킨다 — 읽은 자리에서 한 번에 푼다.
 */
function resolve(path: string, baseUrl: string): string {
  try {
    return new URL(path, baseUrl).toString();
  } catch {
    return fail(`경로를 풀 수 없습니다: ${path}`);
  }
}

/** 사이드카 JSON → 화면이 쓰는 모양. 모르는 판이거나 필드가 어긋나면 던진다 */
export function parseEditorSource(input: unknown, sourceUrl: string): EditorMediaSource {
  if (!isRecord(input)) fail('객체가 아닙니다');
  if (input.schema !== EDITOR_SOURCE_SCHEMA) {
    fail(`모르는 판입니다 (기대 ${EDITOR_SOURCE_SCHEMA}, 받은 값 ${String(input.schema)})`);
  }

  const durationSeconds = num(input, 'durationSeconds', 'source');
  if (durationSeconds <= 0) fail('durationSeconds 가 0보다 커야 합니다');

  const filmstripRaw = input.filmstrip;
  if (!isRecord(filmstripRaw)) fail('filmstrip 이 없습니다');
  const sheets = filmstripRaw.sheets;
  if (!Array.isArray(sheets) || sheets.length === 0) fail('filmstrip.sheets 가 비어 있습니다');
  const sheetPaths = sheets.map((sheet, index) => {
    if (typeof sheet !== 'string' || sheet === '') fail(`filmstrip.sheets[${index}] 가 비었습니다`);
    return sheet;
  });

  const audioRaw = input.audioTracks;
  if (!Array.isArray(audioRaw) || audioRaw.length === 0) fail('audioTracks 가 비어 있습니다');

  return {
    streamId: str(input, 'streamId', 'source'),
    label: str(input, 'label', 'source'),
    sourceStartAtMs: num(input, 'sourceStartAtMs', 'source'),
    durationSeconds,
    width: num(input, 'width', 'source'),
    height: num(input, 'height', 'source'),
    fps: num(input, 'fps', 'source'),
    playlistUrl: resolve(str(input, 'playlist', 'source'), sourceUrl),
    filmstrip: {
      sheets: sheetPaths,
      sheetUrls: sheetPaths.map((sheet) => resolve(sheet, sourceUrl)),
      columns: num(filmstripRaw, 'columns', 'filmstrip'),
      rows: num(filmstripRaw, 'rows', 'filmstrip'),
      tileWidth: num(filmstripRaw, 'tileWidth', 'filmstrip'),
      tileHeight: num(filmstripRaw, 'tileHeight', 'filmstrip'),
      intervalSeconds: num(filmstripRaw, 'intervalSeconds', 'filmstrip'),
      count: num(filmstripRaw, 'count', 'filmstrip'),
      lastSheetCount: num(filmstripRaw, 'lastSheetCount', 'filmstrip'),
    },
    audioTracks: audioRaw.map((track, index) => {
      const where = `audioTracks[${index}]`;
      if (!isRecord(track)) fail(`${where} 가 객체가 아닙니다`);
      const peaksRaw = track.peaks;
      if (!isRecord(peaksRaw)) fail(`${where}.peaks 가 없습니다`);
      return {
        trackId: num(track, 'trackId', where),
        kind: str(track, 'kind', where),
        label: str(track, 'label', where),
        channels: num(track, 'channels', where),
        sampleRate: num(track, 'sampleRate', where),
        peaksUrl: resolve(str(peaksRaw, 'file', `${where}.peaks`), sourceUrl),
      };
    }),
  };
}

/** 파형 JSON → 화면이 쓰는 모양 */
export function parseAudioPeaks(input: unknown): AudioPeaks {
  if (!isRecord(input)) fail('파형이 객체가 아닙니다');
  const peaks = input.peaks;
  if (!Array.isArray(peaks) || peaks.length === 0) fail('파형 peaks 가 비어 있습니다');
  const count = num(input, 'count', 'peaks');
  if (peaks.length !== count) {
    fail(`파형 개수가 안 맞습니다 (count ${count}, 배열 ${peaks.length})`);
  }
  return {
    binMs: num(input, 'binMs', 'peaks'),
    count,
    scale: str(input, 'scale', 'peaks'),
    maxPeak: num(input, 'maxPeak', 'peaks'),
    peaks: peaks.map((value, index) => {
      if (typeof value !== 'number' || !Number.isFinite(value)) {
        fail(`peaks[${index}] 가 수가 아닙니다`);
      }
      return value;
    }),
  };
}

/** 시안 1d-a 의 선택 구간 길이 — 로컬 소스에도 같은 길이로 자리를 잡아 준다 */
const DEFAULT_RANGE_SECONDS = 12.4;
/** 처음 열었을 때 구간이 놓이는 자리 — 소스 앞머리를 조금 지나서 */
const DEFAULT_RANGE_OFFSET_SECONDS = 60;

/**
 * 소스를 처음 열 때의 선택 구간. 짧은 소스에서도 하한(5초)을 지키고 끝을 넘지 않는다 —
 * 넘으면 구간 핸들이 잡을 곳 없이 밖에 놓인다.
 */
export function initialRangeForSource(durationSeconds: number): ClipRange {
  const length = Math.max(MIN_RANGE_SECONDS, Math.min(DEFAULT_RANGE_SECONDS, durationSeconds));
  const start = Math.max(
    0,
    Math.min(DEFAULT_RANGE_OFFSET_SECONDS, durationSeconds * 0.1, durationSeconds - length),
  );
  return { startSeconds: start, endSeconds: start + length };
}

/**
 * 로컬 소스 사이드카의 주소. 없으면 null — 그때는 목업 시뮬레이션으로 간다.
 *
 * 모듈 스코프가 아니라 함수 안에서 읽는다 — 테스트가 vi.stubEnv 로 갈아끼울 수 있어야 한다.
 * 리터럴 접근이라야 빌드 타임에 값이 박힌다(googleOAuth.ts·mediaSource.ts 선례).
 */
export function editorSourceUrl(): string | null {
  return process.env.NEXT_PUBLIC_EDITOR_SOURCE_URL || null;
}
