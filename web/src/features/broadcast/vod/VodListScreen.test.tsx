import { act } from 'react';
import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';
import { VodListScreen } from './VodListScreen';
import { VOD_LIST_FIXTURE } from './vodListFixture';

function rows() {
  return within(screen.getByRole('list', { name: '지난 방송 목록' })).getAllByRole('listitem');
}

function rowAt(index: number) {
  return rows()[index]!;
}

describe('VodListScreen — 헤더', () => {
  it('제목과 방송 수를 보여준다', () => {
    renderWithProviders(<VodListScreen {...VOD_LIST_FIXTURE} />);

    expect(screen.getByRole('heading', { name: '지난 방송' })).toBeInTheDocument();
    expect(screen.getByText('12')).toBeInTheDocument();
    expect(rows()).toHaveLength(12);
  });
});

describe('VodListScreen — 행 상태', () => {
  it('방금 끝난 방송은 준비 중이라 열 수 없다', () => {
    renderWithProviders(<VodListScreen {...VOD_LIST_FIXTURE} />);
    const row = rowAt(0);

    expect(
      within(row).getByText('방금 종료 · VOD 준비 중 · 준비되면 알려드릴게요'),
    ).toBeInTheDocument();
    expect(within(row).getByRole('status')).toHaveTextContent('VOD 준비 중');
    expect(within(row).getByText('준비 중')).toBeInTheDocument();
    // 열 VOD가 없으므로 링크를 만들지 않는다
    expect(within(row).queryByRole('link')).toBeNull();
  });

  it('끝난 지 오래됐고 녹화가 없다고 확인된 방송은 「방금 종료」가 아니라 녹화 없음이고, 카드·채팅을 보러 열 수 있다(POK-254)', () => {
    const endedAt = new Date(VOD_LIST_FIXTURE.now.getTime() - 2 * 60 * 60 * 1000).toISOString();
    renderWithProviders(
      <VodListScreen
        broadcasts={[
          {
            streamId: 'old-1',
            status: 'ended',
            relation: 'OWNER',
            startedAt: endedAt,
            endedAt,
            vodExpiresAt: null,
          },
        ]}
        visuals={{
          'old-1': {
            title: '녹화 없는 방송',
            durationSec: 60,
            cardCount: 2,
            recordingMissing: true,
          },
        }}
        downloads={{}}
        now={VOD_LIST_FIXTURE.now}
      />,
    );
    const row = rowAt(0);

    expect(within(row).queryByText(/방금 종료/)).toBeNull();
    expect(within(row).getByText(/녹화 영상이 없어요/)).toBeInTheDocument();
    expect(within(row).getByRole('link', { name: /녹화 없는 방송/ })).toHaveAttribute(
      'href',
      '/broadcast/vod/old-1',
    );
    expect(within(row).queryByRole('button', { name: /^풀 버전 다운로드/ })).toBeNull();
  });

  it('받는 중인 행은 썸네일이 진행률로 덮이고 취소만 낸다', () => {
    renderWithProviders(<VodListScreen {...VOD_LIST_FIXTURE} />);
    const row = rowAt(1);

    expect(within(row).getByText('풀 VOD 받는 중')).toBeInTheDocument();
    expect(within(row).getByText('46%')).toBeInTheDocument();
    expect(within(row).getByRole('button', { name: /^다운로드 취소/ })).toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: /^풀 버전 다운로드/ })).toBeNull();
  });

  it('다 받아 둔 행은 「받기 완료」를 낸다', () => {
    renderWithProviders(<VodListScreen {...VOD_LIST_FIXTURE} />);
    const row = rowAt(4);

    expect(within(row).getByRole('button', { name: /받기 완료/ })).toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: /^풀 버전 다운로드/ })).toBeNull();
  });

  it('보통 행은 방송일·카드 수·D-day와 길이를 보여준다', () => {
    renderWithProviders(<VodListScreen {...VOD_LIST_FIXTURE} />);
    const row = rowAt(2);

    expect(within(row).getByRole('link', { name: /합방 특집 — 4인 내전/ })).toHaveAccessibleName(
      /길이 4:12:08/,
    );
    expect(within(row).getByText(/카드 11개$/)).toBeInTheDocument();
    expect(within(row).getByText('D-57')).toBeInTheDocument();
  });

  it('만료 임박 행은 남은 날과 함께 무엇을 잃는지 말한다', () => {
    renderWithProviders(<VodListScreen {...VOD_LIST_FIXTURE} />);
    const row = rowAt(11);

    expect(within(row).getByText('D-3')).toBeInTheDocument();
    // 색만으로 급함을 전하지 않는다 — 문장이 함께 선다
    expect(within(row).getByText(/저장하지 않은 카드 9개가 함께 삭제됩니다/)).toBeInTheDocument();
  });

  it('시작 시각이 비어 와도 종료 시각으로 방송일을 적는다', () => {
    renderWithProviders(<VodListScreen {...VOD_LIST_FIXTURE} />);

    expect(screen.queryByText(/방송일 미상/)).toBeNull();
  });
});

describe('VodListScreen — 이동', () => {
  it('행이 VOD 뷰어로 간다', () => {
    renderWithProviders(<VodListScreen {...VOD_LIST_FIXTURE} />);

    expect(screen.getByRole('link', { name: /합방 특집 — 4인 내전/ })).toHaveAttribute(
      'href',
      '/broadcast/vod/stream-2606',
    );
  });

  it('다운로드 버튼은 받을 수 있는 행에만 선다', () => {
    renderWithProviders(<VodListScreen {...VOD_LIST_FIXTURE} />);

    // 준비 중(받을 VOD가 없다)·받는 중(이미 받고 있다)·받기 완료를 뺀 아홉 행
    expect(screen.getAllByRole('button', { name: /^풀 버전 다운로드/ })).toHaveLength(9);
    expect(within(rowAt(0)).queryByRole('button', { name: /^풀 버전 다운로드/ })).toBeNull();
    expect(within(rowAt(1)).queryByRole('button', { name: /^풀 버전 다운로드/ })).toBeNull();
  });

  // 버튼 목록으로 훑는 사람에게는 같은 이름 아홉 개가 구분이 안 된다
  it('행마다 다운로드 버튼 이름이 다르다', () => {
    renderWithProviders(<VodListScreen {...VOD_LIST_FIXTURE} />);

    const names = screen
      .getAllByRole('button', { name: /^풀 버전 다운로드/ })
      .map((button) => button.getAttribute('aria-label'));
    expect(new Set(names).size).toBe(names.length);
    expect(names).toContain('풀 버전 다운로드 · 합방 특집 — 4인 내전');
  });
});

describe('VodListScreen — 풀 VOD 내려받기', () => {
  it('다운로드 버튼이 화질 선택을 펴고 예상 크기를 함께 보여준다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<VodListScreen {...VOD_LIST_FIXTURE} />);

    await user.click(within(rowAt(2)).getByRole('button', { name: /^풀 버전 다운로드/ }));

    const panel = screen.getByRole('dialog', { name: /^풀 버전 다운로드/ });
    expect(within(panel).getByText('합방 특집 — 4인 내전 · 4:12:08')).toBeInTheDocument();
    // 크기는 길이에서 계산한다 — 행마다 같은 값이 아니다
    expect(within(panel).getByText('6.2GB')).toBeInTheDocument();
    expect(within(panel).getByRole('radio', { name: /원본 화질/ })).toBeChecked();
    expect(
      within(panel).getByText(/보관 만료\(D-57\) 후에는 다운로드할 수 없어요/),
    ).toBeInTheDocument();
  });

  it('화질을 바꿔 고를 수 있다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<VodListScreen {...VOD_LIST_FIXTURE} />);

    await user.click(within(rowAt(2)).getByRole('button', { name: /^풀 버전 다운로드/ }));
    const panel = screen.getByRole('dialog', { name: /^풀 버전 다운로드/ });
    await user.click(within(panel).getByRole('radio', { name: /720p30/ }));

    expect(within(panel).getByRole('radio', { name: /720p30/ })).toBeChecked();
  });

  // 받는 일 자체는 아직 아무것도 안 한다 — 진행 중인 척하고 멈춰 있느니 준비 중이라고 말한다
  it('「다운로드 시작」은 준비 중이라고 알리고 행을 그대로 둔다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<VodListScreen {...VOD_LIST_FIXTURE} />);

    await user.click(within(rowAt(2)).getByRole('button', { name: /^풀 버전 다운로드/ }));
    await user.click(screen.getByRole('button', { name: '다운로드 시작' }));

    expect(await screen.findByText('준비 중인 기능이에요')).toBeInTheDocument();
    expect(within(rowAt(2)).getByRole('button', { name: /^풀 버전 다운로드/ })).toBeInTheDocument();
    expect(within(rowAt(2)).queryByText('풀 VOD 받는 중')).toBeNull();
  });

  it('받는 중인 행을 취소하면 다시 받을 수 있는 자리로 돌아간다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<VodListScreen {...VOD_LIST_FIXTURE} />);

    await user.click(within(rowAt(1)).getByRole('button', { name: /^다운로드 취소/ }));

    expect(within(rowAt(1)).queryByText('풀 VOD 받는 중')).toBeNull();
    expect(within(rowAt(1)).getByRole('button', { name: /^풀 버전 다운로드/ })).toBeInTheDocument();
  });

  it('「받기 완료」를 누르면 다시 받을 수 있는 자리로 돌아간다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<VodListScreen {...VOD_LIST_FIXTURE} />);

    await user.click(within(rowAt(4)).getByRole('button', { name: /받기 완료/ }));

    expect(within(rowAt(4)).getByRole('button', { name: /^풀 버전 다운로드/ })).toBeInTheDocument();
  });
});

describe('VodListScreen — 기간 필터', () => {
  it('7일·30일이 목록을 좁힌다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<VodListScreen {...VOD_LIST_FIXTURE} />);

    await user.click(screen.getByRole('button', { name: '7일' }));
    expect(screen.getByRole('button', { name: '7일' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: '전체' })).toHaveAttribute('aria-pressed', 'false');
    expect(rows()).toHaveLength(4);

    await user.click(screen.getByRole('button', { name: '30일' }));
    expect(rows()).toHaveLength(9);

    await user.click(screen.getByRole('button', { name: '전체' }));
    expect(rows()).toHaveLength(12);
  });

  it('기간 지정은 날짜 입력을 펴고 그 범위만 남긴다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<VodListScreen {...VOD_LIST_FIXTURE} />);

    expect(screen.queryByLabelText('시작일')).toBeNull();
    await user.click(screen.getByRole('button', { name: '기간 지정' }));

    // 아직 아무것도 안 채운 동안에는 목록이 그대로다
    expect(rows()).toHaveLength(12);

    fireEvent.change(screen.getByLabelText('시작일'), { target: { value: '2026-08-18' } });
    fireEvent.change(screen.getByLabelText('종료일'), { target: { value: '2026-08-24' } });
    expect(rows()).toHaveLength(4);
  });

  it('그 기간에 방송이 없으면 없다고 말한다 — 「아직 없다」와는 다른 말이다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<VodListScreen {...VOD_LIST_FIXTURE} />);

    await user.click(screen.getByRole('button', { name: '기간 지정' }));
    fireEvent.change(screen.getByLabelText('시작일'), { target: { value: '2026-01-01' } });
    fireEvent.change(screen.getByLabelText('종료일'), { target: { value: '2026-01-31' } });

    expect(screen.getByText('이 기간에 끝난 방송이 없어요.')).toBeInTheDocument();
    expect(screen.queryByRole('list', { name: '지난 방송 목록' })).toBeNull();
    expect(screen.queryByText('아직 지난 방송이 없어요')).toBeNull();
  });
});

describe('VodListScreen — 보관 만료', () => {
  // 기한이 지난 방송도 목록에 남는다(계약). 줄은 남되 열 것이 없다 —
  // 「보관 만료」를 달고서 뷰어 링크를 함께 내면 한 줄이 두 말을 한다.
  const expired = [
    {
      streamId: 'stream-old',
      status: 'vod_ready' as const,
      relation: 'OWNER' as const,
      startedAt: '2026-05-01T19:00:00+09:00',
      endedAt: '2026-05-01T23:00:00+09:00',
      vodExpiresAt: '2026-06-30T23:00:00+09:00',
    },
  ];
  const visuals = {
    'stream-old': { title: '지워진 방송', durationSec: 14400, cardCount: 2 },
  };

  it('만료된 행은 링크도 다운로드도 내지 않는다', () => {
    renderWithProviders(<VodListScreen broadcasts={expired} visuals={visuals} />);
    const row = rowAt(0);

    expect(within(row).getByText('보관 만료')).toBeInTheDocument();
    expect(within(row).getByText('지워진 방송')).toBeInTheDocument();
    expect(within(row).queryByRole('link')).toBeNull();
    expect(within(row).queryByRole('button', { name: /^풀 버전 다운로드/ })).toBeNull();
  });

  // 썸네일 위 길이는 aria-hidden이라, 링크가 없는 행에서도 길이를 따로 남기지 않으면
  // 듣는 쪽만 그 정보를 통째로 잃는다
  it('링크가 없어도 길이는 듣는 쪽에 남는다', () => {
    renderWithProviders(<VodListScreen broadcasts={expired} visuals={visuals} />);

    expect(within(rowAt(0)).getByText(/길이 4:00:00/)).toBeInTheDocument();
  });
});

describe('VodListScreen — 빈 상태', () => {
  it('방송이 하나도 없으면 60일 보관을 안내하고 필터를 감춘다', () => {
    renderWithProviders(<VodListScreen broadcasts={[]} />);

    expect(screen.getByText('아직 지난 방송이 없어요')).toBeInTheDocument();
    expect(screen.getByText(/VOD는 60일 동안 보관되고/)).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: '기간 필터' })).toBeNull();
  });
});

describe('VodListScreen — 접근성', () => {
  it('목록에 위반이 없다', async () => {
    const { container } = renderWithProviders(<VodListScreen {...VOD_LIST_FIXTURE} />);
    // axe 실행 중 Next Link의 비동기 상태 갱신이 발화한다 — act로 감싸 경고 없이 흡수
    await act(async () => {
      expect(await axe(container)).toHaveNoViolations();
    });
  });

  it('빈 상태에도 위반이 없다', async () => {
    const { container } = renderWithProviders(<VodListScreen broadcasts={[]} />);
    await act(async () => {
      expect(await axe(container)).toHaveNoViolations();
    });
  });
});

describe('VodListScreen — 서버 목록', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('처음 불러오는 동안은 「없다」가 아니라 「불러오는 중」이다: 비어 있는 목록은 아직 모르는 것이다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let answer: (response: Response) => void = () => undefined;
    stubFetch((url) =>
      url.startsWith('/api/clip/broadcasts?')
        ? new Promise<Response>((resolve) => {
            answer = resolve;
          })
        : jsonResponse(200, { cards: [], nextCursor: null }),
    );

    renderWithProviders(<VodListScreen />);
    expect(screen.getByText('불러오는 중…')).toBeInTheDocument();
    expect(screen.queryByText('아직 지난 방송이 없어요')).toBeNull();

    await act(async () => {
      answer(jsonResponse(200, { broadcasts: [], nextCursor: null }));
      await vi.advanceTimersByTimeAsync(100);
    });
    // 서버가 「없다」고 답한 뒤에야 빈 상태다
    expect(screen.getByText('아직 지난 방송이 없어요')).toBeInTheDocument();
    expect(screen.queryByText('불러오는 중…')).toBeNull();
  });

  it('첫 조회가 실패하면 다음 주기까지 「불러오는 중」이다: 못 읽은 것을 「없다」고 하지 않는다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    stubFetch(() => jsonResponse(503, { error: 'unavailable' }));

    renderWithProviders(<VodListScreen />);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });
    expect(screen.getByText('불러오는 중…')).toBeInTheDocument();
    expect(screen.queryByText('아직 지난 방송이 없어요')).toBeNull();
  });

  it('카드 수는 낡으면 다시 센다: 끝난 지 한 시간 안이면 주기마다, 그 밖은 10분마다(POK-254)', async () => {
    // 끝난 뒤에도 카드는 늦게 뜨거나 숨겨진다. 처음 잰 수를 영영 쓰면 목록이 실제와 갈린다
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const at = (ms: number) => new Date(Date.now() + ms).toISOString();
    const row = (streamId: string, endedAt: string) => ({
      streamId,
      status: 'vod_ready',
      relation: 'OWNER',
      startedAt: endedAt,
      endedAt,
      vodExpiresAt: null,
      title: streamId,
    });
    const rows = [row('fresh', at(-10 * 60_000)), row('old', at(-3 * 86_400_000))];
    let cards = [{ id: 1 }];
    const spy = stubFetch((url) =>
      url.startsWith('/api/clip/broadcasts?')
        ? jsonResponse(200, { broadcasts: rows, nextCursor: null })
        : jsonResponse(200, { cards, nextCursor: null }),
    );
    const calls = (id: string) =>
      spy.mock.calls.filter(([url]) => String(url).includes(`/${id}/jump-cards`)).length;
    const countOf = (title: string) =>
      within(screen.getByText(title).closest('li')!).getByText(/^카드 \d+개$/).textContent;

    renderWithProviders(<VodListScreen />);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });
    expect([calls('fresh'), calls('old')]).toEqual([1, 1]);

    cards = [{ id: 1 }, { id: 2 }];
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });
    expect([calls('fresh'), calls('old')]).toEqual([2, 1]);
    expect(countOf('fresh')).toBe('카드 2개');
    expect(countOf('old')).toBe('카드 1개');

    await act(async () => {
      await vi.advanceTimersByTimeAsync(9 * 60_000);
    });
    expect(calls('old')).toBe(2);
    expect(countOf('old')).toBe('카드 2개');
  });

  it('오래된 방송의 카드 수는 한 주기에 20개까지만 다시 센다: 처음 함께 센 것이 10분마다 한꺼번에 몰리지 않는다(리뷰 1라운드)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const endedAt = new Date(Date.now() - 3 * 86_400_000).toISOString();
    const rows = Array.from({ length: 25 }, (_, i) => ({
      streamId: `old-${i}`,
      status: 'vod_ready',
      relation: 'OWNER',
      startedAt: endedAt,
      endedAt,
      vodExpiresAt: null,
    }));
    const spy = stubFetch((url) =>
      url.startsWith('/api/clip/broadcasts?')
        ? jsonResponse(200, { broadcasts: rows, nextCursor: null })
        : jsonResponse(200, { cards: [], nextCursor: null }),
    );
    const cardCalls = () =>
      spy.mock.calls.filter(([url]) => String(url).includes('/jump-cards')).length;

    renderWithProviders(<VodListScreen />);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });
    // 처음 보는 방송은 다 센다
    expect(cardCalls()).toBe(25);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(10 * 60_000);
    });
    expect(cardCalls()).toBe(45);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });
    expect(cardCalls()).toBe(50);
  });

  it('오래 끝난 방송의 카드 수는 10분 안에 다시 세지 않는다 — 다시 읽을 때 전체 이력의 카드를 또 부르지 않는다', async () => {
    // 주기마다 방송마다 카드 목록을 부르면 계정이 오래될수록 요청이 끝없이 는다(PR #200 codex)
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const row = (streamId: string) => ({
      streamId,
      status: 'ended',
      relation: 'OWNER',
      startedAt: '2026-09-20T10:00:00Z',
      endedAt: '2026-09-20T12:00:00Z',
      vodExpiresAt: null,
    });
    const spy = stubFetch((url) =>
      url.startsWith('/api/clip/broadcasts?')
        ? jsonResponse(200, { broadcasts: [row('a'), row('b')], nextCursor: null })
        : jsonResponse(200, { cards: [{ id: 1 }], nextCursor: null }),
    );
    const cardCalls = () =>
      spy.mock.calls.filter(([url]) => String(url).includes('/jump-cards')).length;

    renderWithProviders(<VodListScreen />);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });
    expect(cardCalls()).toBe(2);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });
    // 목록은 다시 읽었지만 카드 수는 기억한 값을 쓴다
    expect(
      spy.mock.calls.filter(([url]) => String(url).startsWith('/api/clip/broadcasts?')).length,
    ).toBeGreaterThan(1);
    expect(cardCalls()).toBe(2);
  });

  it('끝난 지 한 시간이 지났고 재생 서버에 녹화가 없으면 녹화 없음이다. 재생 서버가 없으면 「방금」만 뗀다(POK-254)', async () => {
    const endedAt = new Date(Date.now() - 2 * 3_600_000).toISOString();
    const list = () =>
      jsonResponse(200, {
        broadcasts: [
          {
            streamId: 'old-stream',
            status: 'ended',
            relation: 'OWNER',
            startedAt: endedAt,
            endedAt,
            vodExpiresAt: null,
          },
        ],
        nextCursor: null,
      });

    vi.stubEnv('NEXT_PUBLIC_MEDIA_PLAYBACK_BASE_URL', 'http://pb.test');
    vi.resetModules();
    const withPlayback = await import('./VodListScreen');
    const providers = await import('@/test/testProviders');
    stubFetch((url) => {
      if (url.startsWith('http://pb.test/list')) return jsonResponse(200, []);
      if (url.startsWith('/api/clip/broadcasts?')) return list();
      return jsonResponse(200, { cards: [], nextCursor: null });
    });
    const first = providers.renderWithProviders(<withPlayback.VodListScreen />);
    expect(await screen.findByText(/녹화 영상이 없어요/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /old-stream/ })).toBeInTheDocument();
    first.unmount();

    // 재생 서버가 오류로 답하면 묻지 못한 것이다: 「녹화 영상이 없어요」라고 단정하지 않는다(리뷰 1라운드)
    vi.resetModules();
    const failing = await import('./VodListScreen');
    const providersF = await import('@/test/testProviders');
    stubFetch((url) => {
      if (url.startsWith('http://pb.test/list')) return jsonResponse(500, { error: 'boom' });
      if (url.startsWith('/api/clip/broadcasts?')) return list();
      return jsonResponse(200, { cards: [], nextCursor: null });
    });
    const second = providersF.renderWithProviders(<failing.VodListScreen />);
    expect(await screen.findByText(/· VOD 준비 중$/)).toBeInTheDocument();
    expect(screen.queryByText(/녹화 영상이 없어요/)).toBeNull();
    second.unmount();

    vi.stubEnv('NEXT_PUBLIC_MEDIA_PLAYBACK_BASE_URL', '');
    vi.resetModules();
    const noPlayback = await import('./VodListScreen');
    const providers2 = await import('@/test/testProviders');
    stubFetch((url) =>
      url.startsWith('/api/clip/broadcasts?')
        ? list()
        : jsonResponse(200, { cards: [], nextCursor: null }),
    );
    providers2.renderWithProviders(<noPlayback.VodListScreen />);
    expect(await screen.findByText(/· VOD 준비 중$/)).toBeInTheDocument();
    expect(screen.queryByText(/방금 종료/)).toBeNull();
    expect(screen.queryByText(/녹화 영상이 없어요/)).toBeNull();
    expect(screen.queryByRole('link', { name: /old-stream/ })).toBeNull();
    vi.unstubAllEnvs();
  });

  it('녹화 없음은 굳히지 않는다 — 방송 직후 늦게 생긴 녹화를 다음 주기에 알아본다', async () => {
    // 한 번 없던 녹화를 영구히 기억하면 녹화가 생겨도 이 탭은 계속 준비 중이다(PR #200 codex)
    vi.stubEnv('NEXT_PUBLIC_MEDIA_PLAYBACK_BASE_URL', 'http://pb.test');
    vi.resetModules();
    const { VodListScreen: Fresh } = await import('./VodListScreen');
    const { renderWithProviders: renderFresh } = await import('@/test/testProviders');
    vi.useFakeTimers({ shouldAdvanceTime: true });
    // 방금 끝난 방송이어야 한다: 한 시간이 지났으면 「녹화 없음」으로 접혀 그것대로 열린다(POK-254)
    const endedMs = Date.now() - 10 * 60 * 1000;
    const startedAt = new Date(endedMs - 3_600_000).toISOString();
    const endedAt = new Date(endedMs).toISOString();
    let recorded = false;
    stubFetch((url) => {
      if (url.startsWith('http://pb.test/list'))
        return jsonResponse(200, recorded ? [{ start: startedAt, duration: 3600 }] : []);
      if (url.startsWith('/api/clip/broadcasts?'))
        return jsonResponse(200, {
          broadcasts: [
            {
              streamId: 'rec-stream',
              status: 'ended',
              relation: 'OWNER',
              startedAt,
              endedAt,
              vodExpiresAt: null,
            },
          ],
          nextCursor: null,
        });
      return jsonResponse(200, { cards: [], nextCursor: null });
    });

    renderFresh(<Fresh />);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });
    expect(screen.queryByRole('link', { name: /rec-stream/ })).toBeNull();
    expect(screen.getByText('방금 종료 · VOD 준비 중 · 준비되면 알려드릴게요')).toBeInTheDocument();

    recorded = true;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });
    expect(screen.getByRole('link', { name: /rec-stream/ })).toBeInTheDocument();
    // 「녹화 없음」도 열리므로 다시 보기 가능(받기 단추)으로 가른다
    expect(screen.getByRole('button', { name: /^풀 버전 다운로드/ })).toBeInTheDocument();
    vi.unstubAllEnvs();
  });
});
