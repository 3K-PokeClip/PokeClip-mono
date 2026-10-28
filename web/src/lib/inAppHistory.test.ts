import { beforeEach, describe, expect, it } from 'vitest';
import { hasInAppHistory, recordPathname, resetInAppHistoryForTests } from './inAppHistory';

describe('inAppHistory', () => {
  beforeEach(() => resetInAppHistoryForTests());

  it('처음 연 화면만 있으면 앞 화면이 없다(새 탭·주소 직접·새로고침)', () => {
    recordPathname('/clips/editor/studio');
    expect(hasInAppHistory()).toBe(false);
  });

  it('앱 안에서 옮겨 오면 앞 화면이 있다', () => {
    recordPathname('/broadcast/livenow');
    recordPathname('/clips/editor/studio');
    expect(hasInAppHistory()).toBe(true);
  });

  it('같은 화면 안 주소 값만 바뀌면 세지 않는다', () => {
    recordPathname('/clips/editor/studio');
    recordPathname('/clips/editor/studio');
    expect(hasInAppHistory()).toBe(false);
  });

  it('바로 앞 화면으로 돌아오면 한 칸 뺀다: 새 탭 편집기 → 보관함 → 뒤로 = 앞 화면 없음', () => {
    recordPathname('/clips/editor/studio');
    recordPathname('/clips/library');
    expect(hasInAppHistory()).toBe(true);
    recordPathname('/clips/editor/studio');
    expect(hasInAppHistory()).toBe(false);
  });

  it('뒤로 간 뒤 다시 들어가면 다시 앞 화면이 있다', () => {
    recordPathname('/clips/library');
    recordPathname('/clips/editor/studio');
    recordPathname('/clips/library');
    recordPathname('/clips/editor/studio');
    expect(hasInAppHistory()).toBe(true);
  });

  it('오래 써도 기록이 끝없이 자라지 않고 앞 화면은 남는다', () => {
    for (let i = 0; i < 200; i += 1) recordPathname(`/screen/${i}`);
    expect(hasInAppHistory()).toBe(true);
  });
});
