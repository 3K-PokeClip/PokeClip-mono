import { act } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ToastProvider } from '@/ui';
import { LiveScreen } from '@/features/broadcast/livenow/LiveScreen';
import { CARD_CREATE_MS } from '@/features/broadcast/livenow/useManualMarking';

// useMediaSource가 쓰는 useSearchParams 대체 — 아래 env 고정과 함께 소스를 null로 만들어
// 플레이어가 시뮬레이션 경로로 결정적으로 돌게 한다.
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(),
}));

// env는 셸에서 그대로 상속된다 — 로컬/CI 셸에 NEXT_PUBLIC_MEDIA_*가 export돼 있어도
// 시뮬레이션 경로가 유지되도록 빈 값으로 고정한다 (빈 문자열 → useMediaSource가 null 반환).
vi.stubEnv('NEXT_PUBLIC_MEDIA_STUB_URL', '');
vi.stubEnv('NEXT_PUBLIC_MEDIA_LIVE_BASE_URL', '');

// GlassPlayer가 useToast를 쓰므로 ToastProvider로 감싼다 (앱에선 providers.tsx가 담당).
function renderLive() {
  return render(
    <ToastProvider>
      <LiveScreen />
    </ToastProvider>,
  );
}

afterEach(() => {
  vi.useRealTimers();
});

describe('LiveScreen — 방송 정보 바', () => {
  it('영상 아래 줄에 제목·태그·시청자·경과 시간을 세운다', () => {
    renderLive();

    expect(
      screen.getByRole('heading', { name: '새벽 랭크 올리기 — 다이아 승급전 가보자' }),
    ).toBeInTheDocument();
    expect(screen.getByText('리그 오브 레전드')).toBeInTheDocument();
    expect(screen.getByText('다이아승급')).toBeInTheDocument();
    expect(screen.getByText('1,842명')).toBeInTheDocument();
    expect(screen.getByText('스트리밍 중')).toBeInTheDocument();
    expect(screen.getByRole('main')).toBeInTheDocument();
  });

  it('페이지 헤더가 없다 — 시안 1b는 콘텐츠부터 시작한다', () => {
    renderLive();
    expect(screen.queryByRole('link', { name: '홈으로' })).not.toBeInTheDocument();
  });
});

describe('LiveScreen — 하이라이트 카드', () => {
  it('상태 배지를 계약 status에 맞춰 그린다', () => {
    renderLive();

    expect(screen.getByText('97점')).toBeInTheDocument();
    expect(screen.getByText('편집 중 · 박편집')).toBeInTheDocument();
    expect(screen.getByText('클립 완료')).toBeInTheDocument();
    expect(screen.getByText('미처리')).toBeInTheDocument();
    expect(screen.getByText('만료')).toBeInTheDocument();
  });

  it('필터 칩이 실제 카드 수를 센다 — 시안 표기와 같은 전체 8 · 자동 6 · 수동 2', () => {
    renderLive();

    expect(screen.getByRole('button', { name: '전체 8' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: '자동 6' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '수동 2' })).toBeInTheDocument();
  });

  it('"자동" 필터를 누르면 수동 마킹 카드가 사라진다', async () => {
    const user = userEvent.setup();
    renderLive();

    expect(screen.getByText('시청자 참여 미션 성공')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '자동 6' }));
    expect(screen.queryByText('시청자 참여 미션 성공')).not.toBeInTheDocument();
    expect(screen.getByText('승급전 마지막 한타 역전')).toBeInTheDocument();
  });

  it('카드를 누르면 그 시점으로 영상이 이동한다', async () => {
    const user = userEvent.setup();
    renderLive();

    // 0:47:22 = 2842초, 방송 경과 5043초 → 시차 2201초
    await user.click(screen.getByRole('button', { name: '0:47:22 시점으로 이동' }));
    expect(screen.getByRole('slider', { name: '라이브 탐색' })).toHaveAttribute(
      'aria-valuenow',
      '-2201',
    );
  });
});

describe('LiveScreen — 수동 마킹', () => {
  // 3초를 실제로 기다리면 스위트가 느려진다. userEvent는 fake timer와 엉키므로 fireEvent를 쓴다
  // (GlassPlayer 테스트의 자동 숨김 케이스와 같은 이유).

  it('버튼을 누르면 피드백과 만드는 중 자리가 서고, 잠시 뒤 카드가 된다', () => {
    vi.useFakeTimers();
    renderLive();

    fireEvent.click(screen.getByRole('button', { name: /수동 마킹/ }));
    expect(screen.getByText('1:24:03 마킹됨 · 카드 생성 중')).toBeInTheDocument();
    expect(screen.getByText('카드 만드는 중…')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(CARD_CREATE_MS);
    });

    expect(screen.queryByText('카드 만드는 중…')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '1:24:03 수동 마킹' })).toBeInTheDocument();
    // 만들어진 카드가 필터 개수에도 바로 반영된다
    expect(screen.getByRole('button', { name: '수동 3' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '전체 9' })).toBeInTheDocument();
  });

  it('경과 시간이 흐르고, 마킹은 멈춘 값이 아니라 그때의 시각을 찍는다', () => {
    // 회귀: 표기와 마킹이 목업 상수 1:24:03에 묶여 있어, 열어 둔 채 누른 카드가 과거를 가리켰다
    vi.useFakeTimers();
    renderLive();

    expect(screen.getByText('1:24:03')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(120_000); // 2분
    });
    expect(screen.getByText('1:26:03')).toBeInTheDocument();

    fireEvent.keyDown(document, { key: 'F8' });
    expect(screen.getByText('1:26:03 마킹됨 · 카드 생성 중')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(CARD_CREATE_MS);
    });
    // 카드가 서기까지 3초가 더 흘러도 시각은 누른 순간으로 굳어 있다
    expect(screen.getByRole('heading', { name: '1:26:03 수동 마킹' })).toBeInTheDocument();
  });

  it('「자동」을 보고 있을 땐 만드는 중 자리도 두지 않는다', () => {
    // 만들어질 카드는 수동이라 이 필터에선 안 보인다 — 자리만 섰다 사라지면 실패로 읽힌다
    vi.useFakeTimers();
    renderLive();

    fireEvent.click(screen.getByRole('button', { name: '자동 6' }));
    fireEvent.keyDown(document, { key: 'F8' });

    expect(screen.queryByText('카드 만드는 중…')).not.toBeInTheDocument();
    // 눌린 것 자체는 버튼 아래 피드백이 말한다
    expect(screen.getByText(/마킹됨 · 카드 생성 중/)).toBeInTheDocument();
  });

  it('F8을 누르면 어디에 포커스가 있든 같은 흐름이 돈다', () => {
    vi.useFakeTimers();
    renderLive();

    fireEvent.keyDown(document, { key: 'F8' });
    expect(screen.getByText('카드 만드는 중…')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(CARD_CREATE_MS);
    });
    expect(screen.getByRole('button', { name: '수동 3' })).toBeInTheDocument();
  });

  it('만드는 중에 또 눌러도 카드는 하나만 생긴다', () => {
    vi.useFakeTimers();
    renderLive();

    fireEvent.keyDown(document, { key: 'F8' });
    fireEvent.keyDown(document, { key: 'F8' });
    fireEvent.click(screen.getByRole('button', { name: /수동 마킹/ }));

    act(() => {
      vi.advanceTimersByTime(CARD_CREATE_MS);
    });
    expect(screen.getByRole('button', { name: '수동 3' })).toBeInTheDocument();
  });

  it('자동반복과 입력 중인 곳에서 누른 F8은 무시한다', () => {
    renderLive();

    // 누르고 있으면 초당 수십 장이 생긴다
    fireEvent.keyDown(document, { key: 'F8', repeat: true });
    expect(screen.queryByText('카드 만드는 중…')).not.toBeInTheDocument();

    // 볼륨 슬라이더처럼 키에 자기 동작이 있는 위젯 위에서도 가로채지 않는다
    fireEvent.keyDown(screen.getByRole('slider', { name: '볼륨' }), { key: 'F8' });
    expect(screen.queryByText('카드 만드는 중…')).not.toBeInTheDocument();
  });
});

describe('LiveScreen — 실시간 채팅 패널', () => {
  it('급증 키워드·후원·하이라이트 감지 줄을 그린다', () => {
    renderLive();

    expect(screen.getByText('ㅋㅋㅋㅋ ×214')).toBeInTheDocument();
    expect(screen.getByText('미쳤다 ×86')).toBeInTheDocument();
    expect(screen.getByText('도네초코 · 치즈 5,000')).toBeInTheDocument();
    expect(screen.getByText('승급 기원!! 가즈아')).toBeInTheDocument();
    expect(
      screen.getByText('하이라이트 감지 · 1:24:03 구간이 카드로 만들어졌어요'),
    ).toBeInTheDocument();
  });

  it('수집 상태를 채팅 헤더 배지가 말한다 — 옛 본문 경고 배너 자리를 승계했다', () => {
    renderLive();

    expect(screen.getByText('수집 끊김')).toBeInTheDocument();
    expect(screen.queryByText(/채팅 수집이 잠시 끊겼어요/)).not.toBeInTheDocument();
  });

  it('접으면 패널이 사라지고, 플레이어 상단의 여는 버튼으로 되살아난다', async () => {
    const user = userEvent.setup();
    renderLive();

    expect(screen.getByRole('complementary', { name: '실시간 채팅' })).toBeInTheDocument();
    // 열려 있는 동안엔 여는 버튼이 자리를 차지하지 않는다
    expect(screen.queryByRole('button', { name: '채팅 열기' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '채팅 패널 접기' }));
    expect(screen.queryByRole('complementary', { name: '실시간 채팅' })).not.toBeInTheDocument();

    // 패널이 사라져도 복귀 통로는 플레이어 안에 남는다
    await user.click(screen.getByRole('button', { name: '채팅 열기' }));
    expect(screen.getByRole('complementary', { name: '실시간 채팅' })).toBeInTheDocument();
  });
});

describe('LiveScreen — 실시간 통계', () => {
  it('지표 스택과 범례·시간축을 그린다', () => {
    renderLive();

    expect(screen.getByText('최고 시청자')).toBeInTheDocument();
    expect(screen.getByText('2,310')).toBeInTheDocument();
    expect(screen.getByText('12,480')).toBeInTheDocument();
    expect(screen.getByText('분당 평균 채팅')).toBeInTheDocument();
    expect(screen.getByText('저스트 채팅 29분')).toBeInTheDocument();
    expect(screen.getByText('19:12')).toBeInTheDocument();
  });

  it('타임라인이 채팅량·시청자·하이라이트·후원을 한 그림으로 알린다', () => {
    renderLive();

    expect(
      screen.getByRole('img', {
        name: '방송 타임라인 — 채팅량과 시청자 추이, 하이라이트 4곳, 후원 2회',
      }),
    ).toBeInTheDocument();
  });

  it('하이라이트 내역은 총계 앞에 선다 — 총계가 다른 지표와 같은 오른쪽 끝에 맞는다', () => {
    renderLive();

    const note = screen.getByText('자동 6 · 수동 2');
    const value = note.parentElement;
    expect(value?.textContent).toBe('자동 6 · 수동 28');
    expect(note.nextElementSibling).toHaveTextContent('8');
  });

  it('하이라이트 지표는 카드 목록에서 센다 — 마킹하면 필터와 같이 움직인다', () => {
    vi.useFakeTimers();
    renderLive();

    expect(screen.getByText('자동 6 · 수동 2')).toBeInTheDocument();

    fireEvent.keyDown(document, { key: 'F8' });
    act(() => {
      vi.advanceTimersByTime(CARD_CREATE_MS);
    });

    expect(screen.getByText('자동 6 · 수동 3')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '수동 3' })).toBeInTheDocument();
  });
});

describe('LiveScreen — 접근성', () => {
  it('접근성 위반이 없다', async () => {
    const { container } = renderLive();
    // usePlayerSimulation의 1초 tick이 axe 실행(1초 이상) 중에 발화한다 — act로 감싸 흡수
    await act(async () => {
      expect(await axe(container)).toHaveNoViolations();
    });
  });
});
