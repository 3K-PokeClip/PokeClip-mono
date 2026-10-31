// 영상이 끊겨 기다리는 동안(버퍼링) 스피너를 띄울지 정한다(POK-439). 영상 노드의 미디어 이벤트만 본다: 재생 훅 밖에 두어
// hls.js · 네이티브 · 녹화 · 재생 세션 · 편집기가 이 코드 하나를 쓴다. 소스가 바뀌면 브라우저가 emptied를 내서 저절로 내려간다.

/** 이만큼 기다려도 안 풀리면 띄운다. 영상 위치를 옮길 때마다 잠깐 튀는 waiting은 띄우지 않는다 */
export const BUFFERING_DELAY_MS = 400;

/** HAVE_FUTURE_DATA — 받아 둔 영상으로 재생이 이어진다 */
const CAN_KEEP_PLAYING = 3;

const WAIT_EVENTS = ['waiting', 'stalled'] as const;
// loadeddata는 넣지 않는다: 첫 프레임만 받아도 와서 재생은 아직 멎어 있다. 재생이 이어지면 canplay · playing이 온다
const CLEAR_EVENTS = ['playing', 'canplay', 'pause', 'ended', 'emptied', 'error'] as const;

/** 끊긴 상태가 바뀔 때만 알린다. 돌려주는 함수로 멈춘다 */
export function watchBuffering(
  video: HTMLVideoElement,
  onChange: (buffering: boolean) => void,
): () => void {
  let timer: number | undefined;
  let shown = false;
  const show = (next: boolean) => {
    if (next === shown) return;
    shown = next;
    onChange(next);
  };
  const clear = () => {
    window.clearTimeout(timer);
    timer = undefined;
    show(false);
  };
  const wait = () => {
    // 이어 오는 이벤트가 표시를 늦추면 안 된다: 끊기는 동안 waiting · stalled는 번갈아 온다
    if (shown || timer !== undefined) return;
    timer = window.setTimeout(() => {
      timer = undefined;
      // stalled는 받아 둔 영상으로 재생이 이어지는 중에도 온다. 그때 띄우면 내릴 이벤트가 오지 않아 스피너가 붙박인다
      if (!video.paused && !video.ended && video.readyState < CAN_KEEP_PLAYING) show(true);
    }, BUFFERING_DELAY_MS);
  };
  for (const event of WAIT_EVENTS) video.addEventListener(event, wait);
  for (const event of CLEAR_EVENTS) video.addEventListener(event, clear);
  return () => {
    window.clearTimeout(timer);
    for (const event of WAIT_EVENTS) video.removeEventListener(event, wait);
    for (const event of CLEAR_EVENTS) video.removeEventListener(event, clear);
  };
}
