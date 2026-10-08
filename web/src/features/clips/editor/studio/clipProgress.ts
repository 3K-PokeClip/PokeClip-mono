import type { ClipSnapshot } from '@/api/clipEditor';

// 편집기 헤더가 말하는 영상의 진행(POK-291): 만드는 중 N% → 올리는 중 → 업로드됨 / 업로드 실패.
// 업로드 정보를 실어 주문하면 렌더가 끝나는 대로 clip이 올리므로, 상태 확인(폴링)도 업로드가 끝날 때까지 간다.

/** 헤더 문구 */
export function clipLabel(clip: ClipSnapshot): string {
  switch (clip.status) {
    case 'queued':
      return `영상 #${clip.id} 주문됨`;
    case 'rendering':
      return `영상 #${clip.id} 만드는 중 ${clip.progress?.percent ?? 0}%`;
    case 'rendered':
      switch (clip.upload?.status) {
        case undefined:
          // clip은 완성과 업로드 줄을 한 트랜잭션에서 만든다. 정보가 있는데 줄이 없으면 자동 업로드를 건너뛴 것이다
          // (업로드가 꺼졌거나 같은 판이 이미 올라갔다). 「올리는 중」이라고 하면 끝나지 않는 대기가 된다
          return clip.uploadRequest
            ? `영상 #${clip.id} 완성 · 업로드는 시작되지 않았어요`
            : `영상 #${clip.id} 완성`;
        case 'uploaded':
          return `영상 #${clip.id} 업로드됨`;
        case 'failed':
          return `영상 #${clip.id} 업로드 실패(${clip.upload?.error?.code ?? '?'})`;
        case 'checking':
          return `영상 #${clip.id} 업로드 확인 필요`;
        default:
          return `영상 #${clip.id} 유튜브에 올리는 중`;
      }
    case 'failed':
      return `영상 #${clip.id} 실패(${clip.error?.code ?? '?'})`;
  }
}

/**
 * 더 물을 것이 없는가. 렌더가 실패했거나, 완성됐는데 업로드 줄이 없거나, 업로드가 끝났으면(올림·실패·확인 필요) 멈춘다.
 * 확인 필요는 사람이 채널을 봐야 풀린다(보관함과 같다).
 */
export function clipSettled(clip: ClipSnapshot): boolean {
  if (clip.status === 'failed') return true;
  if (clip.status !== 'rendered') return false;
  const upload = clip.upload;
  // 완성인데 줄이 없으면 더 기다려도 생기지 않는다(위 clipLabel 참고)
  if (upload == null) return true;
  return upload.status === 'uploaded' || upload.status === 'failed' || upload.status === 'checking';
}
