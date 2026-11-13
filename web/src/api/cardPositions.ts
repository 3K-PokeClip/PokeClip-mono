// 카드 · 마크 응답의 위치 칸과 소리 주소 응답의 확보 칸(POK-196) — clip 이 장면 함수 · 확보 판정으로 정해 실어 준 값을 읽는다.
// 칸 이름은 이 파일 한 곳에만 둔다. 인자는 구조형이라 편집기 JumpCard 와 라이브 카드 스냅숏을 함께 받는다.
//   playerAtMs           카드 시점의 플레이어용 PDT(epoch ms). 서버가 값을 못 정하면 null 로 실린다(칸 생략이 아니다) —
//                        장면이 없을 때 · 장면 행의 PDT 칸이 비었을 때 · 장면 조회가 실패했을 때(빈 장면으로 친다) 셋 다
//   playerWindow         창 두 끝의 목록 PDT {startMs, endMs}. 카드 칸이 null 이거나 창 끝 하나라도 같은 세 까닭으로
//                        PDT 를 못 정하면 null
//   audioCapture         끝 소리 확보 상태 attached | not_ready | skipped. 소리 주소 응답에 늘 실린다
//   audioCaptureSegment  확보 조각 {seq, startAtMs, durationMs, url}. audioCapture 가 attached 일 때만 실린다.
//                        startAtMs 는 확보 판정 축(ms 내림)이라 segments 의 같은 seq 와 1ms 다를 수 있다 — 자리는
//                        startAtMs 가 아니라 seq 로 찾는다

import type { SegmentAudioAccess } from './clipEditor';

/** 창 두 끝의 목록 PDT(epoch ms). startMs ≤ endMs 다 */
export interface PlayerWindow {
  startMs: number;
  endMs: number;
}

/** 확보 조각 — 소리 주소 응답의 조각 한 줄과 같은 꼴이다 */
export type AudioCaptureSegment = SegmentAudioAccess['segments'][number];

/**
 * 끝 소리 확보 상태. notReady 는 다시 물으면 바뀔 수 있는 기다림이고, none 은 확보 없이 진행이다.
 * none 은 skipped · 칸 없음(옛 clip) · 모르는 값 · attached 인데 확보 조각을 못 읽음을 한데 모은다 — 웹이 할 일이 같다
 * (끝 소리를 비워 두고 렌더는 제 판정대로 확보한다). 모르는 값을 기다림으로 치면 끝나지 않는 기다림이 된다.
 */
export type AudioCaptureState =
  { kind: 'attached'; segment: AudioCaptureSegment } | { kind: 'notReady' } | { kind: 'none' };

/**
 * 카드 시점의 플레이어용 PDT. 유한 수면 그 값, null(서버가 못 정함 — 머리 주석의 세 까닭)이면 null, 칸이 없으면 undefined 다.
 * null 과 undefined 는 뜻이 달라 접지 않는다 — 칸 없음은 이 칸을 모르는 옛 clip 이다. 숫자가 아닌 값(문자열 · NaN ·
 * Infinity 등)은 믿을 수 없어 칸 없음으로 친다.
 */
export function readPlayerAtMs(card: { playerAtMs?: unknown }): number | null | undefined {
  const value = card.playerAtMs;
  if (value === null) return null;
  if (isFiniteNumber(value)) return value;
  return undefined;
}

/**
 * 카드 창 두 끝의 플레이어용 PDT. 두 끝이 유한 수이고 끝 ≥ 시작이면 두 끝만 새 객체로, null 이면 null, 칸이 없으면
 * undefined 다(readPlayerAtMs 와 같은 규칙 — 칸 없음은 범위를 어림(estimate)으로 떨어뜨리는 신호다). 서버는 끝을 시작에
 * 묶어 끝 < 시작을 내지 않으므로, 그런 창이나 숫자가 아닌 끝은 믿을 수 없어 칸 없음으로 친다.
 */
export function readPlayerWindow(card: {
  playerWindow?: unknown;
}): PlayerWindow | null | undefined {
  const value = card.playerWindow;
  if (value === null) return null;
  if (!isRecord(value)) return undefined;
  const { startMs, endMs } = value;
  if (!isFiniteNumber(startMs) || !isFiniteNumber(endMs)) return undefined;
  if (endMs < startMs) return undefined;
  return { startMs, endMs };
}

/**
 * 소리 주소 응답의 끝 소리 확보 상태. 확보 조각은 attached 일 때만 읽는다 — 다른 값인데 실려 와도 무시한다.
 * attached 인데 확보 조각이 없거나 꼴이 틀리면 놓을 소리가 없으니 none 이다.
 */
export function readAudioCapture(access: {
  audioCapture?: unknown;
  audioCaptureSegment?: unknown;
}): AudioCaptureState {
  switch (access.audioCapture) {
    case 'attached': {
      const segment = readCaptureSegment(access.audioCaptureSegment);
      return segment === undefined ? { kind: 'none' } : { kind: 'attached', segment };
    }
    case 'not_ready':
      return { kind: 'notReady' };
    default:
      return { kind: 'none' };
  }
}

function readCaptureSegment(value: unknown): AudioCaptureSegment | undefined {
  if (!isRecord(value)) return undefined;
  const { seq, startAtMs, durationMs, url } = value;
  if (!isFiniteNumber(seq) || !isFiniteNumber(startAtMs) || !isFiniteNumber(durationMs))
    return undefined;
  // seq 는 소리 주소 응답 segments 의 seq 와 맞춰 볼 정수이고, 길이가 0 이하인 조각은 놓을 소리가 없다
  if (!Number.isInteger(seq) || durationMs <= 0) return undefined;
  if (typeof url !== 'string') return undefined;
  return { seq, startAtMs, durationMs, url };
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
