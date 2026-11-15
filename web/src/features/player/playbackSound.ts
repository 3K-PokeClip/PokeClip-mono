// 재생기의 소리 상태(무음 · 음량)를 읽고 쓴다(POK-547). 다시보기로 넘기면 영상 노드가 새로 서므로, 옛 노드의 값을 읽어
// 새 노드의 첫 재생 전에 쓴다. 새 노드의 재생은 클릭 뒤 비동기로 시작해 브라우저가 소리 켠 재생을 막을 수 있다 — 그때는
// 소리만 끄고 한 번 더 튼다. 영상 노드만 다루는 DOM 코드라 React 를 쓰지 않는다.

/** 재생기의 소리 상태 — volume 은 0–100(UI 단위 정수) */
export interface PlayerSound {
  muted: boolean;
  volume: number;
}

/** 영상 노드의 지금 소리 상태. 노드가 없으면 null */
export function readSound(video: HTMLVideoElement | null): PlayerSound | null {
  if (video === null) return null;
  return { muted: video.muted, volume: Math.round(video.volume * 100) };
}

/** 소리 상태를 영상 노드에 쓴다 — 첫 play() 전에 불러야 브라우저가 그 값으로 재생 허용을 정한다 */
export function applySound(video: HTMLVideoElement, sound: PlayerSound): void {
  video.muted = sound.muted;
  video.volume = sound.volume / 100;
}

/** play() 거절 사유 이름(DOMException.name). 다른 꼴의 값이면 null */
function rejectionName(error: unknown): string | null {
  if (typeof error !== 'object' || error === null || !('name' in error)) return null;
  return typeof error.name === 'string' ? error.name : null;
}

/**
 * 첫 재생. soundFallback 이면 NotAllowedError(브라우저가 소리 켠 자동 재생을 막음)에서 음량은 두고 muted=true 로 한 번
 * 더 시도한다. 다시 거절되면 그대로 둔다 — 재생 단추가 남는다(playing 은 video 사건으로만 바뀐다). AbortError(소스 교체로
 * 취소됨) 등 그 밖 거절은 다시 시도하지 않는다
 */
export function playFirst(video: HTMLVideoElement, soundFallback: boolean): void {
  video.play().catch((error: unknown) => {
    if (!soundFallback || rejectionName(error) !== 'NotAllowedError') return;
    console.info('[player] 소리 켠 재생이 막혀 소리를 끄고 다시 틀어요');
    video.muted = true;
    video.play().catch(() => {});
  });
}
