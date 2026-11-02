import { act, screen } from '@testing-library/react';
import { axe } from 'jest-axe';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '@/stores/auth';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';
import { AudioTrackNamesCard } from './AudioTrackNamesCard';

const TRACKS_URL = '/api/auth/me/audio-tracks';

/** 트랙 이름 조회를 붙잡아 두고, 케이스가 고른 때에 답한다 — 「첫 로딩 중」을 만든다. */
function holdTrackLabels() {
  let answer: (res: Response) => void = () => {};
  stubFetch((url) => {
    if (url === TRACKS_URL) return new Promise<Response>((resolve) => (answer = resolve));
    return jsonResponse(404);
  });
  return (res: Response) => answer(res);
}

beforeEach(() => {
  useAuthStore.setState({ accessToken: 'access-1', refreshToken: 'refresh-1', hydrated: true });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('AudioTrackNamesCard — 첫 로딩(POK-446)', () => {
  it('응답 전엔 글자 한 줄 대신 입력 여섯 칸 자리의 스켈레톤을 두고, 낭독기엔 status 한 줄만 들린다', async () => {
    const answer = holdTrackLabels();
    const { container } = renderWithProviders(<AudioTrackNamesCard />);

    expect(screen.getAllByRole('status')).toHaveLength(1);
    expect(screen.getByRole('status')).toHaveTextContent('트랙 이름을 불러오는 중');
    expect(screen.queryByText('불러오는 중…')).toBeNull();
    // 입력·저장 버튼은 이름이 온 뒤에만 선다 — 빈 값을 저장하면 서버의 이름을 지운다
    expect(screen.queryAllByRole('textbox')).toHaveLength(0);
    expect(screen.queryByRole('button', { name: '이름 저장' })).toBeNull();
    await act(async () => {
      expect(await axe(container)).toHaveNoViolations();
    });

    await act(async () => {
      answer(jsonResponse(200, { labels: ['전체', null, '디스코드', null, null, null] }));
    });

    expect(await screen.findByDisplayValue('디스코드')).toBeInTheDocument();
    expect(screen.getAllByRole('textbox')).toHaveLength(6);
    expect(screen.getByRole('button', { name: '이름 저장' })).toBeInTheDocument();
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('조회가 실패하면 스켈레톤을 걷고 못 불러왔다고 말한다 — 실패를 로딩으로 가리지 않는다', async () => {
    const answer = holdTrackLabels();
    renderWithProviders(<AudioTrackNamesCard />);

    await act(async () => {
      answer(jsonResponse(500, { reason: '서버 오류' }));
    });

    expect(await screen.findByRole('alert')).toHaveTextContent('트랙 이름을 불러오지 못했어요');
    expect(screen.queryByRole('status')).toBeNull();
  });
});
