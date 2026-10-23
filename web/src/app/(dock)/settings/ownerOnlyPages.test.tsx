import { screen } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ChannelRelationContext } from '@/features/channel/ChannelScope';
import { useAuthStore } from '@/stores/auth';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';
import ChannelSettingsPage from './channels/page';
import EditorSettingsPage from './editors/page';
import PluginSettingsPage from './plugin/page';

// 주소로 바로 들어와도 막히는가(POK-390, 감사 3회차 지적 2). 페이지는 JSX를 돌려주는 평범한 함수라 그대로 렌더한다.
// 막힌 페이지는 화면을 마운트하지 않으므로 그 화면의 창구는 불리지 않는다: 부르면 아래 stub이 빨간불로 알린다.

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => '/settings',
  useSearchParams: () => new URLSearchParams(''),
}));

const PAGES: Array<[string, () => ReactElement]> = [
  ['채널 연동', ChannelSettingsPage],
  ['편집자 관리', EditorSettingsPage],
  ['플러그인', PluginSettingsPage],
];

beforeEach(() => {
  useAuthStore.setState({ accessToken: 'access-1', refreshToken: 'refresh-1', hydrated: true });
  stubFetch((url) => {
    if (url === '/api/auth/me')
      return jsonResponse(200, {
        id: 7,
        email: 'me@example.com',
        name: '나',
        profileImageUrl: null,
      });
    throw new Error(`막힌 페이지가 창구를 불렀다: ${url}`);
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('남의 채널에서 막는 설정 페이지', () => {
  it.each(PAGES)('%s 페이지는 남의 채널이면 안내만 그린다', (_name, Page) => {
    renderWithProviders(
      <ChannelRelationContext.Provider value="EDITOR">
        <Page />
      </ChannelRelationContext.Provider>,
    );

    expect(screen.getByText('이 채널에서는 열 수 없어요')).toBeInTheDocument();
  });
});
