import { Check, Minus, TriangleAlert, X } from 'lucide-preact';
import type { BridgeState, Tri } from '../lib/types';
import styles from './dock.module.css';

type CheckState = 'ok' | 'warn' | 'fail' | 'unknown';

function stateOf(value: Tri, failState: CheckState = 'fail'): CheckState {
  if (value === 'unknown') return 'unknown';
  return value ? 'ok' : failState;
}

function CheckIcon({ state }: { state: CheckState }) {
  const props = { size: 11, strokeWidth: 3, 'aria-hidden': true } as const;
  return (
    <span class={styles.checkIcon} data-state={state}>
      {state === 'ok' ? <Check {...props} /> : state === 'warn' ? <TriangleAlert {...props} /> : state === 'fail' ? <X {...props} /> : <Minus {...props} />}
    </span>
  );
}

// 송출 규약(ADR-020) 점검 — 방송 시작 시점에 플러그인이 채운다.
export function Checks({ state }: { state: BridgeState }) {
  const { checks } = state;
  const res = checks.width > 0 ? `${checks.width}×${checks.height}` : '—';
  const fps = checks.fps > 0 ? ` · ${Number.isInteger(checks.fps) ? checks.fps : checks.fps.toFixed(2)}fps` : '';
  const rows: { label: string; value: string; state: CheckState; srText: string }[] = [
    {
      label: '키프레임 간격 2초',
      value: checks.keyintSec >= 0 ? `${checks.keyintSec}초` : '—',
      state: stateOf(checks.gop2s),
      srText: '키프레임 간격',
    },
    {
      label: '방송 인코더 공유',
      value: checks.sharedEncoder === true ? '추가 인코딩 없음' : '—',
      state: stateOf(checks.sharedEncoder),
      srText: '인코더 공유',
    },
    {
      label: '1080p 출력',
      value: res + fps,
      state: stateOf(checks.res1080p, 'warn'),
      srText: '해상도',
    },
  ];

  return (
    <section class={styles.section} aria-labelledby="checks-title">
      <h3 id="checks-title" class={styles.sectionTitle}>
        {state.phase === 'live' || state.phase === 'reconnecting' || (state.phase === 'error' && state.errorCode)
          ? '송출 점검'
          : '송출 점검 · 방송 시작 시 확인'}
      </h3>
      <ul class={styles.checks}>
        {rows.map((r) => (
          <li class={styles.check} key={r.label}>
            <CheckIcon state={r.state} />
            <span class={styles.checkLabel}>{r.label}</span>
            <span class={styles.checkValue}>{r.value}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}
