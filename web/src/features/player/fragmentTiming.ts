import type { Fragment } from 'hls.js';
import type { DatedFragment } from './dvrWindow';
import type { PlaybackSource } from './rewindSwitch';

// 되감기 · 다시보기 목록의 판 태그와 원래 EXTINF 를 hls.js 조각에서 읽는다(설계 r7 3.2절 · 계약3 7-6
// 「판 태그」). hls.js 1.7.3 은 모르는 태그를 그 조각의 tagList 로 넘긴다 — `#EXT-X-POKECLIP-TIMING:2` 는
// [TIMING_TAG, '2'] 로, EXTINF 는 ['INF', 글자] 로 남는다. 발행기는 태그를 그 조각 태그 줄 안 EXT-X-MAP
// 뒤(MAP 이 없으면 맨 앞) · EXT-X-PROGRAM-DATE-TIME 앞에 싣는다 — MAP 앞 태그는 hls.js 가 초기화 조각에 넘겨
// 첫 조각이 받지 못한다.

/** 1번 목록 발행기가 목록 첫 조각과 판이 바뀌는 조각에 싣는 사용자 태그(계약3 7-6) */
export const TIMING_TAG = 'EXT-X-POKECLIP-TIMING';

/** 조각의 시각 판 — 2 는 판 2(영상 축 조각). 판 NULL · 태그 없는 옛 목록(지금 식)은 쓰는 자리에서 null 로 적는다 */
export type TimingVersion = 2;

/** 판 2 로 읽는 태그 값 — 그 밖(`0` · 모르는 값)은 판 NULL 이다 */
const VERSION_2_VALUE = '2';

/** 환산이 읽는 hls.js 조각 칸 — 시험이 흉내 조각을 넣을 수 있게 구조 타입으로 받는다 */
export type HlsFragmentLike = Pick<
  Fragment,
  'start' | 'duration' | 'programDateTime' | 'gap' | 'tagList' | 'cc' | 'sn'
>;

/**
 * 라이브 구성 시차(c) 보정 스위치(설계 r7 3.2 · 5.4절 · D1 (가) · 경계 표 열린 것 3). 켜면 라이브 재생기에도 영상 시작
 * 기록을 달고, 해체된 라이브 조각을 start = v_k + c_k 로 센다. 기본 false = 지금 prod 동작 · 넘김 실측 갈래 넷(설계
 * 12.2 · 경계 대조 W-2) 뒤 kty 가 정함. 실행 중 토글은 없다 — 바꾸는 일은 이 한 줄과 웹 재배포다
 */
export const LIVE_PTS_CORRECTION = false;

/** 조각 하나의 영상 시작(videoStarts 의 기록) */
export interface VideoStart {
  /** v_k: 영상 tfdt / 영상 timescale − initPTS / initPTS timescale (초) */
  videoStartS: number;
  /** c_k: 그 조각 첫 영상 표본 cto / 영상 timescale (초) — 라이브 스위치 ON 갈래만 쓴다 */
  firstCtoS: number;
}

/** 조각 → 그 조각의 영상 시작. 붙인 바이트를 읽지 못했거나 그 붙임에 offset(그 cc 의 initPTS)이 없으면 null */
export type StartOf = (frag: HlsFragmentLike) => VideoStart | null;

/** 이 조각에 실린 판 태그의 판 — 둘 이상이면 마지막 태그다. 태그가 없으면 undefined(앞 판을 잇는다) */
function taggedVersion(frag: HlsFragmentLike): TimingVersion | null | undefined {
  const tag = frag.tagList.findLast(([name]) => name === TIMING_TAG);
  if (!tag) return undefined;
  return tag[1] === VERSION_2_VALUE ? 2 : null;
}

/**
 * 조각마다의 판 — 목록 앞에서부터 마지막으로 본 태그 판을 잇는다. `2` 만 판 2 · 그 밖과 태그 없음은
 * null 이다. 미닫이 갱신에서도 발행기가 목록 첫 조각에 태그를 다시 싣고 hls.js 는 새로 읽은 조각을 넘기므로
 * 목록 안에서만 이으면 된다. 전제: /dvr 는 델타 갱신(EXT-X-SKIP)을 광고하지 않는다(계약3 7-5) — 델타
 * 목록이면 hls.js 가 건너뛴 자리를 앞 목록의 조각 객체로 채워, 새 첫 조각의 태그가 목록 앞에 오지 않을 수
 * 있다. /vod 는 VOD 목록을 다시 받지 않아 델타 갱신이 없다(base-playlist-controller 라이브 갈래)
 */
export function timingVersions(fragments: readonly HlsFragmentLike[]): Array<TimingVersion | null> {
  let current: TimingVersion | null = null;
  return fragments.map((frag) => {
    const tagged = taggedVersion(frag);
    if (tagged !== undefined) current = tagged;
    return current;
  });
}

/**
 * 목록이 준 원래 EXTINF(초) — tagList 의 ['INF', 글자]. hls.js 는 frag.duration 을 받은 조각의 PTS
 * 길이로 덮지만(level-helper mergeDetails · updateFragPTSDTS) tagList 는 덮지 않는다. 없거나 숫자가
 * 아니면 null
 */
export function playlistDurationS(frag: HlsFragmentLike): number | null {
  const raw = frag.tagList.find(([name]) => name === 'INF')?.[1];
  if (raw === undefined || raw.trim() === '') return null;
  const seconds = Number(raw);
  return Number.isFinite(seconds) ? seconds : null;
}

/** 해체 조각에서 EXTINF 를 더하고 빼 온 시작 추정 — distance 는 그 해체 조각까지의 조각 수 */
interface StartEstimate {
  startS: number;
  distance: number;
}

/**
 * 조각마다 앞쪽에서 가장 가까운 해체 조각 j 로 센 시작 v_j + Σ_{i=j}^{k−1} EXTINF_i. 사이가 모두 영상 축 조각(durations
 * 가 있음)이고 cc 가 같을 때만 잇는다
 */
function estimatesFromBefore(
  fragments: readonly HlsFragmentLike[],
  durations: ReadonlyArray<number | null>,
  known: ReadonlyArray<number | null>,
): Array<StartEstimate | null> {
  const estimates: Array<StartEstimate | null> = fragments.map(() => null);
  let carry: StartEstimate | null = null;
  for (let i = 0; i < fragments.length; i += 1) {
    const durationS = durations[i] ?? null;
    if (durationS === null || (carry !== null && fragments[i]!.cc !== fragments[i - 1]!.cc)) {
      carry = null;
    }
    if (durationS === null) {
      continue;
    }
    const own = known[i] ?? null;
    const estimate: StartEstimate | null = own !== null ? { startS: own, distance: 0 } : carry;
    estimates[i] = estimate;
    carry = estimate && { startS: estimate.startS + durationS, distance: estimate.distance + 1 };
  }
  return estimates;
}

/** 뒤쪽에서 가장 가까운 해체 조각 j 로 센 시작 v_j − Σ_{i=k}^{j−1} EXTINF_i. 잇는 조건은 estimatesFromBefore 와 같다 */
function estimatesFromAfter(
  fragments: readonly HlsFragmentLike[],
  durations: ReadonlyArray<number | null>,
  known: ReadonlyArray<number | null>,
): Array<StartEstimate | null> {
  const estimates: Array<StartEstimate | null> = fragments.map(() => null);
  let carry: StartEstimate | null = null;
  for (let i = fragments.length - 1; i >= 0; i -= 1) {
    const durationS = durations[i] ?? null;
    if (durationS === null || (carry !== null && fragments[i]!.cc !== fragments[i + 1]!.cc)) {
      carry = null;
    }
    if (durationS === null) {
      continue;
    }
    const own = known[i] ?? null;
    carry =
      own !== null
        ? { startS: own, distance: 0 }
        : carry && { startS: carry.startS - durationS, distance: carry.distance + 1 };
    estimates[i] = carry;
  }
  return estimates;
}

/**
 * 판 태그가 하나라도 실린 목록인가. tagList 가 없는 조각은 태그 없음으로 읽는다 — hls.js 조각은 늘 tagList 가 있지만
 * 기존 재생기 시험(useHlsPlayback*.test.ts)의 흉내 조각에는 이 칸이 없다. 태그가 없으면 뒤의 tagList 읽기에 닿지 않는다
 */
function hasTimingTag(fragments: readonly HlsFragmentLike[]): boolean {
  return fragments.some((frag) => frag.tagList?.some(([name]) => name === TIMING_TAG) ?? false);
}

/**
 * 조각마다 같은 cc 에서 가장 가까운 해체 조각의 c(초) — 거리가 같으면 앞 조각이다. 그 cc 를 하나도 해체하지 않았으면 null.
 * c 는 송출기 설정(B 프레임 수)이라 같은 장 안에서는 조각마다 거의 같다(설계 r7 5.4절)
 */
function nearestCtos(
  fragments: readonly HlsFragmentLike[],
  ctos: ReadonlyArray<number | null>,
): Array<number | null> {
  return fragments.map((frag, i) => {
    for (let distance = 0; distance < fragments.length; distance += 1) {
      for (const j of [i - distance, i + distance]) {
        const cto = ctos[j] ?? null;
        if (cto !== null && fragments[j]!.cc === frag.cc) {
          return cto;
        }
      }
    }
    return null;
  });
}

/**
 * 라이브 조각을 구성 시차 보정 자리로(설계 r7 5.1 표 ON 갈래 · 5.4절). 해체된 조각은 start = v_k + c_k, 해체 전 조각은
 * frag.start(hls.js 추정 — 라이브는 v_k 자리, T-3b)에 같은 cc 의 가장 가까운 해체 조각 c 를 더하고, 그 cc 를 하나도
 * 해체하지 않았으면 hls.js 조각 그대로다. 길이 · 시각 · 틈 표시는 hls.js 조각 값이다 — 판 태그가 없는 목록이라 원래
 * EXTINF 로 바꾸지 않는다
 */
function ptsCorrectedLiveFragments(
  fragments: readonly HlsFragmentLike[],
  startOf: StartOf,
): DatedFragment[] {
  const starts = fragments.map((frag) => startOf(frag));
  const borrowed = nearestCtos(
    fragments,
    starts.map((start) => start?.firstCtoS ?? null),
  );
  return fragments.map((frag, i): DatedFragment => {
    const own = starts[i] ?? null;
    const cto = borrowed[i] ?? null;
    if (cto === null) {
      return frag;
    }
    return {
      start: own !== null ? own.videoStartS + own.firstCtoS : frag.start + cto,
      duration: frag.duration,
      programDateTime: frag.programDateTime,
      gap: frag.gap,
    };
  });
}

/**
 * hls.js 조각 → 시각 환산(programDateAt · mediaTimeAt · loadedList)에 넘길 조각(설계 r7 3.2절 · r6 4.3 표).
 * 라이브 구성 시차 스위치는 LIVE_PTS_CORRECTION 이다 — 두 값은 datedFragmentsWith 로 잰다.
 */
export function datedFragments(
  fragments: readonly HlsFragmentLike[],
  kind: PlaybackSource['kind'],
  startOf: StartOf,
): readonly DatedFragment[] {
  return datedFragmentsWith(fragments, kind, startOf, LIVE_PTS_CORRECTION);
}

/**
 * datedFragments 에 라이브 구성 시차 스위치를 매개로 받는다. 시험 전용 — 제품 코드는 datedFragments 를 부른다(시험이
 * 두 값을 주입한다 — 설계 r7 10절 c5).
 * - 판 2 조각(원래 EXTINF 가 있음)은 영상 축이다: start = v_k(startOf), duration = 원래 EXTINF. 아직 해체하지 않은
 *   조각은 같은 cc 의 가장 가까운 해체 조각 ± EXTINF 합(사이가 모두 판 2 일 때만 · 거리가 같으면 앞 조각), 그 cc 를
 *   하나도 해체하지 않았으면 frag.start(hls.js 추정)다.
 * - 판 NULL · 태그 없는 목록 · 원래 EXTINF 가 없는 판 2 조각은 hls.js 조각 그대로다(지금 식 — 출시 전제).
 * - 라이브는 스위치가 꺼져 있으면 받은 배열 그대로(지금 식), 켜져 있으면 ptsCorrectedLiveFragments 다(설계 r7 5.4절).
 * 해체 뒤의 frag.start · duration 은 hls.js 가 기준 트랙 시작 · 모든 트랙 끝으로 덮은 값이라 판 2 에서 쓰지 않는다
 * (r6 4.1). hls.js 조각은 고치지 않는다 — 버퍼 · 창 판정(rewindFloor)은 hls.js 값 그대로여야 한다
 */
export function datedFragmentsWith(
  fragments: readonly HlsFragmentLike[],
  kind: PlaybackSource['kind'],
  startOf: StartOf,
  livePtsCorrection: boolean,
): readonly DatedFragment[] {
  if (kind === 'live') {
    return livePtsCorrection ? ptsCorrectedLiveFragments(fragments, startOf) : fragments;
  }
  if (!hasTimingTag(fragments)) {
    return fragments;
  }
  const versions = timingVersions(fragments);
  const durations = fragments.map((frag, i) =>
    versions[i] === 2 ? playlistDurationS(frag) : null,
  );
  const known = fragments.map((frag, i) =>
    durations[i] === null ? null : (startOf(frag)?.videoStartS ?? null),
  );
  const before = estimatesFromBefore(fragments, durations, known);
  const after = estimatesFromAfter(fragments, durations, known);
  return fragments.map((frag, i): DatedFragment => {
    const durationS = durations[i] ?? null;
    if (durationS === null) {
      return frag;
    }
    const fromBefore = before[i] ?? null;
    const fromAfter = after[i] ?? null;
    const nearest =
      fromBefore !== null && (fromAfter === null || fromBefore.distance <= fromAfter.distance)
        ? fromBefore
        : fromAfter;
    return {
      start: nearest?.startS ?? frag.start,
      duration: durationS,
      programDateTime: frag.programDateTime,
      gap: frag.gap,
      videoAxis: true,
    };
  });
}
