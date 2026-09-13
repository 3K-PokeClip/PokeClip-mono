import { AlertTriangle, CircleCheck, Radio, RefreshCw, XCircle } from 'lucide-preact';
import { useEffect, useState } from 'preact/hooks';
import { reasonText } from '../lib/copy';
import { formatKbps, formatUptime, sparklinePath } from '../lib/format';
import type { BridgeState } from '../lib/types';
import styles from './dock.module.css';
import { Button } from './ui';

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
    <svg class={styles.sparkline} viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none" aria-hidden>
      <defs>
        <linearGradient id="pc-spark-fill" x1="0" x2="0" y1="0" y2="1">
          <stop offset="0%" stop-color="var(--pc-color-point)" stop-opacity="0.28" />
          <stop offset="100%" stop-color="var(--pc-color-point)" stop-opacity="0" />
        </linearGradient>
      </defs>
      <path class={styles.sparkFill} d={`${line} L${w} ${h} L0 ${h} Z`} />
      <path class={styles.sparkStroke} d={line} />
    </svg>
  );
}

export function StatusCard({
  state,
  onUnpair,
}: {
  state: BridgeState;
  onUnpair: () => Promise<void>;
}) {
  const history = useBitrateHistory(state);
  const [unpairing, setUnpairing] = useState(false);
  const { phase } = state;
  const sending = phase === 'live' || phase === 'reconnecting';
  const busy = sending || phase === 'starting' || phase === 'stopping';

  if (sending) {
    const reconnecting = phase === 'reconnecting';
    return (
      <section class={styles.card} data-tone={reconnecting ? 'warning' : 'live'} aria-live="polite">
        <div class={styles.cardHead}>
          <div class={styles.icon} data-tone={reconnecting ? 'warning' : 'point'}>
            {reconnecting ? <RefreshCw size={17} strokeWidth={2} aria-hidden /> : <Radio size={17} strokeWidth={2} aria-hidden />}
          </div>
          <div>
            <h2 class={styles.cardTitle}>{reconnecting ? '재연결 중' : 'PokeClip으로 전송 중'}</h2>
            <p class={styles.cardDesc}>
              {reconnecting ? '회선이 끊겨 자동으로 다시 붙는 중이에요.' : '본방과 같은 인코더로 함께 보내고 있어요.'}
            </p>
          </div>
        </div>
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
      </section>
    );
  }

  const errored = phase === 'error' && state.errorCode;
  const tone = errored ? 'danger' : 'ready';
  const title =
    phase === 'starting' ? '연결 중' : phase === 'stopping' ? '정지 중' : errored ? '전송이 멈췄어요' : '준비됐어요';
  const desc = errored
    ? 'OBS에서 방송을 다시 시작하면 다시 시도해요.'
    : 'OBS에서 방송을 시작하면 PokeClip으로 함께 전송돼요.';

  return (
    <section class={styles.card} data-tone={tone} aria-live="polite">
      <div class={styles.cardHead}>
        <div class={styles.icon} data-tone={errored ? 'danger' : 'success'}>
          {errored ? <XCircle size={17} strokeWidth={2} aria-hidden /> : <CircleCheck size={17} strokeWidth={2} aria-hidden />}
        </div>
        <div>
          <h2 class={styles.cardTitle}>{title}</h2>
          <p class={styles.cardDesc}>{desc}</p>
        </div>
      </div>

      {errored ? (
        <div class={styles.alert} data-tone="danger" role="alert">
          <AlertTriangle size={14} strokeWidth={2.2} aria-hidden />
          <span>{reasonText(state.errorCode)}</span>
        </div>
      ) : null}

      <div class={styles.rowBetween}>
        <span class={styles.keyChip}>
          스트림 키 <code>••••{state.keyHint}</code>
        </span>
        <Button
          variant="ghost"
          size="sm"
          disabled={busy}
          loading={unpairing}
          title={busy ? '방송 중에는 연결을 해제할 수 없어요' : undefined}
          onClick={async () => {
            setUnpairing(true);
            await onUnpair();
            setUnpairing(false);
          }}
        >
          연결 해제
        </Button>
      </div>
      <div class={styles.metaRow}>
        <span>
          수신 <b>{state.ingest}</b>
        </span>
      </div>
    </section>
  );
}
