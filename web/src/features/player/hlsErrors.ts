import type { ErrorData, ErrorDetails, ErrorTypes } from 'hls.js';

/**
 * 목록(멀티변이 · 변이 · 오디오 목록)을 받지 못한 오류인가. 조각 · 키 받기 오류는 아니다 — 목록이 살아 있어도 조각 하나는
 * 404 일 수 있고, hls.js 는 그것도 재시도 한도(fragLoadPolicy 기본 6회)를 다 쓰면 치명으로 올린다(cx H1)
 * @param details hls.js 의 ErrorDetails. 재생기는 hls.js 를 동적으로 읽으므로 값을 넘겨받는다(번들 분리)
 */
export function isPlaylistLoadError(
  data: Pick<ErrorData, 'details'>,
  details: typeof ErrorDetails,
): boolean {
  return (
    data.details === details.MANIFEST_LOAD_ERROR ||
    data.details === details.LEVEL_LOAD_ERROR ||
    data.details === details.AUDIO_TRACK_LOAD_ERROR
  );
}

/**
 * 미디어 조각을 404 로 받지 못한 오류인가(POK-528) — 방송 중 /dvr 하한 밖으로 밀린 조각이 엣지에서 이 꼴로 온다. 상태 0 ·
 * 403 · 5xx · 시한은 하한과 무관한 받기 실패라 아니다. init 조각은 하한 대상이 아니라 하한으로 옮겨도 낫지 않으므로 아니다
 * (hls.js isMediaFragment 와 같은 식). 치명 여부는 보지 않는다 — 같은 404 가 첫 받기에서는
 * 비치명으로, 재시도를 다 쓴 뒤에는 치명으로 온다
 * @param types hls.js 의 ErrorTypes
 * @param details hls.js 의 ErrorDetails. 재생기는 hls.js 를 동적으로 읽으므로 두 값을 넘겨받는다(번들 분리)
 */
export function isMediaSegmentNotFound(
  data: Pick<ErrorData, 'type' | 'details' | 'response' | 'frag'>,
  types: typeof ErrorTypes,
  details: typeof ErrorDetails,
): boolean {
  return (
    data.type === types.NETWORK_ERROR &&
    data.details === details.FRAG_LOAD_ERROR &&
    data.response?.code === 404 &&
    data.frag?.sn !== 'initSegment'
  );
}
