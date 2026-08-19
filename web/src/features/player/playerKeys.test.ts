import { describe, expect, it } from 'vitest';
import { seekIntentForKey } from './playerKeys';
import { SEEK_PAGE_SECONDS, SEEK_STEP_SECONDS } from './playerMath';

describe('seekIntentForKey — 시크바와 전역 단축키가 공유하는 키맵', () => {
  it('화살표는 한 걸음 이동하고, 왼쪽/아래가 과거 방향이다', () => {
    const back = { kind: 'by', seconds: SEEK_STEP_SECONDS };
    const forward = { kind: 'by', seconds: -SEEK_STEP_SECONDS };
    expect(seekIntentForKey({ key: 'ArrowLeft' })).toEqual(back);
    expect(seekIntentForKey({ key: 'ArrowDown' })).toEqual(back);
    expect(seekIntentForKey({ key: 'ArrowRight' })).toEqual(forward);
    expect(seekIntentForKey({ key: 'ArrowUp' })).toEqual(forward);
  });

  it('PageUp/PageDown은 큰 걸음이다', () => {
    expect(seekIntentForKey({ key: 'PageDown' })).toEqual({
      kind: 'by',
      seconds: SEEK_PAGE_SECONDS,
    });
    expect(seekIntentForKey({ key: 'PageUp' })).toEqual({
      kind: 'by',
      seconds: -SEEK_PAGE_SECONDS,
    });
  });

  it('Home은 창의 가장 오래된 지점, End는 라이브 복귀다', () => {
    // 창 크기와 무관해야 하므로 초가 아니라 비율로 표현한다
    expect(seekIntentForKey({ key: 'Home' })).toEqual({ kind: 'toFraction', fraction: 0 });
    expect(seekIntentForKey({ key: 'End' })).toEqual({ kind: 'live' });
  });

  it('처리하지 않는 키는 null이다 — 호출부가 preventDefault를 걸지 않게', () => {
    expect(seekIntentForKey({ key: ' ' })).toBeNull();
    expect(seekIntentForKey({ key: 'Tab' })).toBeNull();
    expect(seekIntentForKey({ key: 'k' })).toBeNull();
  });

  // 회귀: 수정자 조합은 OS·브라우저 단축키다. 가로채면 호출부가 preventDefault까지 걸어
  // 뒤로 가기 자체가 죽는다. 전역 단축키(GlassPlayer)는 영상을 한 번 클릭하기만 하면
  // 활성화되므로, 시크바에 Tab 포커스를 넣는 경로보다 훨씬 자주 밟힌다.
  it('Cmd/Ctrl/Alt 조합은 가로채지 않는다 — 브라우저 뒤로 가기가 살아 있어야 한다', () => {
    expect(seekIntentForKey({ key: 'ArrowLeft', metaKey: true })).toBeNull();
    expect(seekIntentForKey({ key: 'ArrowLeft', ctrlKey: true })).toBeNull();
    expect(seekIntentForKey({ key: 'ArrowLeft', altKey: true })).toBeNull();
    expect(seekIntentForKey({ key: 'Home', ctrlKey: true })).toBeNull();
    expect(seekIntentForKey({ key: 'End', metaKey: true })).toBeNull();
  });

  it('Shift는 막지 않는다 — 플레이어에서 충돌하는 기본 동작이 없다', () => {
    const shiftArrow = { key: 'ArrowLeft', shiftKey: true };
    expect(seekIntentForKey(shiftArrow)).toEqual({ kind: 'by', seconds: SEEK_STEP_SECONDS });
  });

  // 회귀: 키를 누르고 있으면 OS 자동반복이 초당 수십 번 들어온다. 그대로 시킹하면
  // hls.js가 매번 백/포워드 버퍼를 비우고 프래그먼트를 다시 받아 재생이 끊긴다.
  // 드래그를 "놓을 때 한 번만" 커밋한 것과 같은 이유의 방어다.
  it('자동반복(repeat)은 무시한다 — 누르고 있어도 시크가 폭주하지 않는다', () => {
    expect(seekIntentForKey({ key: 'ArrowLeft', repeat: true })).toBeNull();
    expect(seekIntentForKey({ key: 'PageDown', repeat: true })).toBeNull();
    expect(seekIntentForKey({ key: 'End', repeat: true })).toBeNull();
  });
});
