import { describe, expect, it } from 'vitest';
import {
  keepKnownThumbnail,
  LIVE_REFRESH_MS,
  pickThumbnailUrl,
  signedAtMs,
  STILL_REFRESH_MS,
} from './thumbnailUrl';

// 서버는 목록마다 주소를 새로 서명한다. 같은 사진이면 보던 주소를 이어 써야 목록을 다시 받을 때마다 사진을 다시 받지 않는다.
const SIGNED = Date.UTC(2026, 9, 4, 10, 15, 0);
const url = (key: string, at: string) =>
  `http://localhost:4566/clips-local/thumbnails/${key}.jpg?X-Amz-Date=${at}&X-Amz-Expires=3600&X-Amz-Signature=${at}`;
const A_1015 = url('card/7', '20261004T101500Z');
const A_1016 = url('card/7', '20261004T101600Z');
const B_1016 = url('card/8', '20261004T101600Z');

describe('signedAtMs', () => {
  it('미리서명 주소의 서명 시각을 읽는다', () => {
    expect(signedAtMs(A_1015)).toBe(SIGNED);
  });

  it('서명 시각이 없거나 주소가 아니면 null', () => {
    expect(signedAtMs('http://x/a.jpg')).toBeNull();
    expect(signedAtMs('주소 아님')).toBeNull();
  });
});

describe('pickThumbnailUrl', () => {
  it('같은 사진이고 아직 새것이면 보던 주소를 이어 쓴다', () => {
    expect(pickThumbnailUrl(A_1015, A_1016, SIGNED + 60_000, STILL_REFRESH_MS)).toBe(A_1015);
  });

  it('갱신 시간이 지나면 새 주소로 바꾼다(라이브는 1분)', () => {
    expect(pickThumbnailUrl(A_1015, A_1016, SIGNED + 59_000, LIVE_REFRESH_MS)).toBe(A_1015);
    expect(pickThumbnailUrl(A_1015, A_1016, SIGNED + 60_000, LIVE_REFRESH_MS)).toBe(A_1016);
    expect(pickThumbnailUrl(A_1015, A_1016, SIGNED + STILL_REFRESH_MS, STILL_REFRESH_MS)).toBe(
      A_1016,
    );
  });

  it('다른 사진이면 바로 바꾼다', () => {
    expect(pickThumbnailUrl(A_1015, B_1016, SIGNED, STILL_REFRESH_MS)).toBe(B_1016);
  });

  it('처음이거나 사진이 사라지면 들어온 값을 따른다', () => {
    expect(pickThumbnailUrl(null, A_1015, SIGNED, STILL_REFRESH_MS)).toBe(A_1015);
    expect(pickThumbnailUrl(A_1015, null, SIGNED, STILL_REFRESH_MS)).toBeNull();
  });

  it('보던 주소의 서명 시각을 모르면 새 주소로 바꾼다(만료된 주소를 쥐지 않게)', () => {
    expect(
      pickThumbnailUrl(
        'http://localhost:4566/clips-local/thumbnails/card/7.jpg',
        A_1016,
        SIGNED,
        STILL_REFRESH_MS,
      ),
    ).toBe(A_1016);
  });
});

describe('keepKnownThumbnail', () => {
  it('통로로 온 카드(사진 칸 없음)가 받아 둔 사진을 지우지 않는다', () => {
    type Card = { id: number; claimedBy: string | null; thumbnailUrl: string | null };
    const prev: Card = { id: 7, claimedBy: null, thumbnailUrl: A_1015 };
    const next: Card = { id: 7, claimedBy: '9', thumbnailUrl: null };
    expect(keepKnownThumbnail(prev, next)).toEqual({ id: 7, claimedBy: '9', thumbnailUrl: A_1015 });
  });

  it('새 값에 사진이 있으면 그것을, 둘 다 없으면 빈 채로 둔다', () => {
    expect(
      keepKnownThumbnail({ thumbnailUrl: A_1015 }, { thumbnailUrl: B_1016 }).thumbnailUrl,
    ).toBe(B_1016);
    expect(keepKnownThumbnail(undefined, { thumbnailUrl: null }).thumbnailUrl).toBeNull();
  });
});
