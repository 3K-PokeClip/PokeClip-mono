import { useEffect, useState } from 'react';

// 편집기 영상 상태(POK-492)의 「지금」. 상태 말 가운데 시각이 정하는 것 — 예약 공개 시각이 지났나(「다시 올리면 바로
// 공개돼요」) · 단계 시각에 날짜를 붙이나 — 은 상태 확인 답이 없어도 그 시각이 오면 바뀌어야 한다. 답이 올 때만 다시 계산하면,
// 업로드가 실패해 확인이 멈춘 뒤 예약 시각이 지나도 「그 시각에 공개로 바뀌어요」가 남아 「다시 올리기」가 바로 공개되는 것을
// 숨겼다(PR #454 3차 리뷰). 매초 돌지 않고 말이 바뀌는 다음 순간에만 깬다.

/** 말이 시각 때문에 다음에 바뀌는 순간(ms): 아직 안 온 예약 공개 시각과 다음 자정 가운데 이른 쪽 */
export function nextClockChange(now: Date, publishAt: string | null): number {
  const midnight = new Date(now);
  midnight.setHours(24, 0, 0, 0);
  const at = publishAt === null ? Number.NaN : Date.parse(publishAt);
  return at > now.getTime() && at < midnight.getTime() ? at : midnight.getTime();
}

/** 영상 상태를 그릴 「지금」. 그릴 영상이 있을 때(active)만 깬다 */
export function useClipStatusClock(active: boolean, publishAt: string | null): Date {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    if (!active) return undefined;
    // 쥔 「지금」이 낡았으면(그 사이 지난 순간이 있으면) 지연이 0으로 잘려 곧바로 깬다
    const t = globalThis.setTimeout(
      () => setNow(new Date()),
      Math.max(0, nextClockChange(now, publishAt) - Date.now()) + 1,
    );
    return () => globalThis.clearTimeout(t);
  }, [active, publishAt, now]);
  return now;
}
