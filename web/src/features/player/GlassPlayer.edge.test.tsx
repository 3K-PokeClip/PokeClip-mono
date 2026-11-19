import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastProvider } from '@/ui';
import { GlassPlayer } from './GlassPlayer';

// 시크바 손잡이 · 낭독과 LIVE 단추가 「끝에 있음」 을 같은 판정으로 보이는가(POK-583 c3 · 계획 D5). 실재생(src) 갈래를 실제로
// 그린다 — 재생기의 atEdge 하나가 두 자리에 함께 가야 보이는 숫자(steadySeconds)와 끝 판정이 갈려도 둘이 어긋나지 않는다.

interface Details {
  fragments: Array<{ start: number; duration: number; programDateTime: number }>;
  fragmentStart: number;
  targetduration: number;
  live: boolean;
}

const fake = vi.hoisted(() => ({ instances: [] as FakeHls[] }));

vi.mock('hls.js', () => ({ default: FakeHls }));

class FakeHls {
  static isSupported = () => true;
  static Events = { LEVEL_UPDATED: 'hlsLevelUpdated' };
  readonly handlers = new Map<string, ((event: string, data: unknown) => void)[]>();
  latestLevelDetails: Details | null = null;
  liveSyncPosition: number | null = null;
  constructor() {
    fake.instances.push(this);
  }
  on(event: string, handler: (event: string, data: unknown) => void) {
    this.handlers.set(event, [...(this.handlers.get(event) ?? []), handler]);
  }
  trigger(event: string, data: { details: Details }) {
    this.latestLevelDetails = data.details;
    for (const h of this.handlers.get(event) ?? []) {
      h(event, data);
    }
  }
  attachMedia() {}
  loadSource() {}
  startLoad() {}
  destroy() {}
}

const PDT0 = Date.parse('2026-10-25T05:00:00.000Z');
/** 라이브 재생기가 서는 라이브 끝(영상 초) */
const LIVE_EDGE_S = 38;

/** 영상 0초부터 4초 조각 열 개(PDT0 부터) */
function details(): Details {
  const fragments = Array.from({ length: 10 }, (_, i) => ({
    start: i * 4,
    duration: 4,
    programDateTime: PDT0 + i * 4_000,
  }));
  return { fragments, fragmentStart: 0, targetduration: 4, live: true };
}

function lastHls(): FakeHls {
  const h = fake.instances[fake.instances.length - 1];
  if (h === undefined) throw new Error('재생기가 서지 않았다');
  return h;
}

/** 실재생 플레이어를 그리고 「라이브 끝에서 s 초 뒤에 두고 그리기」 를 돌려준다 */
async function renderAt() {
  const { container } = render(
    <ToastProvider>
      <GlassPlayer channelName="채널" viewersNote="1명" embed src="https://m/live/k/index.m3u8" />
    </ToastProvider>,
  );
  await act(async () => {
    await vi.dynamicImportSettled();
  });
  lastHls().liveSyncPosition = LIVE_EDGE_S;
  act(() => lastHls().trigger('hlsLevelUpdated', { details: details() }));
  const video = container.querySelector('video')!;
  return (s: number) =>
    act(() => {
      video.currentTime = LIVE_EDGE_S - s;
      video.dispatchEvent(new Event('timeupdate'));
    });
}

/** 손잡이의 가로 자리(%) — 트랙 안에서 left 를 가진 요소는 손잡이 하나다 */
function thumbPct(): number {
  const slider = screen.getByRole('slider', { name: '라이브 탐색' });
  const thumb = Array.from(slider.querySelectorAll<HTMLElement>('div')).find(
    (el) => el.style.left !== '',
  );
  return parseFloat(thumb?.style.left ?? '');
}

beforeEach(() => {
  fake.instances.length = 0;
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
  vi.spyOn(HTMLMediaElement.prototype, 'paused', 'get').mockReturnValue(false);
  vi.spyOn(HTMLMediaElement.prototype, 'readyState', 'get').mockReturnValue(4);
  vi.spyOn(HTMLMediaElement.prototype, 'seekable', 'get').mockImplementation(
    () => ({ length: 1, start: () => 0, end: () => 40 }) as unknown as TimeRanges,
  );
});
afterEach(() => {
  vi.restoreAllMocks();
});

describe('GlassPlayer 끝 판정 — 시크바와 LIVE 단추가 같은 판정이다(POK-583 떨림 ③)', () => {
  it('시차 2.4초는 끝이다 — 손잡이는 오른쪽 끝이고 둘 다 「실시간」 이다', async () => {
    const paintAt = await renderAt();
    paintAt(2.4);
    expect(thumbPct()).toBe(100);
    expect(screen.getByRole('slider', { name: '라이브 탐색' })).toHaveAttribute(
      'aria-valuetext',
      '실시간',
    );
    expect(screen.getByRole('button', { name: '실시간으로 이동' })).toHaveTextContent(/^실시간$/);
  });

  it('보이는 숫자가 2인 채 원시 시차가 3초가 되면 둘 다 끝이 아니다', async () => {
    const paintAt = await renderAt();
    paintAt(2.4);
    paintAt(3);
    expect(thumbPct()).toBeLessThan(100);
    expect(screen.getByRole('slider', { name: '라이브 탐색' })).toHaveAttribute(
      'aria-valuetext',
      '실시간에서 -00:02',
    );
    expect(screen.getByRole('button', { name: '실시간으로 이동' })).toHaveTextContent(
      '-00:02 · 실시간으로',
    );
  });
});
