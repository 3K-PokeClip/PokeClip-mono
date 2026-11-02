import { describe, expect, it } from 'vitest';
import { metadata } from './layout';

describe('루트 레이아웃 metadata', () => {
  it('대시보드 전체를 색인에서 뺀다 — 지우면 로그인 화면·개발 데모가 검색에 나온다(POK-349)', () => {
    // Next는 하위 page의 metadata.robots가 없으면 이 값을 물려준다(oauth 콜백도 같은 값을 따로 둔다)
    expect(metadata.robots).toEqual({ index: false, follow: false });
  });
});
