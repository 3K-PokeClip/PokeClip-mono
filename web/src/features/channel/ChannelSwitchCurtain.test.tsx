import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiFetch } from '@/api/client';
import {
  ChannelSwitchCurtain,
  FADE_OUT_MS,
  FINISH_MS,
  HOLD_MS,
  MAX_MS,
  QUIET_MS,
} from './ChannelSwitchCurtain';
import { COVER_IN_MS, useSwitchCurtain } from './switchCurtain';

beforeEach(() => {
  vi.useFakeTimers({
    toFake: [
      'setTimeout',
      'clearTimeout',
      'setInterval',
      'clearInterval',
      'Date',
      'requestAnimationFrame',
      'cancelAnimationFrame',
    ],
  });
  useSwitchCurtain.getState().reset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  // 덮개가 떠 있는 채로 끝난 시험도 있다: 지우는 것도 렌더를 부르니 act 안에서
  act(() => useSwitchCurtain.getState().reset());
  fetchMock.mockReset();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const fetchMock = vi.fn<typeof fetch>();

function setup() {
  render(<ChannelSwitchCurtain />);
}

/** 조금씩 흘린다: 막대·걷기는 렌더마다 다음 틱을 거는 구조라 한 번에 흘리면 틱이 하나만 돈다 */
function run(ms: number) {
  for (let t = 0; t < ms; t += 50) {
    act(() => {
      vi.advanceTimersByTime(50);
    });
  }
}

function cover(commit = vi.fn()) {
  act(() =>
    useSwitchCurtain.getState().cover({ name: '별하', relation: 'EDITOR', photo: null }, commit),
  );
  return commit;
}

/** 풀어 줄 때까지 나가 있는 API 요청(새 채널 화면이 부르는 목록 흉내). 돌려주는 함수가 응답을 보낸다 */
function pendingRequest(): () => Promise<void> {
  let respond: (res: Response) => void = () => {};
  fetchMock.mockImplementationOnce(
    () =>
      new Promise<Response>((r) => {
        respond = r;
      }),
  );
  let done: Promise<unknown> = Promise.resolve();
  act(() => {
    done = apiFetch('/api/list').catch(() => undefined);
  });
  return () =>
    act(async () => {
      respond(new Response('[]', { status: 200 }));
      await done;
    });
}

const progress = () => Number(screen.getByRole('progressbar').getAttribute('aria-valuenow'));
const shown = () => screen.queryByText('별하 채널로 전환 중') !== null;

describe('ChannelSwitchCurtain', () => {
  it('덮개를 세우면 새 채널과 역할을 보이고, 다 덮은 뒤에야 채널을 바꾼다', () => {
    setup();
    const commit = cover();

    expect(screen.getByText('별하 채널로 전환 중')).toBeInTheDocument();
    expect(screen.getByText('편집자로 들어가요')).toBeInTheDocument();
    run(COVER_IN_MS - 50);
    expect(commit).not.toHaveBeenCalled();
    run(50);
    expect(commit).toHaveBeenCalledTimes(1);
  });

  it('불러올 것이 없으면 최소 시간 뒤 막대를 끝까지 채우고 걷고, 「전환했어요」를 알린다', () => {
    setup();
    cover();

    run(HOLD_MS - 100);
    expect(shown()).toBe(true);
    expect(progress()).toBeLessThan(100);
    run(150 + FINISH_MS);
    expect(progress()).toBe(100);
    run(FADE_OUT_MS + 50);

    expect(shown()).toBe(false);
    expect(useSwitchCurtain.getState().curtain).toBeNull();
    expect(screen.getByRole('status')).toHaveTextContent('별하 채널로 전환했어요');
  });

  it('불러오는 동안은 걷지 않고, 길어질수록 막대가 나아가되 다 불러오기 전엔 끝까지 차지 않는다', async () => {
    setup();
    cover();
    run(COVER_IN_MS);
    const finish = pendingRequest();

    run(HOLD_MS);
    const early = progress();
    run(3000);
    const later = progress();

    expect(shown()).toBe(true);
    expect(later).toBeGreaterThan(early);
    expect(later).toBeLessThanOrEqual(95);

    await finish();
    run(QUIET_MS + 100 + FINISH_MS + FADE_OUT_MS + 50);
    expect(useSwitchCurtain.getState().curtain).toBeNull();
  });

  it('요청을 차례로 잇는 화면은 요청 사이 0개 틈에 걷지 않는다: 잠잠한 시간이 지나야 걷는다', async () => {
    setup();
    cover();
    run(COVER_IN_MS);
    const first = pendingRequest();
    run(HOLD_MS);

    // 첫 쪽이 끝나고 다음 쪽을 부르기까지 틈(QUIET_MS보다 짧게)
    await first();
    run(QUIET_MS - 100);
    expect(shown()).toBe(true);
    const second = pendingRequest();
    run(QUIET_MS + 100);
    expect(shown()).toBe(true);
    expect(progress()).toBeLessThan(100);

    await second();
    run(QUIET_MS + 100 + FINISH_MS + FADE_OUT_MS + 50);
    expect(useSwitchCurtain.getState().curtain).toBeNull();
  });

  it('요청이 하나씩 끝나면 그만큼 막대가 나아간다', async () => {
    setup();
    cover();
    run(COVER_IN_MS);
    const finishes = [1, 2, 3, 4].map(() => pendingRequest());
    run(HOLD_MS);
    const before = progress();

    for (const finish of finishes.slice(0, 3)) await finish();
    run(100);

    // 4개 중 3개: 시간만으로 갈 자리보다 앞선다
    expect(progress()).toBeGreaterThanOrEqual(65);
    expect(progress()).toBeGreaterThan(before);
    expect(shown()).toBe(true);
  });

  it('덮는 동안 포커스를 덮개로 옮기고 Tab을 막는다: 아래 화면을 키보드로 못 건드리고, 걷히면 포커스를 돌려준다', () => {
    render(
      <>
        <button type="button">프로필</button>
        <ChannelSwitchCurtain />
      </>,
    );
    const trigger = screen.getByRole('button', { name: '프로필' });
    trigger.focus();
    cover();

    const curtain = screen.getByRole('dialog', { name: '별하 채널로 전환 중' });
    expect(curtain).toHaveFocus();
    // 기본 동작(다음 칸으로 포커스 옮기기)을 막았다
    expect(fireEvent.keyDown(curtain, { key: 'Tab' })).toBe(false);
    expect(curtain).toHaveFocus();

    run(HOLD_MS + 100 + FINISH_MS + FADE_OUT_MS + 50);
    expect(useSwitchCurtain.getState().curtain).toBeNull();
    expect(trigger).toHaveFocus();
  });

  it('응답 없이 멈춘 요청은 MAX_MS에서 끊고 걷는다: 화면을 막아 두지 않는다', () => {
    setup();
    cover();
    run(COVER_IN_MS);
    pendingRequest();

    run(MAX_MS - 500);
    expect(shown()).toBe(true);
    run(500 + 100 + FINISH_MS + FADE_OUT_MS + 50);
    expect(useSwitchCurtain.getState().curtain).toBeNull();
  });

  it('덮기 전부터 나가 있던 요청은 세지 않는다: 다른 화면의 느린 갱신이 전환 화면을 붙잡지 않는다', () => {
    setup();
    pendingRequest();
    cover();

    run(HOLD_MS + 100 + FINISH_MS + FADE_OUT_MS + 50);
    expect(useSwitchCurtain.getState().curtain).toBeNull();
  });
});
