import { describe, expect, it } from 'vitest';
import {
  dockIndexOf,
  dockTransitionType,
  isDockHrefActive,
} from '@/components/app-shell/dockTransition';

describe('dockTransitionType', () => {
  it('오른쪽 탭이면 forward, 왼쪽 탭이면 back이다', () => {
    expect(dockTransitionType('/home', '/settings')).toBe('dock-forward');
    expect(dockTransitionType('/broadcast', '/clips')).toBe('dock-forward');
    expect(dockTransitionType('/settings', '/home')).toBe('dock-back');
    expect(dockTransitionType('/clips', '/broadcast')).toBe('dock-back');
  });

  it('하위 경로에서 출발해도 그 탭 기준으로 방향을 잡는다', () => {
    expect(dockTransitionType('/settings/plugin', '/home')).toBe('dock-back');
  });

  it('같은 탭이면 방향이 없다 — 슬라이드가 돌면 안 된다', () => {
    expect(dockTransitionType('/broadcast', '/broadcast')).toBeUndefined();
    expect(dockTransitionType('/settings/plugin', '/settings')).toBeUndefined();
  });

  it('독 밖(로그인 등)에서 들어오면 방향이 없다', () => {
    expect(dockTransitionType('/login', '/home')).toBeUndefined();
  });
});

describe('isDockHrefActive', () => {
  it('하위 경로는 포함하되 접두사만 겹치는 경로는 제외한다', () => {
    expect(isDockHrefActive('/settings', '/settings')).toBe(true);
    expect(isDockHrefActive('/settings/plugin', '/settings')).toBe(true);
    expect(isDockHrefActive('/homework', '/home')).toBe(false);
  });
});

describe('dockIndexOf', () => {
  it('탭 순서를 그대로 돌려주고, 독 밖 경로는 -1이다', () => {
    expect(dockIndexOf('/home')).toBe(0);
    expect(dockIndexOf('/settings/plugin')).toBe(3);
    expect(dockIndexOf('/login')).toBe(-1);
  });
});
