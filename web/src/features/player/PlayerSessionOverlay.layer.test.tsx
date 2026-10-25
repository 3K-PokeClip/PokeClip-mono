import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PlayerSessionOverlay } from './PlayerSessionOverlay';
import type { SessionView } from './rewindSwitch';

// 끝남 대기 덮개가 플레이어 조작부(시크바 · 재생 · 음량 · 전체 화면)의 포인터 입력을 먹지 않는다(cc H3).
// 덮개 판은 포인터를 아래로 흘리고, 덮개의 버튼 묶음만 받는다. jsdom 은 쌓임 순서를 계산하지 않아 CSS 값으로 단언한다 —
// 화면 확인은 로컬 통합 때 한 번 한다

const VIEW: SessionView = {
  cover: 'checking',
  actions: ['record'],
  banner: null,
  seekHidden: false,
  liveHidden: false,
  liveChecking: false,
  recordEmphasis: false,
  vodReady: false,
};

describe('PlayerSessionOverlay 층', () => {
  it('덮개 판은 pointer-events:none · 버튼 묶음은 auto 다', () => {
    render(<PlayerSessionOverlay view={VIEW} onAction={vi.fn()} />);
    const button = screen.getByRole('button', { name: '녹화 보기' });
    const actions = button.parentElement!;
    const cover = actions.parentElement!;
    expect(getComputedStyle(cover).pointerEvents).toBe('none');
    expect(getComputedStyle(actions).pointerEvents).toBe('auto');
  });
});
