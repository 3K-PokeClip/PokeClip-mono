import { act, createRef, type ComponentProps } from 'react';
import { createEvent, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastProvider } from '@/ui';
import { GlassPlayer, type GlassPlayerController } from '@/features/player/GlassPlayer';
import type { PlayerMark } from '@/features/player/PlayerControls';
import type { PlayerSimulationOptions } from '@/features/player/usePlayerSimulation';
import { recordingTimeline } from '@/features/player/recordingTimeline';
import { openSession } from '@/features/player/rewindSwitch';
import { sessionView, type SessionView } from '@/features/player/sessionView';
import type { PlaybackSession } from '@/features/player/usePlaybackSession';

type PanelProps = Pick<
  ComponentProps<typeof GlassPlayer>,
  'chatPanelOpen' | 'onToggleChatPanel' | 'controllerRef'
>;

/**
 * jsdom엔 requestFullscreen이 없다 — 전체 화면 「결과」를 흉내 낸다: fullscreenElement를 꽂고
 * fullscreenchange를 쏜다. 플레이어가 요청이 아니라 결과를 듣는지가 이 흉내로 검증된다.
 */
function setFullscreenElement(el: Element | null) {
  Object.defineProperty(document, 'fullscreenElement', { configurable: true, get: () => el });
  fireEvent(document, new Event('fullscreenchange'));
}

// jsdom엔 fullscreenElement가 원래 없다 — 꽂아 둔 게터를 지워 다음 테스트가 깨끗한 document를 본다
afterEach(() => {
  Reflect.deleteProperty(document, 'fullscreenElement');
});

function renderPlayer(simulationOptions?: PlayerSimulationOptions, panelProps?: PanelProps) {
  return render(
    <ToastProvider>
      <GlassPlayer
        channelName="게임하는너구리"
        viewersNote="1,842명 시청 중"
        embed
        simulate
        simulationOptions={simulationOptions}
        {...panelProps}
      />
    </ToastProvider>,
  );
}

describe('GlassPlayer', () => {
  it('엣지에서는 LIVE 복귀 버튼이 "실시간"을 보여준다', () => {
    renderPlayer();
    expect(screen.getByRole('button', { name: '실시간으로 이동' })).toHaveTextContent('실시간');
  });

  it('뒤로 시킹된 상태에선 -MM:SS 시차를 보여주고, 클릭하면 실시간으로 복귀한다', async () => {
    const user = userEvent.setup();
    renderPlayer({ initialBehindSeconds: 83 });

    const livePill = screen.getByRole('button', { name: '실시간으로 이동' });
    expect(livePill).toHaveTextContent('-01:23 · 실시간으로');

    await user.click(livePill);
    expect(livePill).toHaveTextContent('실시간');
    expect(livePill).not.toHaveTextContent('실시간으로');
  });

  describe('멈춰 있을 때의 컨트롤(POK-518)', () => {
    beforeEach(() => {
      vi.useFakeTimers();
    });
    afterEach(() => {
      vi.useRealTimers();
    });

    function pausedPlayer() {
      const { container } = renderPlayer();
      fireEvent.click(screen.getByRole('button', { name: '일시정지' }));
      return container.querySelector('[data-controls]')!;
    }
    const wait = (ms: number) =>
      act(() => {
        vi.advanceTimersByTime(ms);
      });

    it('마우스가 플레이어 밖으로 나가면 숨는다 — 멈춰 있어도 영상을 가리지 않는다', () => {
      const player = pausedPlayer();
      fireEvent.mouseMove(player);

      fireEvent.mouseLeave(player);

      expect(player).toHaveAttribute('data-controls', 'hidden');
    });

    it('마우스가 위에 있는 동안은 움직이지 않아도 계속 보인다', () => {
      const player = pausedPlayer();
      fireEvent.mouseMove(player);

      wait(10_000);

      expect(player).toHaveAttribute('data-controls', 'visible');
    });

    it('호버 없이 깨우면(키 · 터치) 잠깐 보였다가 숨는다 — 안 그러면 다음 호버까지 남는다', () => {
      const player = pausedPlayer();
      fireEvent.pointerDown(player);
      expect(player).toHaveAttribute('data-controls', 'visible');

      wait(3000);

      expect(player).toHaveAttribute('data-controls', 'hidden');
    });

    it('재생 중에는 마우스가 위에 있어도 움직임이 없으면 숨는다 — 지금과 같다', () => {
      const { container } = renderPlayer();
      const player = container.querySelector('[data-controls]')!;
      fireEvent.mouseMove(player);

      wait(3000);

      expect(player).toHaveAttribute('data-controls', 'hidden');
    });
  });

  describe('재생 막대 말풍선(POK-518)', () => {
    it('호버하면 위쪽에 버튼 이름과 단축키가 뜬다 — 낭독은 버튼 이름이 맡는다', () => {
      renderPlayer();

      for (const text of [
        '일시정지 (Space / k)',
        '음소거 (m)',
        '실시간으로 이동 (End)',
        '전체 화면 (f)',
      ]) {
        expect(screen.getByText(text)).toHaveAttribute('aria-hidden', 'true');
      }
    });

    it('채팅을 숨겼을 때의 채팅 열기 버튼도 말풍선이 뜬다 — 플레이어 맨 위라 아래쪽으로 뜬다', () => {
      renderPlayer(undefined, { chatPanelOpen: false, onToggleChatPanel: vi.fn() });

      const hint = screen.getByText('채팅 열기');
      expect(hint).toHaveAttribute('aria-hidden', 'true');
      expect(hint).toHaveAttribute('data-side', 'bottom');
    });

    it('단축키가 없는 버튼은 이름만 뜬다', () => {
      renderPlayer();

      expect(screen.getByText('미니 플레이어')).toBeInTheDocument();
    });

    it('말풍선은 지금 상태의 이름을 따른다', () => {
      renderPlayer();

      fireEvent.click(screen.getByRole('button', { name: '음소거' }));
      expect(screen.getByText('음소거 해제 (m)')).toBeInTheDocument();

      fireEvent.click(screen.getByRole('button', { name: '일시정지' }));
      expect(screen.getByText('재생 (Space / k)')).toBeInTheDocument();
    });

    it('전체 화면이면 「전체 화면 종료」다', () => {
      const { container } = renderPlayer();

      act(() => setFullscreenElement(container.querySelector('[data-controls]')));

      expect(screen.getByRole('button', { name: '전체 화면 종료' })).toBeInTheDocument();
      expect(screen.getByText('전체 화면 종료 (f)')).toBeInTheDocument();
    });
  });

  describe('단축키(POK-518)', () => {
    /** 플레이어 밖에서 눌렀다 — 마우스만 올리고 누르면 포커스는 페이지에 있다 */
    const press = (code: string, init: KeyboardEventInit = {}, target: Element = document.body) =>
      fireEvent.keyDown(target, { code, ...init });
    const button = (name: string) => screen.getByRole('button', { name });

    /** 테스트가 끝나면 지워지는 플레이어 밖 요소 */
    function outside(html: string) {
      const host = document.createElement('div');
      host.innerHTML = html;
      document.body.append(host);
      return { el: host.firstElementChild!, remove: () => host.remove() };
    }

    it('m은 음소거를 뒤집는다 — 플레이어를 누르지 않아도 듣는다', () => {
      renderPlayer();

      press('KeyM');
      expect(button('음소거 해제')).toBeInTheDocument();

      press('KeyM');
      expect(button('음소거')).toBeInTheDocument();
    });

    it('음량 0에서 m은 음량을 되살린다 — 단추를 누른 것과 같다', () => {
      renderPlayer();
      const slider = screen.getByRole('slider', { name: '볼륨' });
      fireEvent.pointerDown(slider);
      fireEvent.change(slider, { target: { value: 0 } });

      press('KeyM');

      expect(slider).toHaveValue('70');
    });

    it.each(['Space', 'KeyK'])('%s는 재생을 뒤집고 페이지를 내리지 않는다', (code) => {
      renderPlayer();

      const notPrevented = press(code);

      expect(notPrevented).toBe(false);
      expect(button('재생')).toBeInTheDocument();
    });

    it('f는 전체 화면을 요청한다', () => {
      const requestFullscreen = vi.fn(() => Promise.resolve());
      Object.defineProperty(HTMLElement.prototype, 'requestFullscreen', {
        value: requestFullscreen,
        configurable: true,
      });
      try {
        renderPlayer();

        press('KeyF');

        expect(requestFullscreen).toHaveBeenCalledTimes(1);
      } finally {
        Reflect.deleteProperty(HTMLElement.prototype, 'requestFullscreen');
      }
    });

    it('플레이어 슬라이더를 만진 뒤에도 듣는다 — 시크바를 누르고 바로 Space를 누르는 흐름이다', () => {
      renderPlayer();

      press('Space', {}, screen.getByRole('slider', { name: '라이브 탐색' }));
      expect(button('재생')).toBeInTheDocument();

      press('KeyM', {}, screen.getByRole('slider', { name: '볼륨' }));
      expect(button('음소거 해제')).toBeInTheDocument();
    });

    it.each([
      ['입력창', '<input />'],
      ['글상자', '<textarea></textarea>'],
      ['편집 영역', '<div contenteditable="true"></div>'],
      ['바깥 슬라이더', '<div role="slider" tabindex="0"></div>'],
      ['대화상자', '<div role="dialog"><button type="button">확인</button></div>'],
      ['메뉴', '<div role="menu"><button type="button">항목</button></div>'],
    ])('%s 안에서 누른 키는 그쪽 것이다', (_name, html) => {
      renderPlayer();
      const { el, remove } = outside(html);
      try {
        const target = el.querySelector('button') ?? el;
        expect(press('KeyM', {}, target)).toBe(true);
        expect(press('Space', {}, target)).toBe(true);
        expect(button('음소거')).toBeInTheDocument();
        expect(button('일시정지')).toBeInTheDocument();
      } finally {
        remove();
      }
    });

    it('포커스가 있는 버튼 위 Space는 그 버튼 몫이다 — 키보드로 고른 버튼을 눌러야 한다', () => {
      renderPlayer();
      const { el, remove } = outside('<button type="button">카드</button>');
      try {
        expect(press('Space', {}, el)).toBe(true);
        expect(press('Space', {}, button('음소거'))).toBe(true);
        expect(button('일시정지')).toBeInTheDocument();
      } finally {
        remove();
      }
    });

    it('포커스가 있는 버튼 위에서도 글자 단축키는 듣는다 — 버튼에 기본 동작이 없다', () => {
      renderPlayer();

      press('KeyM', {}, button('일시정지'));

      expect(button('음소거 해제')).toBeInTheDocument();
    });

    it('이미 누가 처리한 키는 건드리지 않는다', () => {
      renderPlayer();
      const handled = createEvent.keyDown(document.body, { code: 'KeyM' });
      handled.preventDefault();

      fireEvent(document.body, handled);

      expect(button('음소거')).toBeInTheDocument();
    });

    it('마우스로 누른 버튼에 포커스가 남지 않는다 — 남으면 다음 Space가 그 버튼을 또 누른다. 슬라이더는 끌어야 해서 뺀다', () => {
      renderPlayer();

      expect(fireEvent.mouseDown(button('음소거'))).toBe(false);
      expect(fireEvent.mouseDown(screen.getByRole('slider', { name: '볼륨' }))).toBe(true);
    });

    it('마우스로 버튼을 누르면 포커스가 플레이어로 온다 — 바깥에 남으면 화살표 시킹이 죽고 Space가 바깥 버튼을 누른다', () => {
      const { container } = renderPlayer();
      const { el, remove } = outside('<button type="button">카드</button>');
      try {
        (el as HTMLElement).focus();

        fireEvent.pointerDown(button('일시정지'));
        fireEvent.mouseDown(button('일시정지'));

        expect(container.querySelector('[data-controls]')).toHaveFocus();
        fireEvent.keyDown(document.activeElement!, { key: 'ArrowLeft' });
        expect(screen.getByRole('slider', { name: '라이브 탐색' })).toHaveAttribute(
          'aria-valuenow',
          '-10',
        );
      } finally {
        remove();
      }
    });

    it('슬라이더와 메뉴를 여는 버튼은 포커스를 자기가 받는다', () => {
      const { container } = renderPlayer();
      const player = container.querySelector('[data-controls]');

      fireEvent.pointerDown(screen.getByRole('slider', { name: '볼륨' }));
      expect(player).not.toHaveFocus();

      fireEvent.pointerDown(button('설정'));
      expect(player).not.toHaveFocus();
      expect(fireEvent.mouseDown(button('설정'))).toBe(true);
    });

    it('플레이어가 내려가면 듣지 않는다', () => {
      const { unmount } = renderPlayer();
      unmount();

      expect(press('KeyM')).toBe(true);
    });
  });

  describe('음량(POK-517)', () => {
    const slider = () => screen.getByRole('slider', { name: '볼륨' });
    /** 슬라이더를 잡아 그 값들을 지나 놓는다 — 끌면 지나는 값마다 change가 온다 */
    function drag(...values: number[]) {
      fireEvent.pointerDown(slider());
      for (const value of values) fireEvent.change(slider(), { target: { value } });
      fireEvent.pointerUp(slider());
    }

    it('스피커 아이콘의 소리 줄기가 음량을 따라 바뀐다 — 끄는 동안 바로 바뀐다', () => {
      const { container } = renderPlayer();

      fireEvent.change(slider(), { target: { value: 30 } });
      expect(container.querySelector('.lucide-volume-1')).not.toBeNull();
      expect(container.querySelector('.lucide-volume-2')).toBeNull();

      fireEvent.change(slider(), { target: { value: 80 } });
      expect(container.querySelector('.lucide-volume-2')).not.toBeNull();
      expect(container.querySelector('.lucide-volume-1')).toBeNull();
    });

    it('음량이 0이면 X 아이콘과 「음소거 해제」를 보인다', () => {
      const { container } = renderPlayer();
      expect(container.querySelector('.lucide-volume-2')).not.toBeNull();

      drag(0);

      expect(screen.getByRole('button', { name: '음소거 해제' })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: '음소거' })).toBeNull();
      expect(container.querySelector('.lucide-volume-x')).not.toBeNull();
      expect(container.querySelector('.lucide-volume-2')).toBeNull();
    });

    /** 키보드로 들어온 포커스인가(:focus-visible). jsdom의 판정은 앞 시험의 사건까지 따라가 순서를 탄다 */
    const focusVisible = (input: HTMLElement, visible: boolean) =>
      vi.spyOn(input, 'matches').mockReturnValue(visible);

    it('키보드로 0까지 내린 뒤 되살리면 들어올 때의 음량이다 — 끌기와 같다', () => {
      renderPlayer();
      const input = slider();
      fireEvent.change(input, { target: { value: 40 } });
      focusVisible(input, true);

      act(() => input.focus());
      for (const value of [39, 1, 0]) {
        fireEvent.keyDown(input, { key: 'ArrowDown' });
        fireEvent.change(input, { target: { value } });
      }
      fireEvent.click(screen.getByRole('button', { name: '음소거 해제' }));

      expect(input).toHaveValue('40');
    });

    it('마우스로 처음 누른 값은 기억하지 않는다 — 크롬은 값을 바꾼 뒤에 포커스를 준다', () => {
      renderPlayer();
      const input = slider();
      fireEvent.change(input, { target: { value: 40 } });
      focusVisible(input, false);

      fireEvent.pointerDown(input);
      fireEvent.change(input, { target: { value: 20 } });
      act(() => input.focus());
      fireEvent.change(input, { target: { value: 0 } });
      fireEvent.click(screen.getByRole('button', { name: '음소거 해제' }));

      expect(input).toHaveValue('40');
    });

    it('끌어내려 0을 만든 뒤 단추를 누르면 끌기 전 음량으로 돌아온다 — 0 바로 앞 값(1)이 아니다', () => {
      renderPlayer();

      drag(40, 1, 0);
      fireEvent.click(screen.getByRole('button', { name: '음소거 해제' }));

      expect(slider()).toHaveValue('70');
      expect(screen.getByRole('button', { name: '음소거' })).toBeInTheDocument();
    });

    it('이미 낮춰 둔 음량이면 그 음량으로 돌아온다', () => {
      renderPlayer();
      drag(40);

      drag(1, 0);
      fireEvent.click(screen.getByRole('button', { name: '음소거 해제' }));

      expect(slider()).toHaveValue('40');
    });

    it('끌기 전 음량이 너무 작으면 기본 음량으로 돌아온다 — 켜도 안 들리면 죽은 단추다', () => {
      renderPlayer();
      drag(5);

      drag(0);
      fireEvent.click(screen.getByRole('button', { name: '음소거 해제' }));

      expect(slider()).toHaveValue('70');
    });

    it('소리가 나는 동안의 단추는 음소거이고, 음량은 그대로 남는다', () => {
      renderPlayer();

      fireEvent.click(screen.getByRole('button', { name: '음소거' }));
      expect(slider()).toHaveValue('0');

      fireEvent.click(screen.getByRole('button', { name: '음소거 해제' }));
      expect(slider()).toHaveValue('70');
    });
  });

  it('시크바는 시차를 slider 값으로 노출한다', () => {
    renderPlayer({ initialBehindSeconds: 83 });
    const slider = screen.getByRole('slider', { name: '라이브 탐색' });
    expect(slider).toHaveAttribute('aria-valuenow', '-83');
    expect(slider).toHaveAttribute('aria-valuetext', '실시간에서 -01:23');
  });

  it('시크바 범위는 되감기 창을 따른다 — 방송이 짧으면 그만큼만', () => {
    // 창을 1시간으로 고정하면 짧은 방송에서 시크바 왼쪽이 눌러도 안 가는 영역이 된다 (POK-32)
    renderPlayer({ initialUptimeSeconds: 600 });
    expect(screen.getByRole('slider', { name: '라이브 탐색' })).toHaveAttribute(
      'aria-valuemin',
      '-600',
    );
  });

  it('방송이 창보다 길면 상한은 계약값 1시간이다', () => {
    renderPlayer({ initialUptimeSeconds: 5043 });
    expect(screen.getByRole('slider', { name: '라이브 탐색' })).toHaveAttribute(
      'aria-valuemin',
      '-3600',
    );
  });

  it('플레이어 어디에 포커스가 있어도 화살표로 되감을 수 있다', () => {
    // 시크바에 Tab 포커스를 넣지 않아도 먹어야 한다 (POK-32)
    const { container } = renderPlayer();
    const player = container.querySelector('[data-controls]')!;

    fireEvent.keyDown(player, { key: 'ArrowLeft' });
    expect(screen.getByRole('slider', { name: '라이브 탐색' })).toHaveAttribute(
      'aria-valuenow',
      '-10',
    );
  });

  it('볼륨 슬라이더 위에서는 화살표가 시킹하지 않는다', () => {
    // 네이티브 input[type=range]는 preventDefault를 안 해서 타겟 검사로 걸러야 한다
    renderPlayer();
    fireEvent.keyDown(screen.getByRole('slider', { name: '볼륨' }), { key: 'ArrowLeft' });
    expect(screen.getByRole('slider', { name: '라이브 탐색' })).toHaveAttribute(
      'aria-valuenow',
      '0',
    );
  });

  it('재생 버튼을 누른 뒤에도 화살표로 되감을 수 있다', () => {
    // 버튼엔 화살표 기본 동작이 없다 — 클릭 직후 단축키가 죽으면 "전역"이 아니다
    renderPlayer();
    fireEvent.keyDown(screen.getByRole('button', { name: '일시정지' }), { key: 'ArrowLeft' });
    expect(screen.getByRole('slider', { name: '라이브 탐색' })).toHaveAttribute(
      'aria-valuenow',
      '-10',
    );
  });

  it('수정자 키 조합은 가로채지 않는다 — 브라우저 뒤로 가기가 살아 있어야 한다', () => {
    // 회귀: 전역 단축키는 영상을 한 번 클릭하면 활성화된다. Cmd+←(macOS)·Alt+←(Win)로
    // 이전 페이지에 가려던 사용자가 영상만 되감기고, preventDefault 탓에 뒤로 가기도 죽었다.
    const { container } = renderPlayer();
    const player = container.querySelector('[data-controls]')!;
    const slider = screen.getByRole('slider', { name: '라이브 탐색' });

    for (const modifier of ['metaKey', 'ctrlKey', 'altKey']) {
      const notPrevented = fireEvent.keyDown(player, { key: 'ArrowLeft', [modifier]: true });
      expect(slider).toHaveAttribute('aria-valuenow', '0');
      // 시킹하지 않았으면 기본 동작도 막지 않아야 브라우저가 뒤로 갈 수 있다
      expect(notPrevented).toBe(true);
    }
  });

  it('키 자동반복은 무시한다 — 누르고 있어도 시크가 폭주하지 않는다', () => {
    // 회귀: OS 키 반복(초당 ~30회)이 그대로 시킹돼 hls.js가 매번 버퍼를 비웠다.
    // 드래그를 "놓을 때 한 번만" 커밋한 것과 같은 이유의 방어다.
    const { container } = renderPlayer();
    const player = container.querySelector('[data-controls]')!;

    fireEvent.keyDown(player, { key: 'ArrowLeft' });
    for (let i = 0; i < 5; i += 1) {
      fireEvent.keyDown(player, { key: 'ArrowLeft', repeat: true });
    }
    // 첫 타건 1회만 반영된다 — 가드가 없으면 -60이다
    expect(screen.getByRole('slider', { name: '라이브 탐색' })).toHaveAttribute(
      'aria-valuenow',
      '-10',
    );
  });

  it('시크바에서 누른 키는 전역 단축키가 다시 처리하지 않는다', () => {
    renderPlayer();
    const slider = screen.getByRole('slider', { name: '라이브 탐색' });

    fireEvent.keyDown(slider, { key: 'ArrowLeft' });
    // 이중 처리되면 -20이 된다
    expect(slider).toHaveAttribute('aria-valuenow', '-10');
  });

  it('시크바 PageDown은 60초 뒤로 시킹한다', () => {
    renderPlayer();
    const slider = screen.getByRole('slider', { name: '라이브 탐색' });

    fireEvent.keyDown(slider, { key: 'PageDown' });
    expect(slider).toHaveAttribute('aria-valuenow', '-60');
  });

  it('재생/일시정지가 토글된다', async () => {
    const user = userEvent.setup();
    renderPlayer();

    await user.click(screen.getByRole('button', { name: '일시정지' }));
    expect(screen.getByRole('button', { name: '재생' })).toBeInTheDocument();
  });

  describe('전체 화면 수동 마킹', () => {
    function renderMarking(mark: Partial<PlayerMark> = {}) {
      const onMark = vi.fn();
      const view = render(
        <ToastProvider>
          <GlassPlayer
            channelName="게임하는너구리"
            viewersNote="1,842명 시청 중"
            embed
            simulate
            mark={{ mode: 'live', disabled: false, status: null, onMark, ...mark }}
          />
        </ToastProvider>,
      );
      const enterFullscreen = () =>
        act(() => setFullscreenElement(view.container.querySelector('[data-controls]')));
      return { onMark, enterFullscreen };
    }

    it('전체 화면이 아니면 버튼이 없다 — 영상 아래 줄에 같은 버튼이 있다', () => {
      renderMarking();

      expect(screen.queryByRole('button', { name: '수동 마킹' })).toBeNull();
      expect(screen.queryByRole('button', { name: '클립 만들기' })).toBeNull();
    });

    it('전체 화면이면 수동 마킹 버튼이 서고 누르면 마킹한다', () => {
      const { onMark, enterFullscreen } = renderMarking();

      enterFullscreen();
      fireEvent.click(screen.getByRole('button', { name: '수동 마킹' }));

      expect(onMark).toHaveBeenCalledTimes(1);
      expect(screen.getByText('수동 마킹 (F8)')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: '클립 만들기' })).toBeNull();
    });

    it('지난 방송은 그 화면의 이름을 쓴다', () => {
      const { enterFullscreen } = renderMarking({ mode: 'past' });

      enterFullscreen();

      expect(screen.getByRole('button', { name: '이 시점 카드 추가' })).toBeInTheDocument();
      expect(screen.getByText('이 시점 카드 추가 (F8)')).toBeInTheDocument();
    });

    it('아직 마킹할 수 없으면 버튼이 잠긴다', () => {
      const { enterFullscreen } = renderMarking({ disabled: true });

      enterFullscreen();

      expect(screen.getByRole('button', { name: '수동 마킹' })).toBeDisabled();
    });

    it('버튼에 포커스를 둔 채 전체 화면을 나가면 「전체 화면」 버튼이 받는다 — 문서로 떨어지면 화살표 시킹이 죽는다', () => {
      const { enterFullscreen } = renderMarking();
      enterFullscreen();
      act(() => screen.getByRole('button', { name: '수동 마킹' }).focus());

      act(() => setFullscreenElement(null));

      expect(screen.queryByRole('button', { name: '수동 마킹' })).toBeNull();
      expect(screen.getByRole('button', { name: '전체 화면' })).toHaveFocus();
    });

    it('마킹 결과는 전체 화면에서만 플레이어 안에 뜬다 — 영상 아래 줄과 토스트가 안 보이는 자리다', () => {
      const { enterFullscreen } = renderMarking({ status: '0:12 마킹됨 · 카드 생성 중' });
      expect(screen.queryByRole('status')).toBeNull();

      enterFullscreen();

      expect(screen.getByRole('status')).toHaveTextContent('0:12 마킹됨 · 카드 생성 중');
    });

    it('마킹을 못 주는 화면이면 전체 화면에도 버튼이 없다', () => {
      const { container } = renderPlayer();

      act(() => setFullscreenElement(container.querySelector('[data-controls]')));

      expect(screen.queryByRole('button', { name: '수동 마킹' })).toBeNull();
    });
  });

  it('설정 팝오버에서 화질 4개를 고를 수 있다', async () => {
    const user = userEvent.setup();
    renderPlayer();

    await user.click(screen.getByRole('button', { name: '설정' }));
    expect(screen.getByRole('button', { name: '자동 (1080p)' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByRole('button', { name: '1080p60' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '720p' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '480p' })).toBeInTheDocument();
  });

  it('설정 팝오버 안에서 누른 화살표는 배경 영상을 시킹하지 않는다', () => {
    // 팝오버는 Portal로 body에 붙지만 React는 React 트리를 따라 버블링시킨다 —
    // 컨테이너의 전역 단축키가 그 키를 삼키면 포커스 트랩 뒤에서 영상이 움직인다
    renderPlayer();
    const slider = screen.getByRole('slider', { name: '라이브 탐색' });

    fireEvent.click(screen.getByRole('button', { name: '설정' }));
    fireEvent.keyDown(screen.getByRole('button', { name: '자동 (1080p)' }), { key: 'ArrowLeft' });

    expect(slider).toHaveAttribute('aria-valuenow', '0');
  });

  it('시크바에서 키보드로 시킹하는 동안 컨트롤이 사라지지 않는다', () => {
    // 회귀: 전역 핸들러가 슬라이더 타겟에서 wake보다 먼저 반환해 숨김 타이머가 갱신되지
    // 않았다. 클릭으로 포커스가 들어간 시크바는 :focus-visible이 아니라 CSS 유보도 안 걸려,
    // 마우스를 안 움직이고 화살표만 누르면 되감기는 계속되는데 시크바·시차 표기가 사라졌다.
    vi.useFakeTimers();
    try {
      const { container } = renderPlayer();
      const player = container.querySelector('[data-controls]');
      const slider = screen.getByRole('slider', { name: '라이브 탐색' });

      act(() => {
        vi.advanceTimersByTime(2000); // 숨김 타이머(2.8s) 직전
      });
      fireEvent.keyDown(slider, { key: 'ArrowLeft' });
      act(() => {
        vi.advanceTimersByTime(2000); // 타이머가 갱신되지 않았다면 이 지점에서 숨는다
      });

      expect(player).toHaveAttribute('data-controls', 'visible');
      expect(slider).toHaveAttribute('aria-valuenow', '-10');
    } finally {
      vi.useRealTimers();
    }
  });

  it('바깥 채팅 패널 콜백이 없으면 채팅 열기 버튼도 없다', () => {
    // 플레이어를 단독으로 쓰는 화면(VOD 등)에 없는 패널의 버튼이 생기면 안 된다
    renderPlayer();
    expect(screen.queryByRole('button', { name: '채팅 열기' })).not.toBeInTheDocument();
  });

  it('채팅 패널이 열려 있으면 여는 버튼은 뜨지 않는다', () => {
    renderPlayer(undefined, { chatPanelOpen: true, onToggleChatPanel: vi.fn() });
    expect(screen.queryByRole('button', { name: '채팅 열기' })).not.toBeInTheDocument();
  });

  it('채팅 패널이 접혀 있으면 상단에 여는 버튼이 서고, 클릭이 콜백으로 간다', async () => {
    const user = userEvent.setup();
    const onToggleChatPanel = vi.fn();
    renderPlayer(undefined, { chatPanelOpen: false, onToggleChatPanel });

    await user.click(screen.getByRole('button', { name: '채팅 열기' }));
    expect(onToggleChatPanel).toHaveBeenCalledOnce();
  });

  it('기본 상태엔 플레이어 안 채팅 오버레이도 그 토글도 없다 — 옆 채팅 패널이 맡는다', () => {
    const { container } = renderPlayer();
    expect(screen.queryByRole('button', { name: '채팅 오버레이' })).not.toBeInTheDocument();
    expect(container.querySelector('[class*="chatOverlay"]')).toBeNull();
  });

  it('전체 화면에 들어가면 채팅 오버레이 토글이 서고, 나오면(ESC 포함) 사라진다', async () => {
    const user = userEvent.setup();
    const { container } = renderPlayer();
    const player = container.querySelector('[data-controls]');
    expect(player).not.toBeNull();

    try {
      setFullscreenElement(player);
      const toggle = screen.getByRole('button', { name: '채팅 오버레이' });
      // 들어가면 바로 켜진 채 시작한다 — 전체 화면에선 이 오버레이가 유일한 채팅 창구다
      expect(toggle).toHaveAttribute('aria-pressed', 'true');
      expect(container.querySelector('[class*="chatOverlay"]')).not.toBeNull();

      await user.click(toggle);
      expect(toggle).toHaveAttribute('aria-pressed', 'false');
      expect(container.querySelector('[class*="chatOverlay"]')).toBeNull();
      // 키보드로 토글에 와 있는 경우다: 마우스로 누른 버튼엔 포커스가 남지 않는다(POK-518)
      act(() => toggle.focus());
      expect(toggle).toHaveFocus();

      // ESC·브라우저 UI로 나가도 같은 이벤트가 온다 — 요청이 아니라 결과를 듣는다
      setFullscreenElement(null);
      expect(screen.queryByRole('button', { name: '채팅 오버레이' })).not.toBeInTheDocument();
      // 토글이 사라지며 잃은 포커스는 바로 옆 「전체 화면」 버튼으로 간다 — 이름 없는 컨테이너로
      // 옮기면 낭독이 끊긴다. 화살표 시킹(POK-32)은 버튼 위에서도 그대로 먹는다.
      expect(screen.getByRole('button', { name: '전체 화면' })).toHaveFocus();
    } finally {
      setFullscreenElement(null);
    }
  });

  it('마운트 시점에 이미 전체 화면이면 이벤트 없이도 그 상태로 시작한다', () => {
    // 리스너가 없던 사이(Suspense 재서스펜드 등)의 변화는 이벤트로 안 온다 — 붙을 때 한 번 읽어야 한다.
    // 게터가 문서에서 플레이어를 찾는다 — 효과는 마운트 뒤에 돌므로 첫 sync()에서 이미 잡힌다.
    Object.defineProperty(document, 'fullscreenElement', {
      configurable: true,
      get: () => document.querySelector('[data-controls]'),
    });
    renderPlayer();
    expect(screen.getByRole('button', { name: '채팅 오버레이' })).toBeInTheDocument();
  });

  it('패널을 접은 채 전체 화면에 들어가면 「채팅 열기」가 서지 않는다 — 바깥 패널이 안 보인다', () => {
    const { container } = renderPlayer(undefined, {
      chatPanelOpen: false,
      onToggleChatPanel: vi.fn(),
    });
    expect(screen.getByRole('button', { name: '채팅 열기' })).toBeInTheDocument();
    try {
      setFullscreenElement(container.querySelector('[data-controls]'));
      expect(screen.queryByRole('button', { name: '채팅 열기' })).not.toBeInTheDocument();
      setFullscreenElement(null);
      expect(screen.getByRole('button', { name: '채팅 열기' })).toBeInTheDocument();
    } finally {
      setFullscreenElement(null);
    }
  });

  it('토글에 포커스가 없었으면 전체 화면을 나가도 남의 포커스를 뺏지 않는다', () => {
    // 이 가드가 없으면 전체 화면을 나갈 때마다 플레이어가 포커스를 가져가, 이어서 Tab을 누른
    // 사용자가 페이지 처음이 아니라 플레이어 다음부터 훑게 된다.
    const { container } = renderPlayer();
    const outside = document.createElement('button');
    document.body.appendChild(outside);
    try {
      setFullscreenElement(container.querySelector('[data-controls]'));
      outside.focus();
      expect(outside).toHaveFocus();

      setFullscreenElement(null);
      expect(outside).toHaveFocus();
    } finally {
      outside.remove();
      setFullscreenElement(null);
    }
  });

  it('다른 요소의 전체 화면은 남의 일이다 — 채팅 버튼이 서지 않는다', () => {
    renderPlayer();
    const other = document.createElement('div');
    try {
      setFullscreenElement(other);
      expect(screen.queryByRole('button', { name: '채팅 오버레이' })).not.toBeInTheDocument();
    } finally {
      setFullscreenElement(null);
    }
  });

  it('방송 경과 시간을 플레이어에 두지 않는다 — 영상 아래 방송 정보 바가 말한다', () => {
    renderPlayer({ initialUptimeSeconds: 5043 });
    expect(screen.queryByText('1:24:03')).not.toBeInTheDocument();
  });

  it('상단 필은 채널명과 시청자 수만 말한다 — 시안의 라이브 줄이다', () => {
    renderPlayer();
    expect(screen.getByText('게임하는너구리')).toBeInTheDocument();
    expect(screen.getByText('1,842명 시청 중')).toBeInTheDocument();
  });

  it('컨트롤러의 시점 이동이 방송 경과 시각을 시차로 환산한다', () => {
    // 카드가 든 "1:24:03"은 고정값이라 절대 시각으로 받는다 — 시차로 건네면 흐르는 동안 어긋난다
    const controllerRef = createRef<GlassPlayerController>();
    renderPlayer({ initialUptimeSeconds: 5043 }, { controllerRef });

    act(() => {
      controllerRef.current?.seekToUptime(4960);
    });
    expect(screen.getByRole('slider', { name: '라이브 탐색' })).toHaveAttribute(
      'aria-valuenow',
      '-83',
    );
  });

  it('되감기 창 밖 시각은 창 끝으로 클램프된다', () => {
    // 만료 임박 카드(1시간 밖)를 눌러도 시크바가 범위를 벗어나면 안 된다
    const controllerRef = createRef<GlassPlayerController>();
    renderPlayer({ initialUptimeSeconds: 5043 }, { controllerRef });

    act(() => {
      controllerRef.current?.seekToUptime(0);
    });
    expect(screen.getByRole('slider', { name: '라이브 탐색' })).toHaveAttribute(
      'aria-valuenow',
      '-3600',
    );
  });

  it('설정 팝오버가 열려 있는 동안엔 자동 숨김이 유보된다', () => {
    // 팝오버는 Portal로 플레이어 밖에 떠서 포커스 보호가 닿지 않는다 — 열림 상태로 유보한다.
    // userEvent는 fake timer와 대기가 엉켜 멈추므로 동기 fireEvent를 쓴다.
    vi.useFakeTimers();
    try {
      const { container } = renderPlayer();
      const player = container.querySelector('[data-controls]');

      fireEvent.click(screen.getByRole('button', { name: '설정' }));
      act(() => {
        vi.advanceTimersByTime(4000); // 숨김 타이머(2.8s)를 지나친다
      });

      expect(player).toHaveAttribute('data-controls', 'visible');
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('GlassPlayer: 녹화 다시보기', () => {
  // jsdom에는 미디어 재생이 없다. 재생 요청만 받아 둔다
  beforeEach(() => {
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
    vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => undefined);
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  function renderRecorded(
    props: Partial<ComponentProps<typeof GlassPlayer>> = {},
    durationSeconds = 444,
  ) {
    const T0 = Date.parse('2026-10-08T02:43:39Z');
    const timeline = recordingTimeline([{ startMs: T0, durationSeconds }], null, null)!;
    return render(
      <ToastProvider>
        <GlassPlayer
          channelName="게임하는너구리"
          viewersNote="시청자 수 수집 전"
          broadcastStatus="ended"
          embed
          recorded={{ streamId: 'test1008a', ...timeline }}
          {...props}
        />
      </ToastProvider>,
    );
  }

  /** 영상 노드가 그 시각까지 흘렀다 */
  function playTo(container: HTMLElement, seconds: number) {
    const video = container.querySelector('video')!;
    act(() => {
      video.currentTime = seconds;
      video.dispatchEvent(new Event('timeupdate'));
    });
  }

  it('녹화를 다시 붙일 수 없으면 영상 위에 한 줄로 알린다: 전에는 마지막 장면에서 조용히 멎었다(POK-337)', async () => {
    // 재생 서버가 답하지 않는다: 403인지 확인하지 못했으니 다시 붙일 수 없다
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('network'));
    const { container } = renderRecorded();
    expect(screen.queryByText('녹화 영상을 불러오지 못했어요')).toBeNull();

    await act(async () => {
      container.querySelector('video')!.dispatchEvent(new Event('error'));
    });
    expect(screen.getByRole('status')).toHaveTextContent('녹화 영상을 불러오지 못했어요');
  });

  it('재생 위치가 초 단위로 바뀔 때마다 알린다: 지난 방송 채팅이 그 시각까지의 줄만 보인다(POK-324)', () => {
    const heard = vi.fn();
    const { container } = renderRecorded({ onPlaybackPosition: heard });

    playTo(container, 12.2);
    expect(heard).toHaveBeenLastCalledWith(12);
    // 내린다: 영상 시각을 넘는 줄은 보이지 않는다(반올림하면 0.5초 앞 줄까지 보였다)
    playTo(container, 12.9);
    expect(heard).toHaveBeenLastCalledWith(12);
    playTo(container, 13.1);

    // timeupdate는 1초에 네 번쯤 온다. 같은 초면 다시 알리지 않는다
    expect(heard.mock.calls.map(([seconds]) => seconds)).toEqual([0, 12, 13]);
  });

  it('카드처럼 정확한 시각으로 옮기면 그 초에 선다: 녹화 길이에 소수가 있어도 1초 앞에 서지 않는다', () => {
    // 시크바 비율을 거치면 시차를 초 단위로 반올림해 600.7초 녹화에서 149초 카드가 148.7초에 섰다(리뷰 1라운드)
    const heard = vi.fn();
    const controllerRef = createRef<GlassPlayerController>();
    renderRecorded({ onPlaybackPosition: heard, controllerRef }, 600.7);

    act(() => {
      controllerRef.current?.seekToUptime(149);
    });
    expect(heard).toHaveBeenLastCalledWith(149);
  });

  it('옮기면 옮긴 자리를 바로 알리고, 새 줄기가 붙기 전 옛 줄기의 시각으로 되돌아가지 않는다', () => {
    vi.useFakeTimers();
    try {
      const heard = vi.fn();
      const { container } = renderRecorded({ onPlaybackPosition: heard });
      playTo(container, 30);
      heard.mockClear();

      fireEvent.keyDown(screen.getByRole('button', { name: '재생' }), { key: 'End' });
      // 끝은 마지막 1초 앞이다
      expect(heard.mock.calls.map(([seconds]) => seconds)).toEqual([443]);
      playTo(container, 30.3);
      act(() => {
        vi.advanceTimersByTime(300);
      });
      // 새 줄기의 0초가 443초다(반올림해도 443): 옛 줄기의 기준이면 0초로 알렸다
      playTo(container, 0.4);

      expect(heard.mock.calls.map(([seconds]) => seconds)).toEqual([443]);
    } finally {
      vi.useRealTimers();
    }
  });

  it('왼쪽 아래는 「지금 / 전체」 시간이다: 누르면 남은 시간, 다시 누르면 원래대로다(POK-518)', () => {
    const { container } = renderRecorded();
    playTo(container, 30);

    expect(screen.queryByRole('button', { name: '실시간으로 이동' })).toBeNull();
    const button = screen.getByRole('button', { name: '남은 시간 보기' });
    expect(button).toHaveTextContent('0:30 / 7:24');

    fireEvent.click(button);
    const remaining = screen.getByRole('button', { name: '재생 시간 보기' });
    expect(remaining).toHaveTextContent('-6:54 / 7:24');

    fireEvent.click(remaining);
    expect(screen.getByRole('button', { name: '남은 시간 보기' })).toHaveTextContent('0:30 / 7:24');
  });

  it('시간 버튼 말풍선은 버튼 이름이다 — 단축키가 없어 이름만 뜬다(POK-518)', () => {
    renderRecorded();

    expect(screen.getByText('남은 시간 보기')).toHaveAttribute('aria-hidden', 'true');

    fireEvent.click(screen.getByRole('button', { name: '남은 시간 보기' }));

    expect(screen.getByText('재생 시간 보기')).toBeInTheDocument();
  });

  it('녹화 끝에 닿으면 지금과 전체가 같고 남은 시간은 0이다(POK-518)', () => {
    const { container } = renderRecorded();
    const video = container.querySelector('video')!;
    Object.defineProperty(video, 'ended', { value: true, configurable: true });
    act(() => {
      video.dispatchEvent(new Event('pause'));
      video.dispatchEvent(new Event('ended'));
    });

    const button = screen.getByRole('button', { name: '남은 시간 보기' });
    expect(button).toHaveTextContent('7:24 / 7:24');

    fireEvent.click(button);
    expect(screen.getByRole('button', { name: '재생 시간 보기' })).toHaveTextContent(
      '-0:00 / 7:24',
    );
  });

  it('멈춘 채로 뜬 화면도 호버하지 않으면 컨트롤이 숨는다 — 자동재생이 막혀도 영상을 가리지 않는다(POK-518)', () => {
    vi.useFakeTimers();
    try {
      const { container } = renderRecorded();
      const player = container.querySelector('[data-controls]')!;
      expect(player).toHaveAttribute('data-controls', 'visible');

      act(() => {
        vi.advanceTimersByTime(3000);
      });

      expect(player).toHaveAttribute('data-controls', 'hidden');
    } finally {
      vi.useRealTimers();
    }
  });

  it('End 키는 플레이어 어디서든 녹화 끝으로 간다(POK-518)', () => {
    const heard = vi.fn();
    renderRecorded({ onPlaybackPosition: heard });

    fireEvent.keyDown(screen.getByRole('button', { name: '재생' }), { key: 'End' });

    // 끝은 마지막 1초 앞이다
    expect(heard).toHaveBeenLastCalledWith(443);
  });

  it('끝까지 보면 재생 단추가 「처음부터」다: 눌렀을 때 일을 미리 말한다(POK-518)', () => {
    const { container } = renderRecorded();
    const video = container.querySelector('video')!;
    expect(screen.queryByRole('button', { name: '처음부터' })).toBeNull();

    Object.defineProperty(video, 'ended', { value: true, configurable: true });
    act(() => {
      video.dispatchEvent(new Event('pause'));
      video.dispatchEvent(new Event('ended'));
    });

    expect(screen.getByRole('button', { name: '처음부터' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '재생' })).toBeNull();
  });

  it('시크바도 다시보기라고 읽는다: 화면 낭독기가 「실시간에서」라고 하지 않는다', () => {
    renderRecorded();

    expect(screen.queryByRole('slider', { name: '라이브 탐색' })).toBeNull();
    expect(screen.getByRole('slider', { name: '다시보기 탐색' })).toHaveAttribute(
      'aria-valuetext',
      '녹화 끝에서 -07:24',
    );
  });

  describe('끊겨 기다릴 때(POK-439)', () => {
    beforeEach(() => {
      vi.useFakeTimers();
    });
    afterEach(() => {
      vi.useRealTimers();
    });

    const spinner = (container: HTMLElement) => container.querySelector('[data-buffering]');

    /** 재생 중인 영상 노드 — jsdom의 영상은 늘 멈춰 있다 */
    function playingVideo(container: HTMLElement) {
      const video = container.querySelector('video')!;
      Object.defineProperty(video, 'paused', { value: false, configurable: true });
      return video;
    }

    function wait(video: HTMLVideoElement, ms: number) {
      act(() => {
        video.dispatchEvent(new Event('waiting'));
        vi.advanceTimersByTime(ms);
      });
    }

    it('waiting 뒤 400ms 전엔 없고, 그 뒤 생기고, playing에 사라진다', () => {
      const { container } = renderRecorded();
      const video = playingVideo(container);

      wait(video, 399);
      expect(spinner(container)).toBeNull();

      act(() => {
        vi.advanceTimersByTime(1);
      });
      // 채팅 창 불러오기와 같은 로고 그리기다
      expect(spinner(container)?.querySelector('svg mask')).not.toBeNull();
      // 버퍼링은 낭독하지 않는다(소음)
      expect(screen.queryByRole('status')).toBeNull();

      act(() => {
        video.dispatchEvent(new Event('playing'));
      });
      expect(spinner(container)).toBeNull();
    });

    it('일시정지 중엔 안 뜬다', () => {
      const { container } = renderRecorded();
      const video = container.querySelector('video')!;

      wait(video, 400);

      expect(spinner(container)).toBeNull();
    });

    it('재생이 멎어 안내가 서면 안 뜬다', async () => {
      vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('network'));
      const { container } = renderRecorded();
      const video = playingVideo(container);
      await act(async () => {
        video.dispatchEvent(new Event('error'));
      });
      expect(screen.getByRole('status')).toHaveTextContent('녹화 영상을 불러오지 못했어요');

      wait(video, 400);

      expect(spinner(container)).toBeNull();
    });
  });
});

describe('GlassPlayer: 바깥 이동 명령의 결과', () => {
  it('영상이 없는 자리 플레이어는 옮기지 못했다고 돌려주고 스스로 알리지 않는다: 녹화를 찾는 중일 수 있다', () => {
    const controllerRef = createRef<GlassPlayerController>();
    render(
      <ToastProvider>
        <GlassPlayer
          channelName="게임하는너구리"
          viewersNote="시청자 수 수집 전"
          broadcastStatus="ended"
          embed
          controllerRef={controllerRef}
        />
      </ToastProvider>,
    );

    let moved: boolean | undefined;
    act(() => {
      moved = controllerRef.current?.seekToUptime(120);
    });
    expect(moved).toBe(false);
    expect(screen.queryByText(/영상은 아직 없어요/)).toBeNull();
  });

  it('되감을 구간이 아직 없으면 옮기지 못했다고 돌려준다: 라이브 영상이 목록을 받기 전이다', () => {
    // 라이브 영상(HLS)은 목록을 받기 전에 되감을 구간이 0초라 이동을 조용히 버린다. 옮겼다고 하면 알림도 없이 사라진다(리뷰 2라운드)
    const controllerRef = createRef<GlassPlayerController>();
    renderPlayer({ initialUptimeSeconds: 0 }, { controllerRef });

    let moved: boolean | undefined;
    act(() => {
      moved = controllerRef.current?.seekToUptime(10);
    });
    expect(moved).toBe(false);
  });

  it('재생 중인 플레이어는 옮겼다고 돌려준다', () => {
    const controllerRef = createRef<GlassPlayerController>();
    renderPlayer(undefined, { controllerRef });

    let moved: boolean | undefined;
    act(() => {
      moved = controllerRef.current?.seekToUptime(10);
    });
    expect(moved).toBe(true);
  });
});

describe('GlassPlayer: 재생 세션(POK-200 M5)', () => {
  function fakeSession(view: Partial<SessionView> = {}): PlaybackSession {
    const state = openSession({
      roundId: 'r-A',
      accessKey: 'r-A|key-1',
      liveUrl: 'https://m/live/key-1/index.m3u8',
    });
    return {
      state,
      view: { ...sessionView(state, Date.now()), ...view },
      dispatch: vi.fn(),
      liveButton: vi.fn(),
      bindPlayer: vi.fn(),
      notice: vi.fn(),
    };
  }

  function renderWith(props: Partial<ComponentProps<typeof GlassPlayer>>) {
    return render(
      <ToastProvider>
        <GlassPlayer channelName="게임하는너구리" viewersNote="1,842명 시청 중" embed {...props} />
      </ToastProvider>,
    );
  }

  it('고르는 순서는 세션이 먼저다 — 녹화 · src 가 있어도 세션 재생기가 서고 재생기를 세션에 묶는다', () => {
    const session = fakeSession({ cover: 'checking', actions: ['record'] });
    renderWith({
      session,
      src: 'https://m/live/other/index.m3u8',
      recorded: {
        streamId: 'x',
        ...recordingTimeline([{ startMs: 0, durationSeconds: 60 }], 0, null)!,
        originAxis: true,
      },
    });
    expect(screen.getByRole('status')).toHaveTextContent('방송이 이어지는지 확인하고 있어요');
    expect(session.bindPlayer).toHaveBeenCalledWith(
      expect.objectContaining({ seekToMs: expect.any(Function) }),
    );
  });

  it('덮개의 「녹화 보기」는 세션에 버튼 사건으로 올린다', async () => {
    const session = fakeSession({ cover: 'checking', actions: ['record'] });
    renderWith({ session });
    await userEvent.click(screen.getByRole('button', { name: '녹화 보기' }));
    expect(session.dispatch).toHaveBeenCalledWith({ type: 'button', which: 'record' });
  });

  it('live 기원 덮개에서는 시크바를 숨긴다 — 받을 목록이 없다', () => {
    renderWith({
      session: fakeSession({ cover: 'checking', actions: ['record'], seekHidden: true }),
    });
    expect(screen.queryByRole('slider', { name: '라이브 탐색' })).toBeNull();
  });

  it('끝에 닿아 덮개가 선 동안 재생 단추는 재생하지 않는다 — 덮개가 없으면 재생한다(cc 확인 패스)', async () => {
    const play = vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
    vi.spyOn(HTMLMediaElement.prototype, 'paused', 'get').mockReturnValue(true);
    try {
      const covered = renderWith({
        session: fakeSession({ cover: 'checking', actions: ['record'] }),
      });
      play.mockClear();
      await userEvent.click(screen.getByRole('button', { name: '재생' }));
      expect(play).not.toHaveBeenCalled();
      covered.unmount();
      renderWith({ session: fakeSession() });
      play.mockClear();
      await userEvent.click(screen.getByRole('button', { name: '재생' }));
      expect(play).toHaveBeenCalledTimes(1);
    } finally {
      vi.restoreAllMocks();
    }
  });

  it('끝난 방송의 다시보기(liveHidden)는 LIVE 버튼을 숨긴다 — 눌러도 세션이 무시하는 죽은 버튼이 남지 않게(cx M)', () => {
    const shown = renderWith({ session: fakeSession() });
    expect(screen.getByRole('button', { name: '실시간으로 이동' })).toBeInTheDocument();
    shown.unmount();
    renderWith({ session: fakeSession({ liveHidden: true }) });
    expect(screen.queryByRole('button', { name: '실시간으로 이동' })).toBeNull();
  });

  it('세션 다시보기엔 시간 표기를 세우지 않는다 — 세션 축은 1시간에서 잘려 총 시간이 틀린다(POK-518)', () => {
    const session = fakeSession({ liveHidden: true });
    const source = { ...session.state.source, kind: 'vod' as const };
    renderWith({ session: { ...session, state: { ...session.state, source } } });

    expect(screen.getByRole('slider', { name: '다시보기 탐색' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '남은 시간 보기' })).toBeNull();
  });

  it('덮개가 선 동안은 끊겨도 스피너를 겹치지 않는다 — 덮개가 없으면 띄운다(POK-439)', () => {
    vi.useFakeTimers();
    try {
      const waitOn = (container: HTMLElement) => {
        const video = container.querySelector('video')!;
        Object.defineProperty(video, 'paused', { value: false, configurable: true });
        act(() => {
          video.dispatchEvent(new Event('waiting'));
          vi.advanceTimersByTime(400);
        });
      };

      const covered = renderWith({
        session: fakeSession({ cover: 'checking', actions: ['record'] }),
      });
      waitOn(covered.container);
      expect(covered.container.querySelector('[data-buffering]')).toBeNull();
      covered.unmount();

      const { container } = renderWith({ session: fakeSession() });
      waitOn(container);
      expect(container.querySelector('[data-buffering]')).not.toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('세션이 없으면 지금 순서(src)다', () => {
    renderWith({ src: 'https://m/live/key-1/index.m3u8' });
    expect(screen.getByRole('slider', { name: '라이브 탐색' })).toBeInTheDocument();
  });
});
