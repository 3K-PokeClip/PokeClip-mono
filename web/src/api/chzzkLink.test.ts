import { ApiError } from './client';
import { chzzkLinkFailureMessage, chzzkLinkFailureOf } from './chzzkLink';

describe('chzzkLinkFailureOf', () => {
  it('reason과 status가 짝일 때만 실패 코드로 읽는다', () => {
    expect(chzzkLinkFailureOf(new ApiError(400, 'INVALID_STATE'))).toBe('INVALID_STATE');
    expect(chzzkLinkFailureOf(new ApiError(400, 'INVALID_CODE'))).toBe('INVALID_CODE');
    expect(chzzkLinkFailureOf(new ApiError(409, 'CHANNEL_ALREADY_LINKED'))).toBe(
      'CHANNEL_ALREADY_LINKED',
    );
    expect(chzzkLinkFailureOf(new ApiError(502, 'CHZZK_UNAVAILABLE'))).toBe('CHZZK_UNAVAILABLE');
  });

  it('status가 어긋나면 null이다 — 아는 reason이어도 믿지 않는다', () => {
    expect(chzzkLinkFailureOf(new ApiError(409, 'INVALID_STATE'))).toBeNull();
    expect(chzzkLinkFailureOf(new ApiError(400, 'CHZZK_UNAVAILABLE'))).toBeNull();
  });

  it('모르는 reason·ApiError가 아닌 것·프로토타입 키는 전부 null이다', () => {
    expect(chzzkLinkFailureOf(new ApiError(400, 'SOMETHING_NEW'))).toBeNull();
    expect(chzzkLinkFailureOf(new ApiError(500, '요청이 실패했다 (500)'))).toBeNull();
    // Object.hasOwn을 쓰지 않으면 'toString'·'constructor'가 in 검사를 통과한다
    expect(chzzkLinkFailureOf(new ApiError(400, 'toString'))).toBeNull();
    expect(chzzkLinkFailureOf(new TypeError('network'))).toBeNull();
    expect(chzzkLinkFailureOf(undefined)).toBeNull();
  });
});

describe('chzzkLinkFailureMessage', () => {
  it('409는 다른 계정에서 해제하라고 구체적으로 안내한다 (POK-112 승계 조건)', () => {
    const { title, description } = chzzkLinkFailureMessage(
      new ApiError(409, 'CHANNEL_ALREADY_LINKED'),
    );
    expect(title).toBe('이미 다른 계정에 연동된 채널이에요');
    expect(description).toContain('다른 PokeClip 계정');
  });

  it('400 둘을 서로 다른 문구로 가른다 — 만료와 무효는 원인이 다르다', () => {
    const expired = chzzkLinkFailureMessage(new ApiError(400, 'INVALID_STATE'));
    const invalid = chzzkLinkFailureMessage(new ApiError(400, 'INVALID_CODE'));
    expect(expired.title).not.toBe(invalid.title);
    expect(expired.title).toContain('만료');
  });

  it('모르는 실패는 폴백 문구로 — reason 원문을 사용자에게 노출하지 않는다', () => {
    const message = chzzkLinkFailureMessage(new ApiError(400, 'SOME_INTERNAL_CODE'));
    expect(message).toEqual({
      title: '연동에 실패했어요',
      description: '잠시 후 다시 시도해 주세요.',
    });
    expect(JSON.stringify(message)).not.toContain('SOME_INTERNAL_CODE');
  });
});
