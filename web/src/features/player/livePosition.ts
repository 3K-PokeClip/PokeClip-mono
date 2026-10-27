import type { SeekableLike } from './dvrWindow';

/**
 * 라이브 재생기의 자리(POK-326). 시각은 조각마다 그 조각의 시각 표시(PDT)로 센 실제 시각(epoch ms)이다. 같은 실제 초 안에서
 * 시차도 재생 여부도 같으면 재생기는 그 초에 처음 잰 값을 둔다
 */
export interface LivePosition {
  /** 재생 위치의 실제 시각 */
  atMs: number;
  /** 실시간 끝(재생기가 서는 라이브 끝)의 실제 시각. 라이브 채팅이 표에 다 들어온 자리를 이것으로 센다 */
  edgeAtMs: number;
  /** 실시간 끝에서 몇 초 뒤인가(정수, 재생기 시차 표기와 같은 값) */
  behindSeconds: number;
  /** 재생 중인가(`!video.paused`, POK-200 c13). 화면 단추의 재생 상태와는 따로 잰다 */
  playing: boolean;
  /** 지금 위치를 담은 played 범위의 길이(ms) — 연속 시작 = atMs − runMs(playedRunMs) */
  runMs: number;
}

/** played 범위 끝과 재생 위치를 대조하는 여유(초) — 재생 중 played 끝이 currentTime 과 같은 값이 아닐 수 있다 */
export const PLAYED_EDGE_EPS_S = 0.05;

/**
 * 지금 위치를 담은 played 범위의 길이(ms). 담은 범위가 없으면 0. played 는 정상 재생으로 닿은 점들의 정규화 집합이라 맞닿은
 * 범위는 합쳐져 있다. 범위 안의 모든 점이 이 소스에서 정상 재생으로 닿은 점이라는 성질만 쓴다. 소스(재생기)마다 played 가
 * 비워진다는 것은 실측(M1c)이 판정하는 안전 전제다
 */
export function playedRunMs(played: SeekableLike, currentTime: number): number {
  for (let i = 0; i < played.length; i++) {
    const start = played.start(i);
    const end = played.end(i);
    if (start <= currentTime && currentTime <= end + PLAYED_EDGE_EPS_S) {
      return Math.round((end - start) * 1000);
    }
  }
  return 0;
}

/**
 * 재생기 자리의 다음 값. 같은 실제 초 안이고 시차 · 재생 여부도 같으면 앞 값(같은 객체)을 둔다: timeupdate 는 1초에 네 번쯤
 * 온다(시차를 반올림하는 이유와 같다). 같은 초 안의 연속 길이 변화는 다음 초의 값이 드러낸다. 재생 여부가 바뀌면 같은
 * 초여도 새 값이다 — 일시정지만 해도 한 번 낸다. 정각 초로 내리지 않는다: 카드 · 채팅 바닥 시계의 0초(서버 기준점)는 ms
 * 자리가 있어, 내리면 카드로 정확히 옮겨도 채팅이 카드보다 그만큼 일찍 잘리고 바닥이 1초 이르게 섰다(리뷰 2라운드)
 */
export function nextLivePosition(
  prev: LivePosition | null,
  atMs: number,
  edgeMs: number,
  behindSeconds: number,
  playing: boolean,
  runMs: number,
): LivePosition {
  const at = Math.round(atMs);
  if (
    prev !== null &&
    Math.floor(prev.atMs / 1000) === Math.floor(at / 1000) &&
    prev.behindSeconds === behindSeconds &&
    prev.playing === playing
  ) {
    return prev;
  }
  return {
    atMs: at,
    edgeAtMs: Math.round(edgeMs),
    behindSeconds,
    playing,
    runMs: Math.round(runMs),
  };
}
