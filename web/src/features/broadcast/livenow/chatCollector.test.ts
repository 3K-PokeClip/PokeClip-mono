import { describe, expect, it } from 'vitest';
import { chatFingerprint, donationLabel } from './chatCollector';

// 후원 라벨(POK-431). 수집기는 후원 종류(donationType)를 플랫폼의 이름 그대로 싣고, 금액(amount)은 그 단위의 개수다(원 환산
// 없음). 치지직은 CHAT · VIDEO(치즈), SOOP은 SDK action 이름 여섯이라 이름이 안 겹쳐 종류만으로 가른다.

describe('후원 라벨', () => {
  it('치지직 후원은 치즈다: 종류가 비어 온 옛 줄도 치지직이다(SOOP 이전엔 치지직뿐이었다)', () => {
    expect(donationLabel('CHAT', 5000)).toBe('치즈 5,000');
    expect(donationLabel('VIDEO', 1000)).toBe('치즈 1,000');
    expect(donationLabel(null, 5000)).toBe('치즈 5,000');
    expect(donationLabel(undefined, 5000)).toBe('치즈 5,000');
    expect(donationLabel('', 5000)).toBe('치즈 5,000');
  });

  it('SOOP 후원은 종류마다 그 단위의 개수다', () => {
    expect(donationLabel('BALLOON_GIFTED', 1000)).toBe('별풍선 1,000개');
    expect(donationLabel('ADBALLOON_GIFTED', 30)).toBe('애드벌룬 30개');
    expect(donationLabel('VIDEOBALLOON_GIFTED', 5)).toBe('영상풍선 5개');
    expect(donationLabel('STICKER_GIFTED', 2)).toBe('스티커 2개');
    // 미션 후원의 단위는 실방송 확인 전이라 단위를 지어내지 않는다
    expect(donationLabel('BATTLE_MISSION_GIFTED', 10)).toBe('미션 후원 10개');
    expect(donationLabel('CHALLENGE_MISSION_GIFTED', 10)).toBe('미션 후원 10개');
  });

  it('개수를 모르면(null) 숫자 없이 단위만 보인다: 0이라고 하지 않는다', () => {
    expect(donationLabel('BALLOON_GIFTED', null)).toBe('별풍선');
    expect(donationLabel('CHAT', null)).toBe('치즈');
  });

  it('모르는 종류는 단위를 지어내지 않고 「후원」으로 금액만 보인다', () => {
    expect(donationLabel('NEW_THING', 7)).toBe('후원 7');
    expect(donationLabel('NEW_THING', null)).toBe('후원');
    // 객체 기본 속성 이름이 와도 단위로 잡지 않는다
    expect(donationLabel('constructor', 7)).toBe('후원 7');
    expect(donationLabel('toString', 7)).toBe('후원 7');
  });
});

describe('겹침 지문', () => {
  it('플랫폼이 다르면 같은 글 · 같은 시각 · 같은 보낸 이라도 다른 줄이다: 표 지문에도 플랫폼이 있다(POK-466)', () => {
    const base = { kind: 'chat', time: '2026-10-25T12:00:00Z', senderChannelId: 's', text: 'ㅋㅋ' };
    expect(chatFingerprint({ ...base, platform: 'CHZZK' })).not.toBe(
      chatFingerprint({ ...base, platform: 'SOOP' }),
    );
    expect(chatFingerprint({ ...base, platform: 'SOOP' })).toBe(
      chatFingerprint({ ...base, platform: 'SOOP' }),
    );
  });
});
