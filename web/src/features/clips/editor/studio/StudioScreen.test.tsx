import { act, useState } from 'react';
import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { describe, expect, it, vi } from 'vitest';
import { renderWithProviders } from '@/test/testProviders';
import { Dialog, RadioGroup } from '@/ui';
import { StudioEditor } from './StudioScreen';
import type { ClipEditorOptions } from '../useClipEditorMockState';

function renderStudio(options?: ClipEditorOptions) {
  return renderWithProviders(<StudioEditor {...options} />);
}

/** 미리보기 아래 재생 막대의 글자 — 타임코드 박스·눈금에도 비슷한 시각이 있어 막대 안만 본다 */
function transportClock(): string {
  return screen.getByRole('button', { name: '5초 뒤로' }).parentElement?.textContent ?? '';
}

/** 재생 막대의 쇼츠 안 시각(m:ss.t)을 초로. 앞의 되감기 아이콘 숫자(5·1)가 붙어 읽히니 분은 한 자리로 끊는다 */
function transportSeconds(): number {
  const [, m, s] = /(\d):(\d\d\.\d)/.exec(transportClock())!;
  return Number(m) * 60 + Number(s);
}

/** 타임라인 머리의 확대 묶음(− 100% ＋) — 오디오 트랙 음량에도 「100%」가 있어 이 안만 본다 */
function zoomGroup(): HTMLElement {
  return screen.getByRole('button', { name: '타임라인 확대' }).parentElement!;
}

describe('StudioScreen', () => {
  it('헤더에 제목·자동 저장·저장 버튼을 렌더한다', () => {
    renderStudio();

    expect(screen.getByRole('heading', { name: '승급전 마지막 한타 역전' })).toBeInTheDocument();
    expect(screen.getByText('라이브 카드 1:24:03 · 방금 자동 저장됨')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '이전 화면으로' })).toHaveAttribute(
      'href',
      '/clips/library',
    );
    // 쇼츠 편집 화면에는 템플릿 저장이 없다(POK-389): 저장은 편집본 저장 하나다
    expect(screen.queryByRole('button', { name: '템플릿 저장' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '업로드' })).toBeEnabled();
    // 아직 편집한 적이 없으니 되돌릴 곳도 없다
    expect(screen.getByRole('button', { name: '작업 이전으로' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '작업 앞으로' })).toBeDisabled();
  });

  it('타임라인은 자막 · 영상 · 방송 소리 트랙 줄이다: 준비 중인 음악·효과음·이미지 줄은 없다(POK-389)', () => {
    const { container } = renderStudio();
    const timeline = screen.getByRole('region', { name: '타임라인' });

    const labels = [...container.querySelectorAll('[data-row-label]')].map((el) => el.textContent);
    expect(labels).toEqual(['자막', '영상', '마이크', '게임', '디스코드', 'BGM']);
    // 목업 자막 다섯 줄이 자막 줄에 조각으로 선다
    expect(container.querySelectorAll('[data-subtitle-block]')).toHaveLength(5);
    expect(within(timeline).getByText('80%')).toBeInTheDocument();
    expect(within(timeline).queryByText('효과음')).not.toBeInTheDocument();
    expect(within(timeline).queryByText('이미지')).not.toBeInTheDocument();
    // 쇼츠 밖은 양쪽을 흐리게 덮는다
    expect(container.querySelectorAll('[data-outside]')).toHaveLength(2);
  });

  it('소리 트랙 줄 머리에서 바로 넣고 뺀다: 뺀 줄은 음량 숫자를 감춘다(POK-389)', async () => {
    const user = userEvent.setup();
    const { container } = renderStudio();
    const timeline = screen.getByRole('region', { name: '타임라인' });

    const discord = within(timeline).getByRole('button', { name: '디스코드 넣기' });
    expect(discord).toHaveAttribute('aria-pressed', 'true');
    expect(
      within(timeline).getByRole('button', { name: '디스코드 음량 100%' }),
    ).toBeInTheDocument();
    await user.click(discord);
    expect(discord).toHaveAttribute('aria-pressed', 'false');
    expect(
      within(timeline).queryByRole('button', { name: /디스코드 음량/ }),
    ).not.toBeInTheDocument();
    expect(container.querySelector('[data-track-row="discord"]')).toHaveAttribute('data-muted');
  });

  it('오디오 메뉴에는 음량이 없다: 방송 소리는 타임라인에서 다룬다고 안내한다(POK-389)', async () => {
    const user = userEvent.setup();
    renderStudio();
    await user.click(screen.getByRole('tab', { name: '오디오' }));
    const audio = screen.getByRole('region', { name: '오디오' });

    expect(within(audio).queryByRole('slider')).not.toBeInTheDocument();
    expect(within(audio).queryByRole('checkbox')).not.toBeInTheDocument();
    expect(within(audio).getByText(/타임라인 트랙 줄에서 넣고 빼요/)).toBeInTheDocument();
  });

  it('음량 숫자를 누르면 막대 창이 뜨고, 50 · 100 · 150%로 바로 고른다(POK-389)', async () => {
    const user = userEvent.setup();
    renderStudio();
    const timeline = screen.getByRole('region', { name: '타임라인' });

    await user.click(within(timeline).getByRole('button', { name: '마이크 음량 80%' }));
    const panel = screen.getByRole('dialog');
    expect(within(panel).getByRole('slider', { name: '마이크 볼륨' })).toHaveAttribute(
      'aria-valuemax',
      '200',
    );
    await user.click(within(panel).getByRole('button', { name: '150%' }));
    expect(within(timeline).getByRole('button', { name: '마이크 음량 150%' })).toBeInTheDocument();
  });

  it('다른 편집 대상을 읽기 시작하면 열려 있던 음량 막대 창을 닫는다: 창은 inert 밖(포털)이다(PR #377 2차 리뷰)', async () => {
    const user = userEvent.setup();
    let startReload: () => void = () => undefined;
    function Harness() {
      const [reloading, setReloading] = useState(false);
      startReload = () => setReloading(true);
      return <StudioEditor reloading={reloading} />;
    }
    renderWithProviders(<Harness />);
    const timeline = screen.getByRole('region', { name: '타임라인' });

    await user.click(within(timeline).getByRole('button', { name: '마이크 음량 80%' }));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    // 바깥을 누르지 않고 읽기만 시작한다 — 바깥 누르기는 원래도 창을 닫는다
    await act(async () => startReload());
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('음량 숫자를 좌우로 끌면 바로 바뀌고 막대 창은 안 뜬다: 끈 것은 실행취소 한 칸이다(POK-389)', () => {
    renderStudio();
    const timeline = screen.getByRole('region', { name: '타임라인' });
    const mic = within(timeline).getByRole('button', { name: '마이크 음량 80%' });

    fireEvent.pointerDown(mic, { clientX: 100, pointerId: 1, button: 0 });
    fireEvent.pointerMove(mic, { clientX: 115, pointerId: 1 });
    fireEvent.pointerMove(mic, { clientX: 130, pointerId: 1 });
    fireEvent.pointerUp(mic, { pointerId: 1 });
    fireEvent.click(mic);

    expect(within(timeline).getByRole('button', { name: '마이크 음량 110%' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    // 끄는 동안 여러 번 바뀌어도 되돌리기 한 번에 끌기 전으로 돌아간다
    fireEvent.click(screen.getByRole('button', { name: '작업 이전으로' }));
    expect(within(timeline).getByRole('button', { name: '마이크 음량 80%' })).toBeInTheDocument();
  });

  it('음량 숫자를 끄는 중 포인터 캡처를 잃어도 끌기를 끝낸다: 다음 편집이 같은 실행취소 칸에 섞이지 않는다(PR #341 claude)', () => {
    renderStudio();
    const timeline = screen.getByRole('region', { name: '타임라인' });
    const mic = within(timeline).getByRole('button', { name: '마이크 음량 80%' });

    fireEvent.pointerDown(mic, { clientX: 100, pointerId: 1, button: 0 });
    fireEvent.pointerMove(mic, { clientX: 130, pointerId: 1 });
    // pointerup·pointercancel 없이 캡처만 잃는다(다른 요소가 캡처를 가져가는 등)
    fireEvent.lostPointerCapture(mic, { pointerId: 1 });
    expect(within(timeline).getByRole('button', { name: '마이크 음량 110%' })).toBeInTheDocument();

    const discord = within(timeline).getByRole('button', { name: '디스코드 넣기' });
    fireEvent.click(discord);
    expect(discord).toHaveAttribute('aria-pressed', 'false');
    // 되돌리기 한 번은 넣기만 되돌린다: 끈 음량은 그대로다
    fireEvent.click(screen.getByRole('button', { name: '작업 이전으로' }));
    expect(discord).toHaveAttribute('aria-pressed', 'true');
    expect(within(timeline).getByRole('button', { name: '마이크 음량 110%' })).toBeInTheDocument();
  });

  it('음량 숫자에 초점이 있으면 ← → 가 5%씩 바꾸고 재생 위치는 안 움직인다(POK-389)', async () => {
    const user = userEvent.setup();
    renderStudio();
    const timeline = screen.getByRole('region', { name: '타임라인' });
    const before = transportClock();

    within(timeline).getByRole('button', { name: '마이크 음량 80%' }).focus();
    await user.keyboard('{ArrowRight}');
    expect(within(timeline).getByRole('button', { name: '마이크 음량 85%' })).toBeInTheDocument();
    await user.keyboard('{ArrowLeft}{ArrowLeft}');
    expect(within(timeline).getByRole('button', { name: '마이크 음량 75%' })).toBeInTheDocument();
    expect(transportClock()).toBe(before);
  });

  it('트랙을 빼거나 음량을 바꾸면 미리 듣기 음량에도 바로 넘긴다: 트랙 순서가 번호다(POK-389)', async () => {
    const user = userEvent.setup();
    const setGains = vi.fn();
    renderStudio({ trackPreview: { status: 'ready', levels: null, setGains } });
    // 목업 트랙: 마이크 80 · 게임 60 · 디스코드 100 · BGM 40
    expect(setGains).toHaveBeenLastCalledWith([0.8, 0.6, 1, 0.4]);

    const timeline = screen.getByRole('region', { name: '타임라인' });
    await user.click(within(timeline).getByRole('button', { name: '디스코드 넣기' }));
    expect(setGains).toHaveBeenLastCalledWith([0.8, 0.6, 0, 0.4]);
    expect(screen.queryByText(/트랙별 미리 듣기를 못 불러왔어요/)).not.toBeInTheDocument();
  });

  it('트랙별 미리 듣기를 못 불렀으면 영상 소리로 들린다고 알린다', () => {
    renderStudio({ trackPreview: { status: 'unavailable', levels: null, setGains: vi.fn() } });
    expect(
      screen.getByText('트랙별 미리 듣기를 못 불러왔어요 · 영상 소리로 들려요'),
    ).toHaveAttribute('role', 'status');
  });

  it.each(['idle', 'loading'] as const)(
    '미리 듣기가 %s이면 실패로 알리지 않는다: 창을 기다리거나 받는 중일 뿐이다(PR #341 claude)',
    (status) => {
      renderStudio({ trackPreview: { status, levels: null, setGains: vi.fn() } });
      expect(screen.queryByText(/트랙별 미리 듣기를 못 불러왔어요/)).not.toBeInTheDocument();
    },
  );

  it('자막이 아직 없으면 자막 줄을 그리지 않는다', () => {
    const { container } = renderStudio({ initialSubtitleStatus: 'idle' });
    const labels = [...container.querySelectorAll('[data-row-label]')].map((el) => el.textContent);
    expect(labels[0]).toBe('영상');
  });

  it('도구 레일을 누르면 패널만 바뀌고 타임라인은 남는다', async () => {
    const user = userEvent.setup();
    renderStudio();

    // 시안 갱신분의 기본은 레이아웃 도구다
    expect(screen.getByRole('region', { name: '레이아웃' })).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: '자막' }));
    expect(screen.getByRole('region', { name: '자막' })).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: '이미지' }));

    expect(screen.queryByRole('region', { name: '자막' })).not.toBeInTheDocument();
    expect(screen.getByRole('region', { name: '이미지' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '타임라인' })).toBeInTheDocument();
  });

  it('자막을 만들기 전에는 제목 추천이 잠겨 있고, 만들면 열린다', async () => {
    const user = userEvent.setup();
    renderStudio({ initialSubtitleStatus: 'idle' });
    await user.click(screen.getByRole('tab', { name: '자막' }));

    const locked = screen.getByRole('region', { name: 'AI 제목 추천' });
    expect(within(locked).getByText('잠김')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '1대3 클러치 미쳤다 #발로란트' })).toBeNull();

    await user.click(screen.getByRole('button', { name: /AI 자막 만들기/ }));

    // 목업 생성이 끝나면 자막 목록과 제목 후보가 함께 열린다
    expect(
      // 목업 생성 지연(1.5초)이 기본 대기시간보다 길다
      await screen.findByRole(
        'button',
        { name: '1대3 클러치 미쳤다 #발로란트' },
        { timeout: 3000 },
      ),
    ).toBeInTheDocument();
    // 같은 문장이 타임라인 자막 줄에도 선다: 자막 패널 안만 본다
    expect(
      within(screen.getByRole('region', { name: '자막' })).getByText('아 이게 된다고?? 미쳤다'),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '재생성' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: '둘 다' })).toBeChecked();
  });

  it('구간을 5초 미만으로 줄이려 하면 핸들이 거기서 멈춘다', async () => {
    const user = userEvent.setup();
    renderStudio();

    const startHandle = screen.getByRole('slider', { name: '구간 시작점' });
    expect(startHandle).toHaveAttribute('aria-valuetext', expect.stringContaining('12.4초'));

    startHandle.focus();
    // 1초씩 8번 당기면 12.4초 → 4.4초라 마지막 한 번이 막힌다
    for (let i = 0; i < 8; i += 1) await user.keyboard('{ArrowRight}');

    expect(startHandle).toHaveAttribute('aria-valuetext', expect.stringContaining('5.4초'));
    // 규칙은 범례가 미리 말해 둔다
    expect(screen.getByText('5초 미만, 3분 초과로는 핸들이 움직이지 않아요')).toBeInTheDocument();
  });

  it('재생 조작은 미리보기 아래 막대에 있다: 되감기·감기·재생·쇼츠 안 시각 · 반복·배속·썸네일 칩', async () => {
    const user = userEvent.setup();
    renderStudio();

    // 목업 구간 1:22:08.4 ~ 1:22:20.8(12.4초), 재생 위치 1:22:14 → 쇼츠 안 5.6초
    expect(transportClock()).toContain('0:05.6 / 0:12.4');
    expect(screen.getByRole('button', { name: '구간 반복' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByRole('button', { name: '재생 속도 1×' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '이 장면을 썸네일로' })).toBeEnabled();
    // 타임라인 위 막대(여기부터·여기까지·시작으로)는 없다: 구간 끝은 I·O 키와 손잡이가 맡는다
    expect(screen.queryByRole('toolbar', { name: '재생과 구간' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /여기부터|시작으로/ })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '1초 앞으로' }));
    expect(transportClock()).toContain('0:06.6');

    await user.click(screen.getByRole('button', { name: '구간 반복' }));
    expect(screen.getByRole('button', { name: '구간 반복' })).toHaveAttribute(
      'aria-pressed',
      'false',
    );

    await user.click(screen.getByRole('button', { name: '재생 속도 1×' }));
    await user.click(screen.getByRole('menuitem', { name: '2×' }));
    expect(screen.getByRole('button', { name: '재생 속도 2×' })).toBeInTheDocument();

    // 키보드로도 고른다: 열면 첫 항목(0.5×)에 초점이 가고 ↓ 한 번이 1×다(PR #341 2차)
    screen.getByRole('button', { name: '재생 속도 2×' }).focus();
    await user.keyboard('{Enter}');
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: '0.5×' }));
    await user.keyboard('{ArrowDown}{Enter}');
    expect(screen.getByRole('button', { name: '재생 속도 1×' })).toHaveFocus();

    // 메뉴 안 Space는 항목을 고른다: 편집기 단축키(재생 토글)로 새지 않는다(PR #341 3차)
    await user.keyboard('{Enter}{ArrowDown}{ArrowDown} ');
    expect(screen.getByRole('button', { name: '재생 속도 1.5×' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '재생' })).toBeInTheDocument();
  });

  it('되감기·감기 버튼이 아이콘 안의 초로 갈린다 — 방향만으로는 구분되지 않는다', () => {
    renderStudio();

    // 시안 1d는 원형 화살표 안에 5·1을 적어 네 버튼을 가른다
    expect(screen.getByRole('button', { name: '5초 뒤로' })).toHaveTextContent('5');
    expect(screen.getByRole('button', { name: '1초 뒤로' })).toHaveTextContent('1');
    expect(screen.getByRole('button', { name: '1초 앞으로' })).toHaveTextContent('1');
    expect(screen.getByRole('button', { name: '5초 앞으로' })).toHaveTextContent('5');
  });

  it('「이 장면을 썸네일로」를 누르면 영상 줄 앞 칸에 그 장면이 선다(POK-389)', async () => {
    const user = userEvent.setup();
    renderStudio();
    const timeline = screen.getByRole('region', { name: '타임라인' });
    expect(within(timeline).queryByRole('button', { name: /썸네일 장면/ })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '이 장면을 썸네일로' }));
    expect(within(timeline).getByRole('button', { name: '썸네일 장면 0:05' })).toBeInTheDocument();
  });

  it('고른 썸네일 장면을 영상 만들기 요청에 실어 보낸다: 창이 그 장면으로 채워진다', async () => {
    const user = userEvent.setup();
    const requestUpload = vi.fn();
    renderStudio({ actions: { requestUpload } });

    await user.click(screen.getByRole('button', { name: '업로드' }));
    expect(requestUpload).toHaveBeenLastCalledWith(expect.anything(), null);

    await user.click(screen.getByRole('button', { name: '이 장면을 썸네일로' }));
    await user.click(screen.getByRole('button', { name: '업로드' }));
    expect(requestUpload).toHaveBeenLastCalledWith(expect.anything(), 4934);
  });

  it('확대 단추가 타임라인 배율을 바꾼다', async () => {
    const user = userEvent.setup();
    renderStudio();

    expect(within(zoomGroup()).getByText('100%')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '타임라인 확대' }));
    expect(within(zoomGroup()).getByText('200%')).toBeInTheDocument();
  });

  it('− · ＋ 단추로 한 단계씩 줄이고 키우며, 끝 단계에서는 그쪽 단추가 꺼진다(POK-389)', async () => {
    const user = userEvent.setup();
    renderStudio();
    const zoomOut = screen.getByRole('button', { name: '타임라인 축소' });
    const zoomIn = screen.getByRole('button', { name: '타임라인 확대' });

    await user.click(zoomIn);
    expect(within(zoomGroup()).getByText('200%')).toBeInTheDocument();
    await user.click(zoomIn);
    expect(within(zoomGroup()).getByText('400%')).toBeInTheDocument();
    expect(zoomIn).toBeDisabled();

    for (let i = 0; i < 4; i++) await user.click(zoomOut);
    expect(within(zoomGroup()).getByText('25%')).toBeInTheDocument();
    expect(zoomOut).toBeDisabled();
    expect(zoomIn).toBeEnabled();
  });

  it('미리보기 영상을 못 틀면 미리보기 아래에 이유를 알린다: 빈 화면만 남으면 왜 안 되는지 모른다(POK-389)', () => {
    const playback = {
      playing: false,
      currentSeconds: 0,
      durationSeconds: 60,
      togglePlay: vi.fn(),
      seekTo: vi.fn(),
      seekBy: vi.fn(),
      setRate: vi.fn(),
      setBounds: vi.fn(),
    };
    const source = {
      clipTitle: '카드',
      sourceLabel: '방송',
      durationSeconds: 60,
      range: { startSeconds: 10, endSeconds: 20 },
    };
    const { unmount } = renderStudio({ source, playback: { ...playback, error: 'access' } });
    expect(
      screen.getByText('영상 출입증이 거절됐어요. 잠시 뒤 다시 열어 주세요'),
    ).toBeInTheDocument();
    unmount();
    renderStudio({ source, playback: { ...playback, error: 'no-recording' } });
    expect(screen.getByText('이 구간의 녹화가 없어 미리보기를 못 틀어요')).toBeInTheDocument();
  });

  it('카드 범위가 좁아도 100%에서 손잡이를 범위 끝까지 끌어 구간을 늘리고, 확대는 그대로 된다(로컬 리뷰 3라운드)', async () => {
    const user = userEvent.setup();
    // 카드 창 100~119초에 5초 구간. 100% = 범위 전체(19초)
    renderStudio({
      source: {
        clipTitle: '카드',
        sourceLabel: '방송',
        minSeconds: 100,
        durationSeconds: 119,
        range: { startSeconds: 107, endSeconds: 112 },
      },
      playback: {
        playing: false,
        currentSeconds: 107,
        durationSeconds: 119,
        error: null,
        togglePlay: vi.fn(),
        seekTo: vi.fn(),
        seekBy: vi.fn(),
        setRate: vi.fn(),
        setBounds: vi.fn(),
      },
    });
    // 범위보다 넓힐 수 없으니 축소 단추는 꺼져 있고 100%에 머문다: 25%·50%는 같은 폭이라 표시만 바뀐다(PR #341 codex)
    expect(screen.getByRole('button', { name: '타임라인 축소' })).toBeDisabled();
    expect(within(zoomGroup()).getByText('100%')).toBeInTheDocument();

    // 구간 늘리기: 끝점을 레인 오른쪽 끝까지 끌면 범위 끝(119초)까지 늘어난다
    const end = screen.getByRole('slider', { name: '구간 끝점' });
    const overlay = document.querySelector<HTMLElement>('[data-scrub]')!.parentElement!;
    overlay.getBoundingClientRect = () => ({ left: 0, width: 1000 }) as DOMRect;
    end.setPointerCapture = () => {};
    end.hasPointerCapture = () => true;
    fireEvent.pointerDown(end, { clientX: 600, pointerId: 3 });
    fireEvent.pointerMove(end, { clientX: 1000, pointerId: 3 });
    fireEvent.pointerUp(end, { pointerId: 3 });
    expect(Number(end.getAttribute('aria-valuenow'))).toBeCloseTo(119);

    // 확대: 200%면 눈금 폭이 범위의 절반이 된다
    const span = () => {
      const ticks = [...document.querySelectorAll('[data-ruler] span')].map((t) => t.textContent!);
      return ticks;
    };
    const at100 = span();
    await user.click(screen.getByRole('button', { name: '타임라인 확대' }));
    expect(span()).not.toEqual(at100);
    expect(screen.getByRole('button', { name: '타임라인 축소' })).toBeEnabled();
  });

  it('키운 채 끄는 동안은 창을 붙잡는다: 재생 위치가 창 밖으로 가도 끄던 자리 밑에서 창이 안 넘어간다(로컬 리뷰 2라운드)', async () => {
    const user = userEvent.setup();
    // 재생 위치를 바깥에서 바꾸는 재생기(실제 재생기처럼 seekTo가 위치를 옮긴다)
    let setPosition: (seconds: number) => void = () => {};
    function Harness() {
      const [position, setPos] = useState(130);
      setPosition = setPos;
      return (
        <StudioEditor
          source={{
            clipTitle: '카드',
            sourceLabel: '방송',
            durationSeconds: 400,
            range: { startSeconds: 100, endSeconds: 160 },
          }}
          playback={{
            playing: false,
            currentSeconds: position,
            durationSeconds: 400,
            error: null,
            togglePlay: vi.fn(),
            seekTo: setPos,
            seekBy: vi.fn(),
            setRate: vi.fn(),
            setBounds: vi.fn(),
          }}
        />
      );
    }
    renderWithProviders(<Harness />);
    await user.click(screen.getByRole('button', { name: '타임라인 확대' }));
    await user.click(screen.getByRole('button', { name: '타임라인 확대' }));
    const ticks = () =>
      [...document.querySelectorAll('[data-ruler] span')].map((tick) => tick.textContent);
    const before = ticks();

    const scrub = document.querySelector<HTMLElement>('[data-scrub]')!;
    scrub.parentElement!.getBoundingClientRect = () => ({ left: 0, width: 1000 }) as DOMRect;
    scrub.setPointerCapture = () => {};
    scrub.hasPointerCapture = () => true;
    fireEvent.pointerDown(scrub, { clientX: 500, pointerId: 1 });
    // 끄는 중에 재생 위치가 창 밖(150초)으로 가도 창은 그대로다
    act(() => setPosition(150));
    expect(ticks()).toEqual(before);
    // 놓으면 그때 재생 위치를 따라 넘어간다
    fireEvent.pointerUp(scrub, { pointerId: 1 });
    expect(ticks()).not.toEqual(before);
  });

  it('키운 창 밖으로 나간 썸네일 표시는 그리지 않는다: 가장자리에 붙으면 그 시각에 고른 것처럼 읽힌다(봇 리뷰)', async () => {
    const user = userEvent.setup();
    let setPosition: (seconds: number) => void = () => {};
    function Harness() {
      const [position, setPos] = useState(105);
      setPosition = setPos;
      return (
        <StudioEditor
          source={{
            clipTitle: '카드',
            sourceLabel: '방송',
            durationSeconds: 400,
            range: { startSeconds: 100, endSeconds: 160 },
          }}
          playback={{
            playing: false,
            currentSeconds: position,
            durationSeconds: 400,
            error: null,
            togglePlay: vi.fn(),
            seekTo: setPos,
            seekBy: vi.fn(),
            setRate: vi.fn(),
            setBounds: vi.fn(),
          }}
        />
      );
    }
    renderWithProviders(<Harness />);
    await user.click(screen.getByRole('button', { name: '이 장면을 썸네일로' }));
    const marker = () => document.querySelector('[data-thumbnail-marker]');
    expect(marker()).not.toBeNull();

    await user.click(screen.getByRole('button', { name: '타임라인 확대' }));
    await user.click(screen.getByRole('button', { name: '타임라인 확대' }));
    // 재생 위치가 멀리 가면 창이 따라가고, 105초에 고른 썸네일은 창 밖이다
    act(() => setPosition(155));
    expect(marker()).toBeNull();
  });

  it('키우면 구간보다 좁게 재생 위치 둘레만 보이고, 밖으로 나간 끝은 가장자리 표시가 대신한다(POK-389)', async () => {
    const user = userEvent.setup();
    const seekTo = vi.fn();
    renderStudio({
      source: {
        clipTitle: '카드',
        sourceLabel: '방송',
        durationSeconds: 400,
        range: { startSeconds: 100, endSeconds: 160 },
      },
      playback: {
        playing: false,
        currentSeconds: 130,
        durationSeconds: 400,
        error: null,
        togglePlay: vi.fn(),
        seekTo,
        seekBy: vi.fn(),
        setRate: vi.fn(),
        setBounds: vi.fn(),
      },
    });
    // 100%: 1분 구간 전체가 보이고 손잡이 둘이 다 있다(Tab으로 간다)
    const handles = () => screen.getAllByRole('slider', { name: /구간 (시작|끝)점/ });
    expect(handles()).toHaveLength(2);
    for (const handle of handles()) expect(handle).toHaveAttribute('tabindex', '0');
    expect(screen.queryByRole('button', { name: /으로 가기/ })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '타임라인 확대' }));
    await user.click(screen.getByRole('button', { name: '타임라인 확대' }));
    // 400%: 18.75초 창(121~139초)이라 두 끝이 다 밖이다. 손잡이는 잘려 안 보일 뿐 그대로 있다(키보드 초점을 잃지 않게)
    expect(screen.getAllByRole('slider', { name: /구간 (시작|끝)점/ })).toHaveLength(2);
    // 구간 상자는 창 밖까지 실제 자리로 뻗는다(덮개가 잘라 감춘다). 창 폭으로 자르면 손잡이가 창 가장자리에 남아,
    // 살짝 끌기만 해도 시작이 100초에서 창 끝(≈120.8초)으로 튄다(PR #341 codex)
    const box = document.querySelector<HTMLElement>('[class*="rangeBox"]')!;
    expect(parseFloat(box.style.left)).toBeCloseTo(-110, 0);
    expect(parseFloat(box.style.width)).toBeCloseTo(320, 0);
    // 잘려 안 보이는 손잡이는 Tab 차례에서 빠진다: 초점 표시 없이 경계를 바꾸지 않게(PR #341 2차)
    for (const handle of handles()) expect(handle).toHaveAttribute('tabindex', '-1');
    await user.click(screen.getByRole('button', { name: '시작점으로 가기' }));
    expect(seekTo).toHaveBeenLastCalledWith(100);
    await user.click(screen.getByRole('button', { name: '끝점으로 가기' }));
    expect(seekTo).toHaveBeenLastCalledWith(159.9);
  });

  it('초점을 가진 손잡이가 키운 창 밖으로 밀려나면 그쪽 가장자리 표시로 초점이 간다: 안 보이는 손잡이에 남지 않게(PR #341 3차)', async () => {
    const user = userEvent.setup();
    renderStudio({
      source: {
        clipTitle: '카드',
        sourceLabel: '방송',
        durationSeconds: 400,
        range: { startSeconds: 125, endSeconds: 135 },
      },
      playback: {
        playing: false,
        currentSeconds: 130,
        durationSeconds: 400,
        error: null,
        togglePlay: vi.fn(),
        seekTo: vi.fn(),
        seekBy: vi.fn(),
        setRate: vi.fn(),
        setBounds: vi.fn(),
      },
    });
    await user.click(screen.getByRole('button', { name: '타임라인 확대' }));
    await user.click(screen.getByRole('button', { name: '타임라인 확대' }));
    // 400%: 창 ≈120.6~139.4초라 끝점(135초)은 창 안이다
    const end = screen.getByRole('slider', { name: '구간 끝점' });
    expect(end).toHaveAttribute('tabindex', '0');
    end.focus();

    // 끝점을 5초 밀면 140초 → 창 밖: 초점이 「끝점으로 가기」로 옮겨 간다
    await user.keyboard('{Shift>}{ArrowRight}{/Shift}');
    expect(end).toHaveAttribute('aria-valuenow', '140');
    expect(screen.getByRole('button', { name: '끝점으로 가기' })).toHaveFocus();
  });

  it('단축키로 재생을 토글하고 되돌린다', async () => {
    const user = userEvent.setup();
    renderStudio();

    await user.keyboard(' ');
    expect(screen.getByRole('button', { name: '일시정지' })).toBeInTheDocument();
    await user.keyboard(' ');
    expect(screen.getByRole('button', { name: '재생' })).toBeInTheDocument();

    // O로 끝점을 플레이헤드(1:22:14)까지 당기면 되돌릴 거리가 생긴다
    await user.keyboard('{o}');
    const undo = screen.getByRole('button', { name: '작업 이전으로' });
    expect(undo).toBeEnabled();

    await user.keyboard('{Meta>}z{/Meta}');
    expect(undo).toBeDisabled();
  });

  // jsdom엔 레이아웃이 없어 겹침 자체(POK-237)는 여기서 못 잰다 — headroom이 Infinity로
  // 떨어지는 경로다. 여기서 지키는 건 손잡이가 높이 상태에 배선돼 있다는 것뿐이고,
  // 실제 상한은 브라우저 실측으로 확인한다.
  it('높이 조절 손잡이의 화살표 키가 레인 높이를 바꾼다', async () => {
    const user = userEvent.setup();
    renderStudio();

    const lane = document.querySelector<HTMLElement>('[data-timeline-lanes]');
    // 기본(null)일 땐 인라인 높이가 없다 — 레인 높이는 트랙 수가 정한다
    expect(lane).not.toBeNull();
    expect(lane?.style.height).toBe('');

    await user.click(screen.getByRole('button', { name: '타임라인 높이 조절' }));
    await user.keyboard('{ArrowUp}');

    // 한 걸음 올리면 레인 높이가 px로 굳는다
    expect(lane?.style.height).toMatch(/^\d+px$/);
  });

  it('타임라인이나 윗줄 눈금을 누르면 그 시점으로 가고, 누른 채 끌면 따라온다(POK-389)', () => {
    renderStudio();
    const scrub = document.querySelector<HTMLElement>('[data-scrub]')!;
    const ruler = document.querySelector<HTMLElement>('[data-ruler]')!;
    const overlay = scrub.parentElement!;
    // jsdom엔 레이아웃이 없다: 덮개 폭을 1000px로 둔다(눈금과 같은 가로 범위)
    overlay.getBoundingClientRect = () => ({ left: 0, width: 1000 }) as DOMRect;
    for (const el of [scrub, ruler]) el.setPointerCapture = () => {};
    scrub.hasPointerCapture = () => true;
    // 구간 상자의 자리로 쇼츠 안 n초가 몇 px인지 잰다(목업 구간 12.4초)
    const box = document.querySelector<HTMLElement>('[class*="rangeBox"]')!;
    const start = parseFloat(box.style.left) / 100;
    const width = parseFloat(box.style.width) / 100;
    const xAt = (seconds: number) => (start + (width * seconds) / 12.4) * 1000;
    // 쇼츠 안 몇 초인가: 재생 막대 시각이 곧 그 값이다
    const position = () => transportSeconds();

    fireEvent.pointerDown(scrub, { clientX: xAt(2.5), pointerId: 1 });
    expect(position()).toBeCloseTo(2.5, 0);
    fireEvent.pointerMove(scrub, { clientX: xAt(9.5), pointerId: 1 });
    expect(position()).toBeCloseTo(9.5, 0);
    fireEvent.pointerUp(scrub, { pointerId: 1 });

    fireEvent.pointerDown(ruler, { clientX: xAt(4.5), pointerId: 2 });
    expect(position()).toBeCloseTo(4.5, 0);
    fireEvent.pointerUp(ruler, { pointerId: 2 });

    // 오른쪽 클릭은 메뉴만: 재생 위치는 그대로다(PR #341 claude)
    fireEvent.pointerDown(scrub, { clientX: xAt(8), pointerId: 3, button: 2 });
    expect(position()).toBeCloseTo(4.5, 0);
  });

  it('타임라인을 접으면 선택 구간 요약만 남는다', async () => {
    const user = userEvent.setup();
    renderStudio();

    expect(screen.getByText('Space')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '타임라인 접기' }));

    expect(screen.getByText(/선택 구간/)).toBeInTheDocument();
    expect(screen.queryByText('Space')).not.toBeInTheDocument();
  });

  it('가운데 화면에는 판만 있다: 위 막대(비율·레이아웃·안전 영역)와 결과 아래 요약 줄이 없다', () => {
    renderStudio();

    expect(screen.queryByRole('toolbar', { name: '미리보기 보기' })).not.toBeInTheDocument();
    expect(screen.queryByRole('switch', { name: '안전 영역' })).not.toBeInTheDocument();
    expect(screen.queryByText(/· 자막 /)).not.toBeInTheDocument();
  });

  it('도구는 쇼츠 만드는 순서대로 다섯이다: 구간·BGM·효과는 없고 패널 위치는 탭 묶음에 끼지 않는다(POK-389)', () => {
    renderStudio();

    const tabs = screen.getAllByRole('tab').map((t) => t.textContent);
    expect(tabs).toEqual(['레이아웃', '자막', '텍스트', '오디오', '이미지']);
  });

  it('패널 위치 버튼이 레일과 패널을 좌우로 옮긴다 — 미리보기·타임라인은 그대로', async () => {
    const user = userEvent.setup();
    renderStudio();

    await user.click(screen.getByRole('tab', { name: '자막' }));
    const body = screen.getByRole('main');
    expect(body).toHaveAttribute('data-panel-side', 'left');

    const toggle = screen.getByRole('button', { name: '패널 위치 · 오른쪽으로 옮기기' });
    await user.click(toggle);

    expect(body).toHaveAttribute('data-panel-side', 'right');
    // 다시 누르면 돌아갈 수 있게 안내가 뒤집힌다
    expect(screen.getByRole('button', { name: '패널 위치 · 왼쪽으로 옮기기' })).toBeInTheDocument();
    // 옮겨도 도구·타임라인은 그대로 선다
    expect(screen.getByRole('region', { name: '자막' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '타임라인' })).toBeInTheDocument();
  });

  it('텍스트·이미지는 준비 중이라 누를 수 있는 버튼이 꺼져 있다', async () => {
    const user = userEvent.setup();
    renderStudio();

    await user.click(screen.getByRole('tab', { name: '텍스트' }));
    const text = screen.getByRole('region', { name: '텍스트' });
    expect(within(text).getByText('준비 중')).toBeInTheDocument();
    expect(within(text).getByRole('button', { name: /제목 넣기/ })).toBeDisabled();

    await user.click(screen.getByRole('tab', { name: '이미지' }));
    const image = screen.getByRole('region', { name: '이미지' });
    expect(within(image).getByText('준비 중')).toBeInTheDocument();
    expect(within(image).getByRole('button', { name: /로고 · 스티커 올리기/ })).toBeDisabled();
  });

  it('버튼을 누른 뒤에도 ⌘Z가 먹는다 — 포커스가 버튼에 남는 것이 정상 흐름이다', async () => {
    const user = userEvent.setup();
    renderStudio();

    await user.click(screen.getByRole('radio', { name: /세로/ }));
    expect(screen.getByRole('radio', { name: /세로/ })).toBeChecked();

    // 클릭한 버튼에 포커스가 남은 채로 되돌린다
    await user.keyboard('{Meta>}z{/Meta}');
    expect(screen.getByRole('radio', { name: /분할/ })).toBeChecked();
  });

  it('버튼 위에서 Space는 여전히 버튼을 누른다 — 재생을 가로채지 않는다', async () => {
    const user = userEvent.setup();
    renderStudio();

    const oneToOne = screen.getByRole('radio', { name: /중앙/ });
    oneToOne.focus();
    await user.keyboard(' ');

    expect(oneToOne).toBeChecked();
    expect(screen.getByRole('button', { name: '재생' })).toBeInTheDocument();
  });

  it('분할 비율 칩 묶음도 화살표로 옮긴다 — 화살표가 헛돌지 않는다', async () => {
    const user = userEvent.setup();
    renderStudio();

    const ratios = screen.getByRole('radiogroup', { name: '분할 비율' });
    within(ratios).getByRole('radio', { name: '50 : 50' }).focus();
    await user.keyboard('{ArrowRight}');
    expect(within(ratios).getByRole('radio', { name: '60 : 40' })).toBeChecked();
    // 시킹으로 새지 않았다
    expect(transportClock()).toContain('0:05.6');
  });

  it('크롭 사각형 위의 화살표는 사각형만 옮기고 플레이헤드는 건드리지 않는다', async () => {
    const user = userEvent.setup();
    renderStudio();
    const body = screen.getByRole('button', { name: /상단 영역/ });
    const before = body.parentElement?.getAttribute('style');

    body.focus();
    await user.keyboard('{ArrowRight}');
    expect(body.parentElement?.getAttribute('style')).not.toBe(before);
    expect(transportClock()).toContain('0:05.6');

    // 모서리도 같다 — 넓히기가 시킹으로 새지 않는다
    screen.getByRole('button', { name: '상단 오른쪽 아래 모서리' }).focus();
    await user.keyboard('{ArrowRight}');
    expect(transportClock()).toContain('0:05.6');
  });

  it('크롭 사각형 위의 Space는 재생을 토글한다 — 버튼이 삼키지 않는다', async () => {
    const user = userEvent.setup();
    renderStudio();

    screen.getByRole('button', { name: /상단 영역/ }).focus();
    await user.keyboard(' ');
    expect(screen.getByRole('button', { name: '일시정지' })).toBeInTheDocument();
    await user.keyboard(' ');
    expect(screen.getByRole('button', { name: '재생' })).toBeInTheDocument();
  });

  it('배치 타깃의 모서리도 방향키로 모양을 바꾼다 — 시킹으로 새지 않는다', async () => {
    const user = userEvent.setup();
    const { container } = renderStudio();
    await user.click(screen.getByRole('radio', { name: /크롭/ }));

    const overlay = () => container.querySelector('[data-placement="overlay"]') as HTMLElement;
    const before = overlay().style.width;
    screen.getByRole('button', { name: '작은 화면 자리 오른쪽 아래 모서리' }).focus();
    await user.keyboard('{ArrowRight}');

    expect(overlay().style.width).not.toBe(before);
    expect(transportClock()).toContain('0:05.6');
  });

  it('「직접」 색 선택기 한 번은 실행취소 한 칸이다 — 값마다 쌓이지 않는다', async () => {
    const user = userEvent.setup();
    renderStudio();
    await user.click(screen.getByRole('radio', { name: /중앙/ }));
    await user.click(screen.getByRole('radio', { name: '단색' }));
    const undo = screen.getByRole('button', { name: '작업 이전으로' });
    const undoCountBefore = (() => {
      // 지금까지 쌓인 칸 수를 세는 대신, 되돌리기 전 색을 기억해 둔다
      return screen.getByLabelText<HTMLInputElement>('배경 색 직접 고르기').value;
    })();

    const input = screen.getByLabelText<HTMLInputElement>('배경 색 직접 고르기');
    input.focus();
    // 네이티브 선택기가 색 영역을 끄는 동안 보내는 input 이벤트 흉내
    for (const color of ['#101010', '#202020', '#303030']) {
      fireEvent.change(input, { target: { value: color } });
    }
    input.blur();
    expect(input.value).toBe('#303030');

    // 한 번 되돌리면 선택기 열기 전 색으로 돌아간다 — 중간 색을 되짚지 않는다
    await user.click(undo);
    expect(input.value).toBe(undoCountBefore);
  });

  it('자막 메뉴는 쉬운 말이다: 번인·CC·경계 같은 말을 쓰지 않는다(POK-389)', async () => {
    const user = userEvent.setup();
    renderStudio();
    await user.click(screen.getByRole('tab', { name: '자막' }));

    const mode = screen.getByRole('radiogroup', { name: '자막 보이는 방식' });
    expect(
      within(mode)
        .getAllByRole('radio')
        .map((r) => r.textContent),
    ).toEqual(['영상에 새기기', '자막 파일', '둘 다']);
    const position = screen.getByRole('radiogroup', { name: '자막 위치' });
    expect(
      within(position)
        .getAllByRole('radio')
        .map((r) => r.textContent),
    ).toEqual(['위', '가운데', '아래']);
    expect(screen.getByText('글꼴')).toBeInTheDocument();
    expect(screen.queryByText(/번인|CC/)).not.toBeInTheDocument();
  });

  it('자막 위치 「가운데」는 분할 경계를 따른다: 50:50 이 아니면 한가운데가 아니다', async () => {
    const user = userEvent.setup();
    const { container } = renderStudio();
    await user.click(screen.getByRole('radio', { name: '70 : 30' }));
    await user.click(screen.getByRole('tab', { name: '자막' }));
    await user.click(
      within(screen.getByRole('radiogroup', { name: '자막 위치' })).getByRole('radio', {
        name: '가운데',
      }),
    );

    // 같은 문장이 자막 목록에도 있다 — 결과 위에 얹힌 번인 자막만 본다
    const burned = container.querySelector<HTMLElement>('[data-position="edge"]');
    expect(burned).not.toBeNull();
    expect(burned!.style.getPropertyValue('--pc-edge')).toBe('70%');
  });

  it('레이아웃은 세로 목록 다섯이다: 줄마다 이름 아래 설명이 보인다', () => {
    renderStudio();
    const group = screen.getByRole('radiogroup', { name: '레이아웃 종류' });
    const rows = within(group).getAllByRole('radio');
    expect(rows.map((row) => row.textContent)).toEqual([
      '세로9:16 꽉 채움',
      '분할상단 · 하단 2영역',
      '중앙원본 비율 유지',
      '크롭메인 + 작은 화면',
      '가로16:9 원본',
    ]);
  });

  it('레이아웃 카드에 포커스만 옮겨서는 레이아웃이 바뀌지 않는다 — 창 복귀 재포커스가 편집을 되살리면 안 된다', () => {
    renderStudio();
    screen.getByRole('radio', { name: /세로/ }).focus();
    expect(screen.getByRole('radio', { name: /분할/ })).toBeChecked();
  });

  it('겹친 두 프레임은 고른 쪽이 위로 온다 — 메인을 고르면 그 안의 배치 타깃도 잡힌다', async () => {
    const user = userEvent.setup();
    renderStudio();
    await user.click(screen.getByRole('radio', { name: /크롭/ }));
    const main = screen.getByRole('button', { name: /메인 화면 영역/ });
    const pip = screen.getByRole('button', { name: /작은 화면 영역/ });

    // 포커스가 곧 선택이다 (fireEvent 가 act 로 감싼다)
    fireEvent.focus(pip);
    expect(pip.parentElement?.style.zIndex).toBe('5');
    fireEvent.focus(main);
    expect(main.parentElement?.style.zIndex).toBe('5');
    expect(Number(pip.parentElement?.style.zIndex)).toBeLessThan(5);
  });

  it('레이아웃 묶음을 화살표로 옮길 수 있다', async () => {
    const user = userEvent.setup();
    renderStudio();

    // 시안 순서: 세로 · 분할 · 중앙 · 크롭 · 가로 — 세로 목록이라 위아래 화살표다
    screen.getByRole('radio', { name: /분할/ }).focus();
    await user.keyboard('{ArrowUp}');
    expect(screen.getByRole('radio', { name: /세로/ })).toBeChecked();

    await user.keyboard('{ArrowDown}{ArrowDown}');
    expect(screen.getByRole('radio', { name: /중앙/ })).toBeChecked();
  });

  it('가로는 목록에 보이되 고를 수 없다 — 누르거나 화살표로 닿아도 선택이 안 바뀐다', async () => {
    const user = userEvent.setup();
    renderStudio();

    const horiz = screen.getByRole('radio', { name: /가로/ });
    expect(horiz).toBeDisabled();

    await user.click(horiz);
    expect(screen.getByRole('radio', { name: /분할/ })).toBeChecked();

    // 크롭에서 아래로 내려가도 가로에 서지 않는다
    await user.click(screen.getByRole('radio', { name: /크롭/ }));
    await user.keyboard('{ArrowDown}');
    expect(horiz).not.toBeChecked();
    expect(horiz).not.toHaveFocus();
  });

  it('구간 핸들이 각자 자기 경계 위치를 읽어 준다', () => {
    renderStudio();

    const start = screen.getByRole('slider', { name: '구간 시작점' });
    const end = screen.getByRole('slider', { name: '구간 끝점' });
    // 둘이 같은 값을 말하면 스크린리더가 두 핸들을 구분하지 못한다
    expect(start.getAttribute('aria-valuenow')).not.toBe(end.getAttribute('aria-valuenow'));
    expect(Number(end.getAttribute('aria-valuenow'))).toBeGreaterThan(
      Number(start.getAttribute('aria-valuenow')),
    );
  });

  it('버튼에 포커스가 있어도 I·O는 통과한다 — Space·화살표는 계속 양보한다', async () => {
    const user = userEvent.setup();
    renderStudio();

    const play = screen.getByRole('button', { name: '재생' });
    play.focus();
    await user.keyboard('{o}');

    // O가 끝점을 플레이헤드로 당겼으니 되돌릴 거리가 생긴다
    expect(screen.getByRole('button', { name: '작업 이전으로' })).toBeEnabled();
    // Space는 여전히 버튼 것이라 재생으로 새지 않는다
    expect(screen.getByRole('button', { name: '재생' })).toBeInTheDocument();
  });

  it('창(dialog)이 떠 있으면 I·O·Space가 뒤 편집기를 안 바꾼다: 창 안 라디오는 Space로 골라진다(POK-291)', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <StudioEditor />
        <Dialog open>
          <Dialog.Content>
            <Dialog.Title>창</Dialog.Title>
            <RadioGroup aria-label="고르기" defaultValue="a">
              <RadioGroup.Item value="a" label="가" />
              <RadioGroup.Item value="b" label="나" />
            </RadioGroup>
            <button type="button">창 단추</button>
          </Dialog.Content>
        </Dialog>
      </>,
    );
    const dialog = within(screen.getByRole('dialog', { name: '창' }));

    dialog.getByRole('button', { name: '창 단추' }).focus();
    await user.keyboard('{o}{i}');
    // 버튼 위 O는 원래 통과해 끝점을 당긴다(위 시험): 창 안이면 막혀 되돌릴 거리가 안 생긴다
    expect(screen.getByRole('button', { name: '작업 이전으로', hidden: true })).toBeDisabled();

    const second = dialog.getByRole('radio', { name: '나' });
    second.focus();
    await user.keyboard(' ');
    expect(second).toBeChecked();
    expect(screen.getByRole('button', { name: '재생', hidden: true })).toBeInTheDocument();
  });

  it('핸들 ARIA 범위가 3분 상한까지 반영한다', () => {
    renderStudio();

    const start = screen.getByRole('slider', { name: '구간 시작점' });
    const end = screen.getByRole('slider', { name: '구간 끝점' });
    // 시작 핸들의 최소는 0이 아니라 「끝 − 3분」이다
    expect(Number(start.getAttribute('aria-valuemin'))).toBeGreaterThan(0);
    expect(
      Number(end.getAttribute('aria-valuenow')) - Number(start.getAttribute('aria-valuemin')),
    ).toBeLessThanOrEqual(180);
  });

  it('볼륨 슬라이더가 포인터로 살아 있다: 제스처 핸들러가 DS 핸들러를 덮지 않는다', async () => {
    const user = userEvent.setup();
    renderStudio();
    // 음량 막대는 트랙 줄의 음량 숫자를 누르면 뜨는 창 안에 있다
    await user.click(screen.getByRole('button', { name: '마이크 음량 80%' }));

    const [slider] = screen.getAllByRole('slider', { name: /볼륨/ });
    if (slider === undefined) throw new Error('볼륨 슬라이더가 없다');
    // jsdom에는 포인터 캡처가 없어 Slider 내부에서 던진다 — 있는 척만 해 준다
    const root = slider.parentElement?.parentElement as HTMLElement;
    root.setPointerCapture = () => {};
    root.hasPointerCapture = () => true;

    fireEvent.pointerDown(root, { clientX: 10, pointerId: 1 });

    // Slider의 onPointerDown이 살아 있으면 썸에 포커스가 간다.
    // 우리 핸들러가 덮어썼다면 이 줄이 깨진다 (실제로 그렇게 깨진 적이 있다).
    expect(document.activeElement).toBe(slider);
  });

  it('구간이 창보다 길어져도 핸들 둘이 남는다', async () => {
    const user = userEvent.setup();
    renderStudio();

    const end = screen.getByRole('slider', { name: '구간 끝점' });
    end.focus();
    // 5초씩 20번이면 구간이 100초를 넘겨 100% 줌 창(75초)보다 길어진다
    for (let i = 0; i < 20; i += 1) await user.keyboard('{Shift>}{ArrowRight}{/Shift}');

    expect(screen.getByRole('slider', { name: '구간 시작점' })).toBeInTheDocument();
    expect(screen.getByRole('slider', { name: '구간 끝점' })).toBeInTheDocument();
  });

  it('넣기 단추를 누른 뒤에도 ⌘Z가 먹는다: 단추는 글자 입력이 아니다', async () => {
    const user = userEvent.setup();
    renderStudio();

    const mic = screen.getByRole('button', { name: '마이크 넣기' });
    await user.click(mic);
    expect(mic).toHaveAttribute('aria-pressed', 'false');

    // 포커스가 넣기 단추에 남은 채로 되돌린다
    await user.keyboard('{Meta>}z{/Meta}');
    expect(screen.getByRole('button', { name: '마이크 넣기' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });

  it('키마다 주인이 다르다 — 버튼 위 화살표는 시킹, 슬라이더 위 Space는 재생', async () => {
    const user = userEvent.setup();
    renderStudio();

    // 버튼은 화살표를 안 쓴다 → 시킹이 통과해야 한다
    const play = screen.getByRole('button', { name: '재생' });
    play.focus();
    const before = transportClock();
    await user.keyboard('{ArrowRight}');
    expect(transportClock()).not.toBe(before);

    // 슬라이더는 Space를 안 쓴다 → 재생이 통과해야 한다
    screen.getByRole('slider', { name: '구간 시작점' }).focus();
    await user.keyboard(' ');
    expect(screen.getByRole('button', { name: '일시정지' })).toBeInTheDocument();
  });

  it('접근성 위반이 없다', async () => {
    const { container } = renderStudio();
    await act(async () => {
      expect(await axe(container)).toHaveNoViolations();
    });
  });
});

// --- 로딩 표시(POK-444) ---------------------------------------------------------
// 컨테이너(WiredStudio)가 넘기는 보내는 중 · 영상 진행 · 다시 읽기 상태를 헤더와 타임라인이 어떻게 보이는지.

describe('StudioScreen — 로딩 표시(POK-444)', () => {
  const realSource = {
    clipTitle: '카드',
    sourceLabel: '방송',
    durationSeconds: 60,
    range: { startSeconds: 10, endSeconds: 20 },
  };
  const realTracks = [0, 1, 2].map((i) => ({
    id: `track-${i}`,
    kind: 'mic' as const,
    label: `트랙 ${i + 1}`,
    volume: 100,
    muted: false,
  }));

  it('저장을 보내는 중이면 그 버튼만 돌고(aria-busy) 다른 쪽은 꺼진다: 컨테이너가 보내는 동안 둘 다 받지 않는다', () => {
    renderStudio({ busyAction: 'save' });
    const saving = screen.getByRole('button', { name: '편집본 저장 중' });
    expect(saving).toHaveAttribute('aria-busy', 'true');
    expect(saving).toBeDisabled();
    // 라벨은 자리만 지킨다(폭 유지) — 이름은 aria-label이 댄다
    expect(saving).toHaveTextContent('편집본 저장');
    const upload = screen.getByRole('button', { name: '업로드' });
    expect(upload).toBeDisabled();
    expect(upload).not.toHaveAttribute('aria-busy');
  });

  it('영상 주문을 보내는 중이면 주 버튼만 돌고 저장은 꺼진다', () => {
    renderStudio({ busyAction: 'render' });
    const ordering = screen.getByRole('button', { name: '업로드 요청 중' });
    expect(ordering).toHaveAttribute('aria-busy', 'true');
    expect(ordering).toBeDisabled();
    expect(screen.getByRole('button', { name: '편집본 저장' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '편집본 저장' })).not.toHaveAttribute('aria-busy');
  });

  it('보내는 것이 없으면 두 버튼 다 누를 수 있고 돌지 않는다', () => {
    renderStudio();
    for (const name of ['편집본 저장', '업로드']) {
      expect(screen.getByRole('button', { name })).toBeEnabled();
      expect(screen.getByRole('button', { name })).not.toHaveAttribute('aria-busy');
    }
  });

  it('헤더 문구 아래 영상 진행 막대: 퍼센트가 있으면 그 값, 없으면 불확정이다(0%로 지어내지 않는다)', () => {
    const { unmount } = renderStudio({ clipProgress: { value: 42 } });
    expect(screen.getByRole('progressbar', { name: '영상 진행' })).toHaveAttribute(
      'aria-valuenow',
      '42',
    );
    unmount();

    renderStudio({ clipProgress: { value: null } });
    expect(screen.getByRole('progressbar', { name: '영상 진행' })).not.toHaveAttribute(
      'aria-valuenow',
    );
  });

  it('기다릴 영상이 없으면 진행 막대가 없다', () => {
    renderStudio();
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
  });

  it('트랙 소리를 받는 중이면 소리 줄마다 줄 높이 스켈레톤이고, 못 불렀으면 빈 줄과 안내 그대로다', () => {
    const loading = renderStudio({
      source: realSource,
      tracks: realTracks,
      trackPreview: { status: 'loading', levels: null, setGains: vi.fn() },
    });
    const bars = loading.container.querySelectorAll('[data-wave-loading]');
    expect(bars).toHaveLength(realTracks.length);
    // 파형처럼 그림일 뿐이라 낭독에서 빠진다
    for (const bar of bars) expect(bar).toHaveAttribute('aria-hidden', 'true');
    loading.unmount();

    const unavailable = renderStudio({
      source: realSource,
      tracks: realTracks,
      trackPreview: { status: 'unavailable', levels: null, setGains: vi.fn() },
    });
    expect(unavailable.container.querySelectorAll('[data-wave-loading]')).toHaveLength(0);
    expect(
      screen.getByText('트랙별 미리 듣기를 못 불러왔어요 · 영상 소리로 들려요'),
    ).toBeInTheDocument();
    unavailable.unmount();

    // 받을 창이 없으면(녹화 없는 구간) 들을 것이 없다: 로더가 아니다
    const idle = renderStudio({
      source: realSource,
      tracks: realTracks,
      trackPreview: { status: 'idle', levels: null, setGains: vi.fn() },
    });
    expect(idle.container.querySelectorAll('[data-wave-loading]')).toHaveLength(0);
  });

  it('다른 편집 대상을 읽는 동안 옛 화면은 보이기만 한다: 헤더 아래 불확정 막대 한 줄, 이전 화면으로는 나갈 수 있다', async () => {
    const user = userEvent.setup();
    const { container } = renderStudio({ reloading: true });

    const bar = screen.getByRole('progressbar', { name: '편집 대상을 다시 불러오는 중' });
    expect(bar).not.toHaveAttribute('aria-valuenow');
    expect(container.querySelector('main')).toHaveAttribute('inert');
    expect(container.querySelector('section[aria-label="타임라인"]')).toHaveAttribute('inert');
    for (const name of ['편집본 저장', '업로드', '작업 이전으로', '작업 앞으로']) {
      expect(screen.getByRole('button', { name })).toBeDisabled();
    }
    expect(screen.getByRole('link', { name: '이전 화면으로' })).toHaveAttribute(
      'href',
      '/clips/library',
    );
    // 단축키도 쉰다: Space가 재생을 켜지 않는다
    await user.keyboard(' ');
    expect(container.querySelector('button[aria-label="일시정지"]')).toBeNull();
  });
});

// --- 크롭·레이아웃 -------------------------------------------------------------
// 원본 판은 아직 자리 표시자다. 사각형은 목업 해상도(1920×1080) 기준으로 잡히므로
// 영상 없이도 좌표·경계·실행취소를 그대로 확인할 수 있다.

describe('StudioScreen — 크롭 영역 (E5)', () => {
  it('소스 위에 잡을 영역과 모서리 네 개가 선다', () => {
    renderStudio();

    expect(screen.getByRole('region', { name: '원본' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '결과' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /상단 영역/ })).toBeInTheDocument();
    for (const corner of ['왼쪽 위', '오른쪽 위', '왼쪽 아래', '오른쪽 아래']) {
      expect(screen.getByRole('button', { name: `상단 ${corner} 모서리` })).toBeInTheDocument();
    }
  });

  it('상하분할은 한 소스에 사각형 두 개를 얹는다', () => {
    renderStudio();

    expect(screen.getByRole('button', { name: /상단 영역/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /하단 영역/ })).toBeInTheDocument();
  });

  it('사각형을 방향키로 옮긴다', async () => {
    const user = userEvent.setup();
    renderStudio();
    const body = screen.getByRole('button', { name: /상단 영역/ });

    const before = body.parentElement?.getAttribute('style');
    body.focus();
    await user.keyboard('{ArrowRight}');

    expect(body.parentElement?.getAttribute('style')).not.toBe(before);
  });

  it('모서리 방향키가 범위를 넓히고 좁힌다', async () => {
    const user = userEvent.setup();
    renderStudio();
    const handle = screen.getByRole('button', { name: '상단 오른쪽 아래 모서리' });
    const rect = () => handle.parentElement?.style.width ?? '';

    handle.focus();
    const before = rect();
    await user.keyboard('{ArrowRight}');
    const grown = rect();
    expect(Number.parseFloat(grown)).toBeGreaterThan(Number.parseFloat(before));

    await user.keyboard('{ArrowLeft}');
    expect(Number.parseFloat(rect())).toBeLessThan(Number.parseFloat(grown));
  });

  it('옮긴 뒤 실행취소로 되돌린다', async () => {
    const user = userEvent.setup();
    renderStudio();
    const body = screen.getByRole('button', { name: /상단 영역/ });
    const rect = () => body.parentElement?.getAttribute('style');

    const before = rect();
    body.focus();
    await user.keyboard('{ArrowRight}');
    expect(rect()).not.toBe(before);

    await user.click(screen.getByRole('button', { name: '작업 이전으로' }));
    expect(rect()).toBe(before);
  });

  it('접근성 위반이 없다', async () => {
    const { container } = renderStudio();
    expect(await axe(container)).toHaveNoViolations();
  });
});

describe('StudioScreen — 중앙 모드 여백 채우기', () => {
  it('중앙을 고를 때만 보이고, 블러면 강도 슬라이더가, 단색이면 스와치가 열린다', async () => {
    const user = userEvent.setup();
    renderStudio();

    expect(screen.queryByRole('radiogroup', { name: '여백 채우기' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('radio', { name: /중앙/ }));
    expect(screen.getByRole('radio', { name: '블러' })).toBeChecked();
    // 시안 기본 60
    expect(screen.getByRole('slider', { name: '블러 강도' })).toHaveAttribute(
      'aria-valuenow',
      '60',
    );
    expect(screen.queryByRole('radiogroup', { name: '배경 색' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('radio', { name: '단색' }));
    expect(screen.queryByRole('slider', { name: '블러 강도' })).not.toBeInTheDocument();
    const swatches = screen.getByRole('radiogroup', { name: '배경 색' });
    expect(within(swatches).getByRole('radio', { name: '검정' })).toBeChecked();

    await user.click(within(swatches).getByRole('radio', { name: '흰색' }));
    expect(within(swatches).getByRole('radio', { name: '흰색' })).toBeChecked();
    expect(screen.getByLabelText('배경 색 직접 고르기')).toHaveValue('#ffffff');
  });

  it('블러 강도 슬라이더가 결과의 블러 변수를 움직인다', async () => {
    const user = userEvent.setup();
    const { container } = renderStudio();
    await user.click(screen.getByRole('radio', { name: /중앙/ }));
    const pane = container.querySelector('[data-placement="contain"]') as HTMLElement;
    expect(pane.style.getPropertyValue('--pc-blur')).toBe('0.6');

    const slider = screen.getByRole('slider', { name: '블러 강도' });
    slider.focus();
    await user.keyboard('{ArrowLeft}');

    expect(slider).toHaveAttribute('aria-valuenow', '59');
    expect(pane.style.getPropertyValue('--pc-blur')).toBe('0.59');
  });

  it('접근성 위반이 없다 — 단색 스와치까지', async () => {
    const user = userEvent.setup();
    const { container } = renderStudio();
    await user.click(screen.getByRole('radio', { name: /중앙/ }));
    await user.click(screen.getByRole('radio', { name: '단색' }));
    expect(await axe(container)).toHaveNoViolations();
  });
});

describe('StudioScreen — 크롭 모드 작은 화면', () => {
  it('작은 화면 자리·비율은 패널 설정 없이 원본의 프레임과 자리, 두 사각형의 꼭짓점으로 잡는다', async () => {
    const user = userEvent.setup();
    renderStudio();
    await user.click(screen.getByRole('radio', { name: /크롭/ }));

    // 따로 고르는 비율·크기 설정은 없다 — 사각형이 곧 설정이다
    expect(screen.queryByRole('radiogroup', { name: '작은 화면 비율' })).not.toBeInTheDocument();
    expect(screen.queryByRole('slider', { name: '작은 화면 크기' })).not.toBeInTheDocument();

    for (const corner of ['왼쪽 위', '오른쪽 위', '왼쪽 아래', '오른쪽 아래']) {
      expect(
        screen.getByRole('button', { name: `작은 화면 ${corner} 모서리` }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole('button', { name: `작은 화면 자리 ${corner} 모서리` }),
      ).toBeInTheDocument();
    }
  });

  it('테두리는 켜진 채 시작하고, 끄면 굵기·색 고르기가 접히며 결과의 라인이 사라진다', async () => {
    const user = userEvent.setup();
    const { container } = renderStudio();
    await user.click(screen.getByRole('radio', { name: /크롭/ }));

    const toggle = screen.getByRole('switch', { name: '테두리 표시' });
    expect(toggle).toBeChecked();
    const widths = screen.getByRole('radiogroup', { name: '테두리 굵기' });
    expect(within(widths).getByRole('radio', { name: '1px' })).toBeChecked();
    const colors = screen.getByRole('radiogroup', { name: '테두리 색' });
    expect(within(colors).getByRole('radio', { name: '흰색' })).toBeChecked();

    const overlay = () => container.querySelector('[data-placement="overlay"]') as HTMLElement;
    expect(overlay().style.borderRadius).not.toBe('');

    await user.click(within(widths).getByRole('radio', { name: '3px' }));
    expect(overlay().style.border).toContain('3');

    await user.click(toggle);
    expect(toggle).not.toBeChecked();
    expect(screen.queryByRole('radiogroup', { name: '테두리 굵기' })).not.toBeInTheDocument();
    expect(overlay().style.borderRadius).toBe('');
  });

  it('접근성 위반이 없다 — 테두리 설정까지', async () => {
    const user = userEvent.setup();
    const { container } = renderStudio();
    await user.click(screen.getByRole('radio', { name: /크롭/ }));
    expect(await axe(container)).toHaveNoViolations();
  });
});
