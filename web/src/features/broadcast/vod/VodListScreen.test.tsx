import { act } from 'react';
import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/testProviders';
import { VodListScreen } from './VodListScreen';

function rows() {
  return within(screen.getByRole('list', { name: '지난 방송 목록' })).getAllByRole('listitem');
}

function rowAt(index: number) {
  return rows()[index]!;
}

describe('VodListScreen — 헤더', () => {
  it('제목과 방송 수를 보여준다', () => {
    renderWithProviders(<VodListScreen />);

    expect(screen.getByRole('heading', { name: '지난 방송' })).toBeInTheDocument();
    expect(screen.getByText('12')).toBeInTheDocument();
    expect(rows()).toHaveLength(12);
  });
});

describe('VodListScreen — 행 상태', () => {
  it('방금 끝난 방송은 준비 중이라 열 수 없다', () => {
    renderWithProviders(<VodListScreen />);
    const row = rowAt(0);

    expect(
      within(row).getByText('방금 종료 · VOD 준비 중 · 준비되면 알려드릴게요'),
    ).toBeInTheDocument();
    expect(within(row).getByRole('status')).toHaveTextContent('VOD 준비 중');
    expect(within(row).getByText('준비 중')).toBeInTheDocument();
    // 열 VOD가 없으므로 링크를 만들지 않는다
    expect(within(row).queryByRole('link')).toBeNull();
  });

  it('받는 중인 행은 썸네일이 진행률로 덮이고 취소만 낸다', () => {
    renderWithProviders(<VodListScreen />);
    const row = rowAt(1);

    expect(within(row).getByText('풀 VOD 받는 중')).toBeInTheDocument();
    expect(within(row).getByText('46%')).toBeInTheDocument();
    expect(within(row).getByRole('button', { name: '다운로드 취소' })).toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: '풀 버전 다운로드' })).toBeNull();
  });

  it('다 받아 둔 행은 「받기 완료」를 낸다', () => {
    renderWithProviders(<VodListScreen />);
    const row = rowAt(4);

    expect(within(row).getByRole('button', { name: /받기 완료/ })).toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: '풀 버전 다운로드' })).toBeNull();
  });

  it('보통 행은 방송일·카드 수·D-day와 길이를 보여준다', () => {
    renderWithProviders(<VodListScreen />);
    const row = rowAt(2);

    expect(within(row).getByRole('link', { name: /합방 특집 — 4인 내전/ })).toHaveAccessibleName(
      /길이 4:12:08/,
    );
    expect(within(row).getByText(/카드 11개$/)).toBeInTheDocument();
    expect(within(row).getByText('D-57')).toBeInTheDocument();
  });

  it('만료 임박 행은 남은 날과 함께 무엇을 잃는지 말한다', () => {
    renderWithProviders(<VodListScreen />);
    const row = rowAt(11);

    expect(within(row).getByText('D-3')).toBeInTheDocument();
    // 색만으로 급함을 전하지 않는다 — 문장이 함께 선다
    expect(within(row).getByText(/저장하지 않은 카드 9개가 함께 삭제됩니다/)).toBeInTheDocument();
  });

  it('시작 시각이 비어 와도 종료 시각으로 방송일을 적는다', () => {
    renderWithProviders(<VodListScreen />);

    expect(screen.queryByText(/방송일 미상/)).toBeNull();
  });
});

describe('VodListScreen — 이동', () => {
  it('행이 VOD 뷰어로 간다', () => {
    renderWithProviders(<VodListScreen />);

    expect(screen.getByRole('link', { name: /합방 특집 — 4인 내전/ })).toHaveAttribute(
      'href',
      '/broadcast/vod/stream-2606',
    );
  });

  it('다운로드 버튼은 받을 수 있는 행에만 선다', () => {
    renderWithProviders(<VodListScreen />);

    // 준비 중(받을 VOD가 없다)·받는 중(이미 받고 있다)·받기 완료를 뺀 아홉 행
    expect(screen.getAllByRole('button', { name: '풀 버전 다운로드' })).toHaveLength(9);
    expect(within(rowAt(0)).queryByRole('button', { name: '풀 버전 다운로드' })).toBeNull();
    expect(within(rowAt(1)).queryByRole('button', { name: '풀 버전 다운로드' })).toBeNull();
  });
});

describe('VodListScreen — 풀 VOD 내려받기', () => {
  it('다운로드 버튼이 화질 선택을 펴고 예상 크기를 함께 보여준다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<VodListScreen />);

    await user.click(within(rowAt(2)).getByRole('button', { name: '풀 버전 다운로드' }));

    const panel = screen.getByRole('dialog', { name: '풀 버전 다운로드' });
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
    renderWithProviders(<VodListScreen />);

    await user.click(within(rowAt(2)).getByRole('button', { name: '풀 버전 다운로드' }));
    const panel = screen.getByRole('dialog', { name: '풀 버전 다운로드' });
    await user.click(within(panel).getByRole('radio', { name: /720p30/ }));

    expect(within(panel).getByRole('radio', { name: /720p30/ })).toBeChecked();
  });

  // 받는 일 자체는 아직 아무것도 안 한다 — 진행 중인 척하고 멈춰 있느니 준비 중이라고 말한다
  it('「다운로드 시작」은 준비 중이라고 알리고 행을 그대로 둔다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<VodListScreen />);

    await user.click(within(rowAt(2)).getByRole('button', { name: '풀 버전 다운로드' }));
    await user.click(screen.getByRole('button', { name: '다운로드 시작' }));

    expect(await screen.findByText('준비 중인 기능이에요')).toBeInTheDocument();
    expect(within(rowAt(2)).getByRole('button', { name: '풀 버전 다운로드' })).toBeInTheDocument();
    expect(within(rowAt(2)).queryByText('풀 VOD 받는 중')).toBeNull();
  });

  it('받는 중인 행을 취소하면 다시 받을 수 있는 자리로 돌아간다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<VodListScreen />);

    await user.click(within(rowAt(1)).getByRole('button', { name: '다운로드 취소' }));

    expect(within(rowAt(1)).queryByText('풀 VOD 받는 중')).toBeNull();
    expect(within(rowAt(1)).getByRole('button', { name: '풀 버전 다운로드' })).toBeInTheDocument();
  });

  it('「받기 완료」를 누르면 다시 받을 수 있는 자리로 돌아간다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<VodListScreen />);

    await user.click(within(rowAt(4)).getByRole('button', { name: /받기 완료/ }));

    expect(within(rowAt(4)).getByRole('button', { name: '풀 버전 다운로드' })).toBeInTheDocument();
  });
});

describe('VodListScreen — 기간 필터', () => {
  it('7일·30일이 목록을 좁힌다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<VodListScreen />);

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
    renderWithProviders(<VodListScreen />);

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
    renderWithProviders(<VodListScreen />);

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
    expect(within(row).queryByRole('button', { name: '풀 버전 다운로드' })).toBeNull();
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
    const { container } = renderWithProviders(<VodListScreen />);
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
