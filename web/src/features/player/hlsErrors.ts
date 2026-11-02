import type { ErrorData, ErrorDetails } from 'hls.js';

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
