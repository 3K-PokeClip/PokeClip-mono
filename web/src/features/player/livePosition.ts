/**
 * 라이브 재생기의 자리(POK-326). 시각은 조각마다 그 조각의 시각 표시(PDT)로 센 실제 시각(epoch ms)이다. 같은 실제 초 안에서
 * 시차도 같으면 재생기는 그 초에 처음 잰 값을 둔다
 */
export interface LivePosition {
  /** 재생 위치의 실제 시각 */
  atMs: number;
  /** 실시간 끝(재생기가 서는 라이브 끝)의 실제 시각. 라이브 채팅이 표에 다 들어온 자리를 이것으로 센다 */
  edgeAtMs: number;
  /** 실시간 끝에서 몇 초 뒤인가(정수, 재생기 시차 표기와 같은 값) */
  behindSeconds: number;
}

/**
 * 재생기 자리의 다음 값. 같은 실제 초 안이고 시차도 같으면 앞 값(같은 객체)을 둔다: timeupdate 는 1초에 네 번쯤 온다(시차를
 * 반올림하는 이유와 같다). 정각 초로 내리지 않는다: 카드 · 채팅 바닥 시계의 0초(서버 기준점)는 ms 자리가 있어, 내리면 카드로
 * 정확히 옮겨도 채팅이 카드보다 그만큼 일찍 잘리고 바닥이 1초 이르게 섰다(리뷰 2라운드)
 */
export function nextLivePosition(
  prev: LivePosition | null,
  atMs: number,
  edgeMs: number,
  behindSeconds: number,
): LivePosition {
  const at = Math.round(atMs);
  if (
    prev !== null &&
    Math.floor(prev.atMs / 1000) === Math.floor(at / 1000) &&
    prev.behindSeconds === behindSeconds
  ) {
    return prev;
  }
  return { atMs: at, edgeAtMs: Math.round(edgeMs), behindSeconds };
}
