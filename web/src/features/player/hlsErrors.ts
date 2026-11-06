import type Hls from 'hls.js';
import type { ErrorData, ErrorDetails, ErrorTypes } from 'hls.js';
import type { DvrSegment } from './rewindSwitch';

/**
 * 목록(멀티변이 · 변이 · 오디오 목록)을 받지 못한 오류인가. 조각 · 키 받기 오류는 아니다 — 목록이 살아 있어도 조각 하나는
 * 404 일 수 있고, hls.js 는 그것도 재시도 한도(fragLoadPolicy 기본 6회)를 다 쓰면 치명으로 올린다(cx H1). 단 세션에 묶인
 * 되감기 재생기의 미디어 조각 404 는 이 판정보다 앞에서 앱이 가로챈다(consumeDvrSegmentGone · POK-528)
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
 * 첫 목록(hls.js 의 MANIFEST — 멀티변이 목록 또는 변이 하나뿐인 목록)을 받지 못하고 치명으로 끝난 오류인가(POK-528 c8).
 * hls.js 는 첫 목록의 받기 오류 · 시한 · 파싱 오류를 늘 치명으로 올린다 — 다시 받기(manifestLoadPolicy: 5xx 는 1초 뒤 한 번
 * · 시한은 두 번 · 4xx 는 다시 받지 않음)는 그 앞 로더 안에서 끝난다(loader/playlist-loader.ts 611-615 · 653-665). 응답
 * 코드는 보지 않는다: 첫 목록이 없으면 변이가 없어 앱이 startLoad 해도 목록을 다시 청하지 않는 것(playlist-loader.ts 86 ·
 * stream-controller.ts 191-193)은 코드와 무관하다. 변이 목록 · 조각 오류는 이 판별 대상이 아니다.
 * 파싱 오류(MANIFEST_PARSING_ERROR)는 멀티변이 목록을 읽을 때만 난다(hls.js 1.7.3 playlist-loader.ts 346-358 · 415-417 ·
 * 614). 변이 목록의 파싱 오류는 이 꼴로 오지 않는다 — LEVEL_PARSING_ERROR · LEVEL_EMPTY_ERROR 비치명이다(같은 파일 742 · 764)
 * @param types hls.js 의 ErrorTypes
 * @param details hls.js 의 ErrorDetails. 재생기는 hls.js 를 동적으로 읽으므로 두 값을 넘겨받는다(번들 분리)
 */
export function isFirstListFailure(
  data: Pick<ErrorData, 'type' | 'details' | 'fatal'>,
  types: typeof ErrorTypes,
  details: typeof ErrorDetails,
): boolean {
  return (
    data.fatal &&
    data.type === types.NETWORK_ERROR &&
    (data.details === details.MANIFEST_LOAD_ERROR ||
      data.details === details.MANIFEST_LOAD_TIMEOUT ||
      data.details === details.MANIFEST_PARSING_ERROR)
  );
}

/**
 * 미디어 조각을 404 로 받지 못한 오류인가(POK-528) — 방송 중 /dvr 하한 밖으로 밀린 조각이 엣지에서 이 꼴로 온다. 상태 0 ·
 * 403 · 5xx · 시한은 하한과 무관한 받기 실패라 아니다. init 조각은 하한 대상이 아니라 하한으로 옮겨도 낫지 않으므로 아니다.
 * 치명 여부는 보지 않는다 — 같은 404 가 첫 받기에서는 비치명으로, 재시도를 다 쓴 뒤에는 치명으로 온다
 *
 * init 404 는 앱에 미디어 조각의 오류로 온다 — hls.js 가 미디어 조각 받기 앞의 init 받기가 거절되면 frag 를 그 미디어
 * 조각으로 바꿔 끼운 뒤 올린다(base-stream-controller.ts 815-823). 그래서 sn 이 아니라 실패한 파일(response.url —
 * fragment-loader.ts 118-126 이 받던 조각의 url 로 채운다)이 그 조각의 init 인지로 가른다. sn 조건(hls.js isMediaFragment
 * 와 같은 식)은 방어 조건이다 — 1.7.3 은 받기 중이면 늘 바꿔 끼워 올려 init 조각 자체(sn 'initSegment')로는 오지 않는다.
 * 원본에서 찾은 예외는 거절 때 국면이 이미 STOPPED · ERROR 인 경우다: 바꿔 끼우지 않고 다시 던져(815-818) 바깥 Promise.all
 * 의 catch(1171)가 init 조각 그대로 올릴 수 있다. 한계: init 과 미디어가 같은 url(한 파일 byterange)이면 가르지 못한다 —
 * MediaMTX 는 init 을 별도 파일로 낸다
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
    data.frag?.sn !== 'initSegment' &&
    !failedOnInitSegment(data)
  );
}

/** 실패한 파일이 그 조각의 init 인가. init 이 없거나 url 을 모르면 아니다 */
function failedOnInitSegment(data: Pick<ErrorData, 'response' | 'frag'>): boolean {
  const initUrl = data.frag?.initSegment?.url;
  if (!initUrl) return false;
  return data.response?.url === initUrl;
}

/**
 * 되감기 재생기의 미디어 조각 404(isMediaSegmentNotFound)를 앱이 먼저 가로챈다(POK-528) — 받기를 멈추고 세션에
 * 그 조각을 올린다. hls.js 처리기는 앱 처리기보다 먼저 돈다 — 스트림 컨트롤러의 ERROR 처리기는 생성자에서 달리고
 * (stream-controller.ts 94 · 97-98 · base-stream-controller.ts 150-157) 오류 컨트롤러의 최종 처리기(onErrorOut)도 생성자에서
 * 달린다(hls.ts 351-356). 그래서 앱 처리기는 onErrorOut 뒤이고, 이미 다음 받기를 예약해 둔 뒤다: 마지막 · ENDLIST 조각은
 * 같은 조각 재시도, 방송 중 가운데 조각은 틈 넘김 뒤 다음 tick(base-stream-controller.ts 2153-2187). stopLoad 가
 * 그 예약과 목록 재요청을 함께 끊는다(176-194). 다시 받기 · 재생기 내리기는 하지 않는다 — 복구(새 재생기 · 다시보기 ·
 * 닫기)는 세션이 정한다
 *
 * 세션이 아는 목록 안 구멍(이미 한 번 다시 받게 한 조각)이고 hls.js 가 이 오류를 틈으로 넘기기로 했으면
 * (hlsSkipsAsGap) 가로채지 않는다(POK-528 후속) — 받기를 멈추면 hls.js 가 예약한 다음 조각 받기가 끊기므로 판정은
 * stopLoad · onGone 앞이다. 다시 받은 재생기가 구멍 조각에서 시작해도 첫 버퍼 전 · 뒤 모두 같다. 단 그 뒤 hls.js 가
 * 재생점을 다음 조각으로 옮기는 것은 재생 중일 때다 — 멈춘 채면 사람이 틀 때 건너뛴다(gap-controller.ts 184-185 ·
 * 221-231 · 296-297)
 * @param onGone 세션 고리 — 받지 못한 조각(frag 의 type · sn)을 받는다. 없으면 가로채지 않는다(지금 동작 — hls.js
 *     재시도와 앱의 한 번 다시 받기)
 * @param types hls.js 의 ErrorTypes
 * @param details hls.js 의 ErrorDetails
 * @param passHole 세션이 아는 목록 안 구멍인가. 없으면 늘 가로챈다
 * @returns 가로챘나 — 참이면 오류 처리기는 여기서 끝난다
 */
export function consumeDvrSegmentGone(
  hls: Pick<Hls, 'stopLoad'>,
  data: Pick<ErrorData, 'type' | 'details' | 'response' | 'frag' | 'part'>,
  types: typeof ErrorTypes,
  details: typeof ErrorDetails,
  onGone?: (segment: DvrSegment) => void,
  passHole?: (segment: DvrSegment) => boolean,
): boolean {
  if (onGone === undefined || !isMediaSegmentNotFound(data, types, details)) return false;
  const segment = goneSegment(data);
  if (segment === null) return false;
  if (passHole?.(segment) === true && hlsSkipsAsGap(data)) {
    console.info('[player] 목록 안 구멍 — hls.js 에 맡김', segment.sn);
    return false;
  }
  hls.stopLoad();
  onGone(segment);
  return true;
}

/**
 * hls.js 가 이 받기 오류를 틈으로 넘기기로 했나(POK-528 후속) — 부분 조각이면 part.gap, 아니면 frag.gap. hls.js 의
 * 조각 오류 처리기가 앱 처리기보다 먼저 돌아(hls.ts 351-356) 앱이 볼 때는 이미 정해져 있다: 다시 받지 않음 ∧ 갈아탈
 * 대안 없음 ∧ 방송 중 목록 ∧ 영상 목록 마지막 조각 앞이면 틈으로 적는다(base-stream-controller.ts 2153-2167 →
 * fragment-tracker.ts 295-296 addAsGap 의 frag.gap). ENDLIST 목록 · 마지막 조각 · 다시 받기 예약 · 문맥이 바뀐 늦은
 * 오류는 거짓이다. 화질이 여럿인 목록이면 hls.js 가 다른 화질로 갈아타기를 골라 틈이 서지 않는다 — 그러면 지금처럼 가로채 세션의
 * 404 행으로 간다
 */
export function hlsSkipsAsGap(data: Pick<ErrorData, 'frag' | 'part'>): boolean {
  return data.part ? data.part.gap === true : data.frag?.gap === true;
}

/**
 * 오류의 미디어 조각(type · sn). 조각이 없거나 init 조각이면 null — 앞선 판별(isMediaSegmentNotFound)은 frag 없는
 * 오류도 통과시키므로 여기서 뺀다. hls.js 1.7.3 은 FRAG_LOAD_ERROR 에 frag 를 싣는다(fragment-loader.ts 55 · 123 ·
 * 265) — 없으면 가로채지 않아 옛 경로(hls.js 재시도와 앱의 한 번 다시 받기)로 간다
 */
function goneSegment(data: Pick<ErrorData, 'frag'>): DvrSegment | null {
  const { frag } = data;
  if (frag === undefined || frag.sn === 'initSegment') return null;
  return { type: frag.type, sn: frag.sn };
}
