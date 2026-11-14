import { describe, expect, it } from 'vitest';
import { cardSeekAtMs, keepKnownPlayerAtMs, pendingStartSeconds } from './cardSeek';

// 카드 · 마크 이동의 시각 규칙(POK-196 c13). 값은 clip 이 장면 함수로 정해 카드 응답에 실은 플레이어용 PDT(playerAtMs)다.
// 기준점(cardBase) · 카드 초 · 다시보기 축(timeBaseMs)은 epoch ms 손셈 값이다
const CARD_BASE = 1_794_426_519_000;
/** 카드 초 5초 — 지금 식 자리는 CARD_BASE + 5,000 */
const SECONDS = 5;

describe('cardSeekAtMs: 카드로 옮길 절대 시각', () => {
  it('플레이어용 PDT 가 있으면 그 값이다', () => {
    expect(cardSeekAtMs(1_794_426_524_000, CARD_BASE, SECONDS)).toBe(1_794_426_524_000);
  });

  it('장면이 없어 null 로 온 카드는 지금 식(기준점 + 카드 초)이다', () => {
    expect(cardSeekAtMs(null, CARD_BASE, SECONDS)).toBe(1_794_426_524_000);
  });

  it('칸이 없는 옛 clip 카드도 지금 식이다', () => {
    expect(cardSeekAtMs(undefined, CARD_BASE, SECONDS)).toBe(1_794_426_524_000);
  });

  it('판 NULL 카드처럼 기준점 경로와 수 초 다른 값이면 기준점이 아니라 그 값으로 간다', () => {
    // 출시 이미지 판 NULL 꼴: 지금 식은 …524,000 인데 장면 함수는 3.2초 뒤 조각을 가리킨다
    expect(cardSeekAtMs(1_794_426_527_200, CARD_BASE, SECONDS)).toBe(1_794_426_527_200);
  });

  it('틈 안 카드는 clip 이 실은 다음 회차 귀속 행 시작을 고치지 않고 그대로 쓴다', () => {
    // 카드 초는 틈 한가운데(지금 식 …524,000)지만 clip 은 재접속 뒤 첫 화면 시작(…561,480)을 싣는다
    expect(cardSeekAtMs(1_794_426_561_480, CARD_BASE, SECONDS)).toBe(1_794_426_561_480);
  });

  it('기준점을 몰라도 플레이어용 PDT 가 있으면 그 값으로 간다', () => {
    expect(cardSeekAtMs(1_794_426_524_000, null, SECONDS)).toBe(1_794_426_524_000);
  });

  it('기준점도 플레이어용 PDT 도 모르면 옮길 시각이 없다(null)', () => {
    expect(cardSeekAtMs(null, null, SECONDS)).toBeNull();
    expect(cardSeekAtMs(undefined, null, SECONDS)).toBeNull();
  });
});

describe('pendingStartSeconds: 다시보기가 설 때 넘길 받아 둔 초', () => {
  /** 회차 다시보기 축 0초(axisZeroMs) — 기준점과 1.37초 다르다 */
  const TIME_BASE = CARD_BASE + 1_370;

  it('받아 둔 절대 시각이 있으면 다시보기 축 0초에서 잰 초다', () => {
    expect(pendingStartSeconds(TIME_BASE + 2_842_500, 2_842, TIME_BASE)).toBe(2_842.5);
  });

  it('받아 둔 절대 시각이 있어도 다시보기 축이 서기 전에는 null 이다 — 재생기가 기다린다', () => {
    expect(pendingStartSeconds(TIME_BASE + 2_842_500, 2_842, null)).toBeNull();
  });

  it('받아 둔 절대 시각이 없으면 받아 둔 초 그대로다', () => {
    expect(pendingStartSeconds(null, 2_842, TIME_BASE)).toBe(2_842);
    expect(pendingStartSeconds(null, 2_842, null)).toBe(2_842);
  });

  it('받아 둔 자리가 없으면 null 이다', () => {
    expect(pendingStartSeconds(null, null, TIME_BASE)).toBeNull();
    expect(pendingStartSeconds(TIME_BASE, null, TIME_BASE)).toBeNull();
  });
});

describe('keepKnownPlayerAtMs: 통로 카드가 칸 없이 와도 받아 둔 값을 지킨다', () => {
  interface Wire {
    id: number;
    eventSeq: number;
    playerAtMs?: number | null;
  }
  const KNOWN: Wire = { id: 2, eventSeq: 1, playerAtMs: 1_794_426_524_000 };

  it('칸 없이 온 카드는 앞 카드의 값을 이어받는다', () => {
    const next: Wire = { id: 2, eventSeq: 2 };

    expect(keepKnownPlayerAtMs(KNOWN, next)).toEqual({
      id: 2,
      eventSeq: 2,
      playerAtMs: 1_794_426_524_000,
    });
  });

  it('null 로 온 카드는 받는다 — 장면이 없다는 서버 답이다', () => {
    const next: Wire = { id: 2, eventSeq: 2, playerAtMs: null };

    expect(keepKnownPlayerAtMs(KNOWN, next)).toBe(next);
  });

  it('새 값이 실려 오면 새 값이다', () => {
    const next: Wire = { id: 2, eventSeq: 2, playerAtMs: 1_794_426_527_200 };

    expect(keepKnownPlayerAtMs(KNOWN, next)).toBe(next);
  });

  it('앞 카드가 없거나 앞 카드도 값을 모르면 온 카드 그대로다', () => {
    const next: Wire = { id: 2, eventSeq: 2 };

    expect(keepKnownPlayerAtMs(undefined, next)).toBe(next);
    expect(keepKnownPlayerAtMs({ id: 2, eventSeq: 1 }, next)).toBe(next);
  });
});
