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

  it('400 둘을 서로 다른 문구로 가른다 — state 불일치와 code 무효는 원인이 다르다', () => {
    const badState = chzzkLinkFailureMessage(new ApiError(400, 'INVALID_STATE'));
    const badCode = chzzkLinkFailureMessage(new ApiError(400, 'INVALID_CODE'));
    expect(badState.title).not.toBe(badCode.title);
    // INVALID_STATE는 TTL 초과만이 아니라 계정 불일치·위조도 같은 코드로 온다 —
    // 만료 하나로 단정하면 다른 탭에서 계정을 바꾼 사용자에게 틀린 원인을 말하게 된다
    expect(badState.description).toContain('시간이 너무 지났거나');
    expect(badState.description).toContain('계정이 바뀌었어요');
  });

  it('모르는 실패는 폴백 문구로 — reason 원문을 사용자에게 노출하지 않는다', () => {
    const message = chzzkLinkFailureMessage(new ApiError(400, 'SOME_INTERNAL_CODE'));
    expect(message).toEqual({
      title: '연동에 실패했어요',
      description: '잠시 후 다시 시도해 주세요.',
    });
    expect(JSON.stringify(message)).not.toContain('SOME_INTERNAL_CODE');
  });

  it('reason 없는 5xx는 도달 실패로 — 연동을 처음부터 다시 시작하라고 안내한다 (POK-217)', () => {
    // dev 프록시가 재시작 중인 auth에 닿지 못하면 본문 없는 500, apiFetch의 refresh
    // 불가 판정이면 503이 온다. code·state는 이미 지워졌으므로 "다시 시도"가 아니라
    // 연동 재시작이 사용자가 실제로 할 일이다.
    for (const status of [500, 502, 503, 504]) {
      const message = chzzkLinkFailureMessage(new ApiError(status, `요청이 실패했다 (${status})`));
      expect(message.title).toBe('서버와 연결이 원활하지 않아요');
      expect(message.description).toContain('연동을 다시 시작');
    }
  });

  it('502라도 reason이 CHZZK_UNAVAILABLE이면 치지직 문구가 이긴다 — 도달 실패 판정보다 먼저', () => {
    const message = chzzkLinkFailureMessage(new ApiError(502, 'CHZZK_UNAVAILABLE'));
    expect(message.title).toBe('치지직과 연결하지 못했어요');
  });

  it('ApiError가 아닌 실패(네트워크 TypeError)는 여전히 폴백이다', () => {
    expect(chzzkLinkFailureMessage(new TypeError('network')).title).toBe('연동에 실패했어요');
  });
});
