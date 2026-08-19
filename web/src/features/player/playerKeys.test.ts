import { describe, expect, it } from 'vitest';
import { seekIntentForKey } from './playerKeys';
import { SEEK_PAGE_SECONDS, SEEK_STEP_SECONDS } from './playerMath';

describe('seekIntentForKey — 시크바와 전역 단축키가 공유하는 키맵', () => {
  it('화살표는 한 걸음 이동하고, 왼쪽/아래가 과거 방향이다', () => {
    expect(seekIntentForKey('ArrowLeft')).toEqual({ kind: 'by', seconds: SEEK_STEP_SECONDS });
    expect(seekIntentForKey('ArrowDown')).toEqual({ kind: 'by', seconds: SEEK_STEP_SECONDS });
    expect(seekIntentForKey('ArrowRight')).toEqual({ kind: 'by', seconds: -SEEK_STEP_SECONDS });
    expect(seekIntentForKey('ArrowUp')).toEqual({ kind: 'by', seconds: -SEEK_STEP_SECONDS });
  });

  it('PageUp/PageDown은 큰 걸음이다', () => {
    expect(seekIntentForKey('PageDown')).toEqual({ kind: 'by', seconds: SEEK_PAGE_SECONDS });
    expect(seekIntentForKey('PageUp')).toEqual({ kind: 'by', seconds: -SEEK_PAGE_SECONDS });
  });

  it('Home은 창의 가장 오래된 지점, End는 라이브 복귀다', () => {
    // 창 크기와 무관해야 하므로 초가 아니라 비율로 표현한다
    expect(seekIntentForKey('Home')).toEqual({ kind: 'toFraction', fraction: 0 });
    expect(seekIntentForKey('End')).toEqual({ kind: 'live' });
  });

  it('처리하지 않는 키는 null이다 — 호출부가 preventDefault를 걸지 않게', () => {
    expect(seekIntentForKey(' ')).toBeNull();
    expect(seekIntentForKey('Tab')).toBeNull();
    expect(seekIntentForKey('k')).toBeNull();
  });
});
