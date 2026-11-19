import { describe, expect, it } from 'vitest';
import { isWaitingForBroadcast, setupSteps } from './setupSteps';
import type { HomeLinks } from './useHomeMockState';

const base: HomeLinks = {
  chzzk: { linked: false },
  soop: { linked: false },
  youtube: { linked: false },
  streamKeyIssued: true,
  pluginPaired: true,
};
const pluginStep = (links: HomeLinks) => setupSteps(links).find((s) => s.id === 'plugin')!;
const chzzkOn = { linked: true, status: 'ACTIVE' as const, channelName: '재환의 방송' };
const soopOn = { linked: true, status: 'ACTIVE' as const, nickname: '숲너구리' };
const channelStep = (links: HomeLinks) => setupSteps(links).find((s) => s.id === 'chzzk')!;

describe('setupSteps: 방송 채널 단계는 치지직·SOOP 둘 중 하나 (POK-396)', () => {
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

  it('단계 id는 chzzk 그대로다: 시작 가이드 표적이 기대한다', () => {
    expect(setupSteps(base)[0]!.id).toBe('chzzk');
  });
});

describe('setupSteps: 플러그인 단계는 연결돼야 끝난다 (POK-608·609)', () => {
  it('연결 시각이 있으면 완료', () => {
    expect(pluginStep(base)).toMatchObject({ done: true, unknown: false, doneNote: '연결 완료' });
  });

  it('코드만 받았으면 완료가 아니다: 남은 일(OBS에 넣기)을 말하고 연결하기로 보낸다', () => {
    // 2026-11-18~19 새 회원 넷이 코드를 받았고 OBS에 넣은 사람은 없었다 — 그때는 이 줄이 완료였다
    expect(pluginStep({ ...base, pluginPaired: false })).toMatchObject({
      done: false,
      unknown: false,
      desc: '코드를 OBS의 PokeClip 창에 넣으면 연결돼요',
      action: { label: '연결하기', href: '/settings/plugin' },
    });
  });

  it('코드도 받지 않았으면 코드가 아니라 설치부터 권한다', () => {
    const step = pluginStep({ ...base, streamKeyIssued: false, pluginPaired: false });
    expect(step).toMatchObject({
      done: false,
      desc: '플러그인을 설치하고 연동 코드로 연결해요',
      action: { label: '설치하기', href: '/settings/plugin' },
    });
    expect(step.action!.label).not.toMatch(/코드/);
  });

  it('상태를 못 읽었으면 확인 중: 버튼을 내밀지 않게 unknown', () => {
    expect(pluginStep({ ...base, streamKeyIssued: null, pluginPaired: null })).toMatchObject({
      done: false,
      unknown: true,
    });
  });
});

describe('isWaitingForBroadcast: 방송 채널은 둘 중 하나 (C8)', () => {
  it('SOOP만 연동 + 플러그인 연결 → 방송만 남았다', () => {
    expect(isWaitingForBroadcast({ ...base, soop: soopOn })).toBe(true);
  });

  it('치지직만 연동 + 플러그인 연결 → 방송만 남았다', () => {
    expect(isWaitingForBroadcast({ ...base, chzzk: chzzkOn })).toBe(true);
  });

  it('둘 다 미연동이거나 플러그인을 연결하지 않았으면 아니다', () => {
    expect(isWaitingForBroadcast(base)).toBe(false);
    expect(isWaitingForBroadcast({ ...base, soop: soopOn, pluginPaired: false })).toBe(false);
  });

  it('코드 발급만으로는 아니다 — 플러그인 없는 회원에게 「이제 방송만 켜면 돼요」라고 했다 (POK-608)', () => {
    expect(
      isWaitingForBroadcast({
        ...base,
        chzzk: chzzkOn,
        streamKeyIssued: true,
        pluginPaired: false,
      }),
    ).toBe(false);
  });
});
