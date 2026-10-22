import { describe, expect, it } from 'vitest';
import { isWaitingForBroadcast, setupSteps } from './setupSteps';
import type { HomeLinks } from './useHomeMockState';

const base: HomeLinks = {
  chzzk: { linked: false },
  soop: { linked: false },
  youtube: { linked: false },
  streamKeyIssued: true,
};
const chzzkOn = { linked: true, status: 'ACTIVE' as const, channelName: '재환의 방송' };
const soopOn = { linked: true, status: 'ACTIVE' as const, nickname: '숲너구리' };
const channelStep = (links: HomeLinks) => setupSteps(links).find((s) => s.id === 'chzzk')!;

describe('setupSteps — 방송 채널 단계는 치지직·SOOP 둘 중 하나 (POK-396)', () => {
  it('치지직만 연동 → 완료, 문구는 그대로 채널 이름', () => {
    const step = channelStep({ ...base, chzzk: chzzkOn });
    expect(step).toMatchObject({ done: true, unknown: false, doneNote: '재환의 방송 연결됨' });
  });

  it('SOOP만 연동 → 완료, 「SOOP 연결됨」', () => {
    const step = channelStep({ ...base, soop: soopOn });
    expect(step).toMatchObject({ done: true, unknown: false, doneNote: 'SOOP 연결됨' });
  });

  it('둘 다 연동 → 「치지직 · SOOP 연결됨」', () => {
    expect(channelStep({ ...base, chzzk: chzzkOn, soop: soopOn }).doneNote).toBe(
      '치지직 · SOOP 연결됨',
    );
  });

  it('둘 다 아님 → 미완료', () => {
    expect(channelStep(base)).toMatchObject({ done: false, unknown: false });
  });

  it('둘 다 못 읽음 → 확인 중', () => {
    expect(channelStep({ ...base, chzzk: null, soop: null })).toMatchObject({
      done: false,
      unknown: true,
    });
  });

  it('하나는 못 읽고 하나는 연동 → 완료', () => {
    expect(channelStep({ ...base, chzzk: null, soop: soopOn })).toMatchObject({
      done: true,
      unknown: false,
    });
    expect(channelStep({ ...base, chzzk: chzzkOn, soop: null })).toMatchObject({
      done: true,
      unknown: false,
    });
  });

  it('하나는 못 읽고 하나는 미연동 → 확인 중(안 했다고 단정하지 않는다)', () => {
    expect(channelStep({ ...base, chzzk: null })).toMatchObject({ done: false, unknown: true });
    expect(channelStep({ ...base, soop: null })).toMatchObject({ done: false, unknown: true });
  });

  it('단계 id는 chzzk 그대로다 — 시작 가이드 표적이 기대한다', () => {
    expect(setupSteps(base)[0]!.id).toBe('chzzk');
  });
});

describe('isWaitingForBroadcast — 방송 채널은 둘 중 하나 (C8)', () => {
  it('SOOP만 연동 + 코드 발급 → 방송만 남았다', () => {
    expect(isWaitingForBroadcast({ ...base, soop: soopOn })).toBe(true);
  });

  it('치지직만 연동 + 코드 발급 → 방송만 남았다', () => {
    expect(isWaitingForBroadcast({ ...base, chzzk: chzzkOn })).toBe(true);
  });

  it('둘 다 미연동이거나 코드가 없으면 아니다', () => {
    expect(isWaitingForBroadcast(base)).toBe(false);
    expect(isWaitingForBroadcast({ ...base, soop: soopOn, streamKeyIssued: false })).toBe(false);
  });
});
