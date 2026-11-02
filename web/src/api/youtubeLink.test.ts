import { ApiError } from './client';
import { youtubeLinkFailureMessage, youtubeLinkFailureOf } from './youtubeLink';

describe('youtubeLinkFailureOf', () => {
  it('reason과 status가 짝일 때만 실패 코드로 읽는다 — 여섯 코드 전부', () => {
    expect(youtubeLinkFailureOf(new ApiError(400, 'INVALID_STATE'))).toBe('INVALID_STATE');
    expect(youtubeLinkFailureOf(new ApiError(400, 'INVALID_CODE'))).toBe('INVALID_CODE');
    expect(youtubeLinkFailureOf(new ApiError(400, 'SCOPE_MISSING'))).toBe('SCOPE_MISSING');
    expect(youtubeLinkFailureOf(new ApiError(400, 'NO_CHANNEL'))).toBe('NO_CHANNEL');
    expect(youtubeLinkFailureOf(new ApiError(409, 'CHANNEL_ALREADY_LINKED'))).toBe(
      'CHANNEL_ALREADY_LINKED',
    );
    expect(youtubeLinkFailureOf(new ApiError(502, 'YOUTUBE_UNAVAILABLE'))).toBe(
      'YOUTUBE_UNAVAILABLE',
    );
  });

  it('status가 어긋나면 null이다 — 아는 reason이어도 믿지 않는다', () => {
    expect(youtubeLinkFailureOf(new ApiError(409, 'INVALID_STATE'))).toBeNull();
    expect(youtubeLinkFailureOf(new ApiError(400, 'YOUTUBE_UNAVAILABLE'))).toBeNull();
    expect(youtubeLinkFailureOf(new ApiError(502, 'SCOPE_MISSING'))).toBeNull();
  });

  it('모르는 reason·ApiError가 아닌 것·프로토타입 키는 전부 null이다', () => {
    expect(youtubeLinkFailureOf(new ApiError(400, 'SOMETHING_NEW'))).toBeNull();
    // 치지직 코드가 유튜브 판정으로 새면 안 된다 — 같은 화면에 두 배선이 산다
    expect(youtubeLinkFailureOf(new ApiError(502, 'CHZZK_UNAVAILABLE'))).toBeNull();
    // Object.hasOwn을 쓰지 않으면 'toString'·'constructor'가 in 검사를 통과한다
    expect(youtubeLinkFailureOf(new ApiError(400, 'toString'))).toBeNull();
    expect(youtubeLinkFailureOf(new TypeError('network'))).toBeNull();
    expect(youtubeLinkFailureOf(undefined)).toBeNull();
  });
});

describe('youtubeLinkFailureMessage', () => {
  it('409는 다른 계정에서 해제하라고 구체적으로 안내한다', () => {
    const { title, description } = youtubeLinkFailureMessage(
      new ApiError(409, 'CHANNEL_ALREADY_LINKED'),
    );
    expect(title).toBe('이미 다른 계정에 연동된 채널이에요');
    expect(description).toContain('다른 PokeClip 계정');
  });

  it('400 넷을 서로 다른 문구로 가른다 — 원인이 다르면 할 일 안내도 다르다', () => {
    const titles = ['INVALID_STATE', 'INVALID_CODE', 'SCOPE_MISSING', 'NO_CHANNEL'].map(
      (reason) => youtubeLinkFailureMessage(new ApiError(400, reason)).title,
    );
    expect(new Set(titles).size).toBe(4);
  });

  it('INVALID_STATE를 만료 하나로 단정하지 않는다 — 계정 불일치·위조도 같은 코드로 온다', () => {
    const { description } = youtubeLinkFailureMessage(new ApiError(400, 'INVALID_STATE'));
    expect(description).toContain('시간이 너무 지났거나');
    expect(description).toContain('계정이 바뀌었어요');
  });

  it('SCOPE_MISSING은 권한을 모두 허용하라고 지시한다 — 동의 화면에서 체크를 끈 경우다', () => {
    const { title, description } = youtubeLinkFailureMessage(new ApiError(400, 'SCOPE_MISSING'));
    expect(title).toBe('필요한 권한이 빠졌어요');
    expect(description).toContain('권한을 모두 허용');
  });

  it('NO_CHANNEL은 채널을 만들거나 계정을 바꾸라고 지시한다', () => {
    const { title, description } = youtubeLinkFailureMessage(new ApiError(400, 'NO_CHANNEL'));
    expect(title).toBe('연동할 유튜브 채널이 없어요');
    expect(description).toContain('채널을 만들거나');
  });

  it('모르는 실패는 폴백 문구로 — reason 원문을 사용자에게 노출하지 않는다', () => {
    const message = youtubeLinkFailureMessage(new ApiError(400, 'SOME_INTERNAL_CODE'));
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
      const message = youtubeLinkFailureMessage(
        new ApiError(status, `요청이 실패했다 (${status})`),
      );
      expect(message.title).toBe('서버와 연결이 원활하지 않아요');
      expect(message.description).toContain('연동을 다시 시작');
    }
  });

  it('502라도 reason이 YOUTUBE_UNAVAILABLE이면 유튜브 문구가 이긴다 — 도달 실패 판정보다 먼저', () => {
    const message = youtubeLinkFailureMessage(new ApiError(502, 'YOUTUBE_UNAVAILABLE'));
    expect(message.title).toBe('유튜브와 연결하지 못했어요');
  });

  it('fetch가 거부된 네트워크 단절(TypeError)도 도달 실패다 — 사용자 상황이 5xx와 같다', () => {
    const message = youtubeLinkFailureMessage(new TypeError('Failed to fetch'));
    expect(message.title).toBe('서버와 연결이 원활하지 않아요');
    expect(message.description).toContain('연동을 다시 시작');
  });

  it('TypeError가 아닌 알 수 없는 예외는 폴백이다 — 버그를 연결 장애로 위장하지 않는다', () => {
    expect(youtubeLinkFailureMessage(new Error('boom')).title).toBe('연동에 실패했어요');
  });
});
