import type { Fragment } from 'hls.js';

// 되감기 · 다시보기 목록의 판 태그와 원래 EXTINF 를 hls.js 조각에서 읽는다(설계 r7 3.2절 · 계약3 7-6
// 「판 태그」). hls.js 1.7.3 은 모르는 태그를 그 조각의 tagList 로 넘긴다 — `#EXT-X-POKECLIP-TIMING:2` 는
// [TIMING_TAG, '2'] 로, EXTINF 는 ['INF', 글자] 로 남는다. 발행기는 태그를 그 조각 태그 줄 안 EXT-X-MAP
// 뒤(MAP 이 없으면 맨 앞) · EXT-X-PROGRAM-DATE-TIME 앞에 싣는다 — MAP 앞 태그는 hls.js 가 초기화 조각에 넘겨
// 첫 조각이 받지 못한다.

/** 1번 목록 발행기가 목록 첫 조각과 판이 바뀌는 조각에 싣는 사용자 태그(계약3 7-6) */
export const TIMING_TAG = 'EXT-X-POKECLIP-TIMING';

/** 조각의 시각 판 — 2 는 판 2(영상 축 조각), null 은 판 NULL · 태그 없는 옛 목록(지금 식) */
export type TimingVersion = 2 | null;

/** 판 2 로 읽는 태그 값 — 그 밖(`0` · 모르는 값)은 판 NULL 이다 */
const VERSION_2_VALUE = '2';

/** 환산이 읽는 hls.js 조각 칸 — 시험이 흉내 조각을 넣을 수 있게 구조 타입으로 받는다 */
export type HlsFragmentLike = Pick<
  Fragment,
  'start' | 'duration' | 'programDateTime' | 'gap' | 'tagList' | 'cc' | 'sn'
>;

/** 이 조각에 실린 판 태그의 판. 태그가 없으면 undefined(앞 판을 잇는다) */
function taggedVersion(frag: HlsFragmentLike): TimingVersion | undefined {
  const tag = frag.tagList.findLast(([name]) => name === TIMING_TAG);
  if (!tag) return undefined;
  return tag[1] === VERSION_2_VALUE ? 2 : null;
}

/**
 * 조각마다의 판 — 목록 앞에서부터 마지막으로 본 태그 판을 잇는다. `2` 만 판 2 · 그 밖과 태그 없음은
 * null 이다. 미닫이 갱신에서도 발행기가 목록 첫 조각에 태그를 다시 싣고 hls.js 는 새로 읽은 조각을 넘기므로
 * 목록 안에서만 이으면 된다. 전제: /dvr 는 델타 갱신(EXT-X-SKIP)을 광고하지 않는다(계약3 7-5) — 델타
 * 목록이면 hls.js 가 건너뛴 자리를 앞 목록의 조각 객체로 채워, 새 첫 조각의 태그가 목록 앞에 오지 않을 수
 * 있다. /vod 도 같은 발행기라 같다고 본다(추론 — 7-5 문언은 「되감기 목록」)
 */
export function timingVersions(fragments: readonly HlsFragmentLike[]): TimingVersion[] {
  let current: TimingVersion = null;
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
