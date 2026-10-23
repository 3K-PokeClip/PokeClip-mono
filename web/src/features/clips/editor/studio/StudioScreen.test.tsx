import { act } from 'react';
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

describe('StudioScreen', () => {
  it('헤더에 제목·자동 저장·저장 버튼을 렌더한다', () => {
    renderStudio();

    expect(screen.getByRole('heading', { name: '승급전 마지막 한타 역전' })).toBeInTheDocument();
    expect(screen.getByText('라이브 카드 1:24:03 · 방금 자동 저장됨')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '보관함으로' })).toHaveAttribute(
      'href',
      '/clips/library',
    );
    // 쇼츠 편집 화면에는 템플릿 저장이 없다(POK-389) — 저장은 편집본 저장 하나다
    expect(screen.queryByRole('button', { name: '템플릿 저장' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '업로드' })).toBeEnabled();
    // 아직 편집한 적이 없으니 되돌릴 곳도 없다
    expect(screen.getByRole('button', { name: '작업 이전으로' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '작업 앞으로' })).toBeDisabled();
  });

  it('타임라인은 자막 · 영상 · 방송 소리 트랙 줄이다 — 준비 중인 음악·효과음·이미지 줄은 없다(POK-389)', () => {
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

  it('소리 트랙 줄 머리에서 바로 넣고 뺀다 — 오디오 메뉴와 같은 값이다(POK-389)', async () => {
    const user = userEvent.setup();
    const { container } = renderStudio();
    const timeline = screen.getByRole('region', { name: '타임라인' });

    const discord = within(timeline).getByRole('button', { name: '디스코드 넣기' });
    expect(discord).toHaveAttribute('aria-pressed', 'true');
    await user.click(discord);
    expect(discord).toHaveAttribute('aria-pressed', 'false');
    expect(within(timeline).getByText('안 넣음')).toBeInTheDocument();
    expect(container.querySelector('[data-track-row="discord"]')).toHaveAttribute('data-muted');

    await user.click(screen.getByRole('tab', { name: '오디오' }));
    expect(screen.getByRole('checkbox', { name: '디스코드 넣기' })).not.toBeChecked();
  });

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
    // 같은 문장이 타임라인 자막 줄에도 선다 — 자막 패널 안만 본다
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

  it('재생 조작은 타임라인 위 막대에 있다 — 미리보기 아래 재생 막대·배속·구간 반복은 없다(POK-389)', async () => {
    const user = userEvent.setup();
    renderStudio();
    const bar = screen.getByRole('toolbar', { name: '재생과 구간' });

    // 목업 구간 1:22:08.4 ~ 1:22:20.8(12.4초), 재생 위치 1:22:14 → 쇼츠 안 5.6초(내림 0:05)
    expect(within(bar).getByRole('status', { name: '재생 위치' })).toHaveTextContent('0:05 / 0:12');
    expect(within(bar).getByText('쇼츠 12.4초 · 최대 3분')).toBeInTheDocument();
    expect(screen.queryByRole('radiogroup', { name: '재생 속도' })).not.toBeInTheDocument();
    expect(screen.queryByRole('switch', { name: '구간 반복' })).not.toBeInTheDocument();

    // 여기부터 = I 키: 시작이 재생 위치로 와서 쇼츠 안 0초가 된다
    await user.click(within(bar).getByRole('button', { name: /여기부터/ }));
    expect(within(bar).getByRole('status', { name: '재생 위치' })).toHaveTextContent('0:00 / 0:06');
    expect(within(bar).getByText('쇼츠 6.8초 · 최대 3분')).toBeInTheDocument();

    // 시작으로 = 쇼츠 맨 앞으로
    await user.keyboard('{ArrowRight}{ArrowRight}');
    expect(within(bar).getByRole('status', { name: '재생 위치' })).toHaveTextContent('0:02 / 0:06');
    await user.click(within(bar).getByRole('button', { name: /시작으로/ }));
    expect(within(bar).getByRole('status', { name: '재생 위치' })).toHaveTextContent('0:00 / 0:06');
  });

  it('「이 장면을 썸네일로」를 누르면 영상 줄 앞 칸에 그 장면이 선다(POK-389)', async () => {
    const user = userEvent.setup();
    renderStudio();
    const timeline = screen.getByRole('region', { name: '타임라인' });
    expect(within(timeline).queryByRole('button', { name: /썸네일 장면/ })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '이 장면을 썸네일로' }));
    expect(within(timeline).getByRole('button', { name: '썸네일 장면 0:05' })).toBeInTheDocument();
  });

  it('고른 썸네일 장면을 영상 만들기 요청에 실어 보낸다 — 창이 그 장면으로 채워진다', async () => {
    const user = userEvent.setup();
    const requestUpload = vi.fn();
    renderStudio({ actions: { requestUpload } });

    await user.click(screen.getByRole('button', { name: '업로드' }));
    expect(requestUpload).toHaveBeenLastCalledWith(expect.anything(), null);

    await user.click(screen.getByRole('button', { name: '이 장면을 썸네일로' }));
    await user.click(screen.getByRole('button', { name: '업로드' }));
    expect(requestUpload).toHaveBeenLastCalledWith(expect.anything(), 4934);
  });

  it('확대 막대가 타임라인 배율을 바꾼다', async () => {
    const user = userEvent.setup();
    renderStudio();

    const bar = screen.getByRole('toolbar', { name: '재생과 구간' });
    const zoom = within(bar).getByRole('slider', { name: '타임라인 확대' });
    expect(within(bar).getByText('100%')).toBeInTheDocument();
    zoom.focus();
    await user.keyboard('{ArrowRight}');
    expect(within(bar).getByText('200%')).toBeInTheDocument();
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

  it('타임라인을 접으면 선택 구간 요약만 남는다', async () => {
    const user = userEvent.setup();
    renderStudio();

    expect(screen.getByText('Space')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '타임라인 접기' }));

    expect(screen.getByText(/선택 구간/)).toBeInTheDocument();
    expect(screen.queryByText('Space')).not.toBeInTheDocument();
  });

  it('레이아웃을 고르면 미리보기 안내가 따라온다', async () => {
    const user = userEvent.setup();
    renderStudio();

    expect(screen.getByText('상하 분할 · 자막 영상+파일')).toBeInTheDocument();

    await user.click(screen.getByRole('radio', { name: /전체/ }));
    expect(screen.getByRole('radio', { name: /전체/ })).toBeChecked();
  });

  it('도구는 쇼츠 만드는 순서대로 다섯이다 — 구간·BGM·효과·패널 위치는 없다(POK-389)', () => {
    renderStudio();

    const tabs = screen.getAllByRole('tab').map((t) => t.textContent);
    expect(tabs).toEqual(['레이아웃', '자막', '텍스트', '오디오', '이미지']);
    expect(screen.queryByRole('button', { name: /패널 위치/ })).not.toBeInTheDocument();
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

    await user.click(screen.getByRole('radio', { name: /전체/ }));
    expect(screen.getByRole('radio', { name: /전체/ })).toBeChecked();

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
    expect(screen.getByRole('status', { name: '재생 위치' })).toHaveTextContent('0:05 / 0:12');
  });

  it('크롭 사각형 위의 화살표는 사각형만 옮기고 플레이헤드는 건드리지 않는다', async () => {
    const user = userEvent.setup();
    renderStudio();
    const body = screen.getByRole('button', { name: /상단 영역/ });
    const before = body.parentElement?.getAttribute('style');

    body.focus();
    await user.keyboard('{ArrowRight}');
    expect(body.parentElement?.getAttribute('style')).not.toBe(before);
    expect(screen.getByRole('status', { name: '재생 위치' })).toHaveTextContent('0:05 / 0:12');

    // 모서리도 같다 — 넓히기가 시킹으로 새지 않는다
    screen.getByRole('button', { name: '상단 오른쪽 아래 모서리' }).focus();
    await user.keyboard('{ArrowRight}');
    expect(screen.getByRole('status', { name: '재생 위치' })).toHaveTextContent('0:05 / 0:12');
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
    expect(screen.getByRole('status', { name: '재생 위치' })).toHaveTextContent('0:05 / 0:12');
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

  it('자막 메뉴는 쉬운 말이다 — 번인·CC·경계 같은 말을 쓰지 않는다(POK-389)', async () => {
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

  it('자막 위치 「가운데」는 분할 경계를 따른다 — 50:50 이 아니면 한가운데가 아니다', async () => {
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

  it('미리보기 위 막대가 비율·레이아웃을 말하고 안전 영역을 켜고 끈다 — 편집 기록은 안 바뀐다(POK-389)', async () => {
    const user = userEvent.setup();
    const { container } = renderStudio();
    const bar = screen.getByRole('toolbar', { name: '미리보기 보기' });

    expect(within(bar).getByText('9:16')).toBeInTheDocument();
    expect(within(bar).getByText('상하 분할')).toBeInTheDocument();
    expect(container.querySelector('[data-safe-zone]')).toBeNull();

    await user.click(within(bar).getByRole('switch', { name: '안전 영역' }));
    expect(container.querySelector('[data-safe-zone]')).not.toBeNull();
    // 보는 방식일 뿐이라 되돌릴 거리가 생기지 않는다
    expect(screen.getByRole('button', { name: '작업 이전으로' })).toBeDisabled();

    await user.click(screen.getByRole('radio', { name: /전체/ }));
    expect(within(bar).getByText('전체')).toBeInTheDocument();
  });

  it('레이아웃은 폰 모양 타일 다섯이다 — 이름만 읽고 설명은 덧붙임으로(POK-389)', () => {
    renderStudio();
    const group = screen.getByRole('radiogroup', { name: '레이아웃 종류' });
    const tiles = within(group).getAllByRole('radio');
    expect(tiles.map((tile) => tile.textContent)).toEqual([
      '전체',
      '상하 분할',
      '중앙',
      '크롭',
      '가로',
    ]);
    expect(tiles[1]).toHaveAccessibleDescription('위 · 아래 두 칸');
  });

  it('레이아웃 카드에 포커스만 옮겨서는 레이아웃이 바뀌지 않는다 — 창 복귀 재포커스가 편집을 되살리면 안 된다', () => {
    renderStudio();
    screen.getByRole('radio', { name: /전체/ }).focus();
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
    expect(screen.getByRole('radio', { name: /전체/ })).toBeChecked();

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

  it('볼륨 슬라이더가 포인터로 살아 있다 — 제스처 핸들러가 DS 핸들러를 덮지 않는다', async () => {
    const user = userEvent.setup();
    renderStudio();
    await user.click(screen.getByRole('tab', { name: '오디오' }));

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

  it('체크박스를 누른 뒤에도 ⌘Z가 먹는다 — 체크박스는 글자 입력이 아니다', async () => {
    const user = userEvent.setup();
    renderStudio();

    await user.click(screen.getByRole('tab', { name: '오디오' }));
    const mic = screen.getByRole('checkbox', { name: '마이크 넣기' });
    await user.click(mic);
    expect(mic).not.toBeChecked();

    // 포커스가 체크박스(input)에 남은 채로 되돌린다
    await user.keyboard('{Meta>}z{/Meta}');
    expect(screen.getByRole('checkbox', { name: '마이크 넣기' })).toBeChecked();
  });

  it('키마다 주인이 다르다 — 버튼 위 화살표는 시킹, 슬라이더 위 Space는 재생', async () => {
    const user = userEvent.setup();
    renderStudio();

    // 버튼은 화살표를 안 쓴다 → 시킹이 통과해야 한다
    const play = screen.getByRole('button', { name: '재생' });
    play.focus();
    const clock = () => screen.getByRole('status', { name: '재생 위치' }).textContent ?? '';
    const before = clock();
    await user.keyboard('{ArrowRight}');
    expect(clock()).not.toBe(before);

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
