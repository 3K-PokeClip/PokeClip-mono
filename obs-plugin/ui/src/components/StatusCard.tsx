import { Unlink } from 'lucide-preact';
import { useEffect, useState } from 'preact/hooks';
import { reasonText } from '../lib/copy';
import { formatKbps, formatUptime, sparklinePath } from '../lib/format';
import type { BridgeState } from '../lib/types';
import styles from './dock.module.css';

const SPARK_POINTS = 60;

function useBitrateHistory(state: BridgeState): number[] {
  const [history, setHistory] = useState<number[]>([]);
  useEffect(() => {
    if (state.phase !== 'live' && state.phase !== 'reconnecting') {
      setHistory([]);
      return;
    }
    if (state.stats.uptimeSec <= 0) return; // 첫 통계 전 0 샘플로 선이 바닥에서 튀지 않게
    setHistory((h) => [...h, state.stats.bitrateKbps].slice(-SPARK_POINTS));
  }, [state.stats.uptimeSec, state.phase]);
  return history;
}

function Sparkline({ values }: { values: number[] }) {
  const w = 240;
  const h = 36;
  const line = sparklinePath(values, w, h);
  return (
    <svg class={styles.sparkline} viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none" aria-hidden="true">
      <defs>
        <linearGradient id="pc-spark-fill" x1="0" x2="0" y1="0" y2="1">
          <stop offset="0%" stop-color="var(--pc-color-point)" stop-opacity="0.28" />
          <stop offset="100%" stop-color="var(--pc-color-point)" stop-opacity="0" />
        </linearGradient>
      </defs>
      <path class={styles.sparkFill} d={`${line} L${w} ${h} L0 ${h} Z`} fill="url(#pc-spark-fill)" />
      <path class={styles.sparkStroke} d={line} />
    </svg>
  );
}

type Tone = 'ready' | 'live' | 'warning' | 'danger' | undefined;

function copyFor(state: BridgeState): { title: string; desc: string; tone: Tone } {
  switch (state.phase) {
    case 'live':
      return { title: 'PokeClip으로 전송 중', desc: '본방과 같은 인코더로 함께 보내고 있어요.', tone: 'live' };
    case 'reconnecting':
      return { title: '재연결 중', desc: '회선이 끊겨 자동으로 다시 붙는 중이에요.', tone: 'warning' };
    case 'starting':
      return { title: '연결 중', desc: 'PokeClip 수신 서버에 붙는 중이에요.', tone: undefined };
    case 'stopping':
      return { title: '정지 중', desc: '본방과 함께 전송을 마무리하고 있어요.', tone: undefined };
    case 'error':
      if (state.errorCode)
        return { title: '전송이 멈췄어요', desc: 'OBS에서 방송을 다시 시작하면 다시 시도해요.', tone: 'danger' };
      return { title: '준비됐어요', desc: 'OBS에서 방송을 시작하면 PokeClip으로 함께 전송돼요.', tone: 'ready' };
    default:
      return { title: '준비됐어요', desc: 'OBS에서 방송을 시작하면 PokeClip으로 함께 전송돼요.', tone: 'ready' };
  }
}

export function StatusCard({ state, onAskUnpair }: { state: BridgeState; onAskUnpair: () => void }) {
  const history = useBitrateHistory(state);
  const { phase } = state;
  const sending = phase === 'live' || phase === 'reconnecting';
  const busy = phase === 'starting' || phase === 'stopping';
  const errored = phase === 'error' && Boolean(state.errorCode);
  const { title, desc, tone } = copyFor(state);

  return (
    <section class={styles.card} data-tone={tone} aria-live="polite">
      {phase === 'live' ? <div class={styles.glow} aria-hidden="true" /> : null}
      <div class={styles.cardBody}>
        <div>
          <h2 class={styles.cardTitle}>{title}</h2>
          <p class={styles.cardDesc}>{desc}</p>
        </div>

        {sending ? (
          <>
            <div class={styles.metrics}>
              <div>
                <span class={styles.metricLabel}>전송 시간</span>
                <span class={styles.uptime}>{formatUptime(state.stats.uptimeSec)}</span>
              </div>
              <div class={styles.bitrate}>
                <span class={styles.metricLabel}>비트레이트</span>
                <span class={styles.bitrateValue}>{formatKbps(state.stats.bitrateKbps)}</span>
                <span class={styles.bitrateUnit}>kbps</span>
              </div>
            </div>
            <Sparkline values={history} />
            <div class={styles.metaRow}>
              <span>
                수신 <b>{state.ingest}</b>
              </span>
              <span>
                드롭 <b>{state.stats.droppedFrames.toLocaleString('ko-KR')}</b>
              </span>
            </div>
          </>
        ) : null}

        {errored ? (
          <div class={styles.alert} role="alert">
            <span>{reasonText(state.errorCode)}</span>
          </div>
        ) : null}

        {!sending ? (
          <>
            <div class={styles.metaRow}>
              <span>
                수신 <b>{state.ingest}</b>
              </span>
            </div>
            <button
              type="button"
              class={styles.unpairButton}
              onClick={onAskUnpair}
              disabled={busy}
              title={busy ? '방송 중에는 연결을 해제할 수 없어요' : undefined}
            >
              <Unlink size={13} strokeWidth={2} aria-hidden="true" />
              <span>연결 해제</span>
            </button>
          </>
        ) : null}
      </div>
    </section>
  );
}
