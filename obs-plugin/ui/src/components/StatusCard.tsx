import { BookmarkPlus, Unlink } from 'lucide-preact';
import { useEffect, useState } from 'preact/hooks';
import { KEY_SUSPECT_HINT } from '../lib/copy';
import { formatKbps, formatUptime, sparklinePath } from '../lib/format';
import { statusView } from '../lib/status';
import type { BridgeState } from '../lib/types';
import styles from './dock.module.css';
import { Button } from './ui';

const SPARK_POINTS = 60;

function useBitrateHistory(state: BridgeState): number[] {
  const [history, setHistory] = useState<number[]>([]);
  useEffect(() => {
    if (state.phase !== 'live') {
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

// A4 「지금 이 순간 표시」 — 핫키와 같은 대기열로 간다. 누른 뒤 2초는 플러그인도 연타로 거른다.
const MARK_COOLDOWN_MS = 2000;

function MarkRow({ state, onMark }: { state: BridgeState; onMark: () => void }) {
  const [cooling, setCooling] = useState(false);
  useEffect(() => {
    if (!cooling) return;
    const t = setTimeout(() => setCooling(false), MARK_COOLDOWN_MS);
    return () => clearTimeout(t);
  }, [cooling]);
  const { hotkey, sent, pending, failed } = state.marks;

  return (
    <div class={styles.markBox}>
      <div class={styles.markRow}>
        <button
          type="button"
          class={styles.markButton}
          disabled={cooling}
          onClick={() => {
            setCooling(true);
            onMark();
          }}
        >
          <BookmarkPlus size={13} strokeWidth={2.2} aria-hidden="true" />
          <span>지금 이 순간 표시</span>
        </button>
        <span
          class={styles.markHint}
          title={hotkey ? '다른 앱 단축키와 겹치면 OBS 설정 › 단축키에서 바꾸세요 — OBS가 뒤에 있어도 받아요.' : undefined}
        >
          {hotkey ? (
            <>
              단축키 <kbd class={styles.kbd}>{hotkey}</kbd>
            </>
          ) : (
            '단축키 없음 · OBS 설정 › 단축키'
          )}
        </span>
      </div>
      {sent + pending + failed > 0 ? (
        <div class={styles.metaRow}>
          <span>
            표시 <b>{sent}</b>
          </span>
          {pending > 0 ? (
            <span>
              보내는 중 <b>{pending}</b>
            </span>
          ) : null}
          {failed > 0 ? (
            <span>
              실패 <b>{failed}</b>
            </span>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

// A5 — 다음 재시도까지 남은 초를 세려고 1초마다 다시 그린다. 플러그인은 다음 시도 시각만 주고, 세는 것은 독이 한다.
function useSecondTick(active: boolean) {
  const [, setTick] = useState(0);
  useEffect(() => {
    if (!active) return;
    const t = setInterval(() => setTick((n) => n + 1), 1000);
    return () => clearInterval(t);
  }, [active]);
}

// 요청이 돌아올 때까지 버튼을 잠근다 — 두 번 눌러 두 번째가 거절되는 일이 없게.
function useAction(run: () => Promise<void>): [boolean, () => void] {
  const [busy, setBusy] = useState(false);
  const fire = () => {
    if (busy) return;
    setBusy(true);
    void run().finally(() => setBusy(false));
  };
  return [busy, fire];
}

export function StatusCard({
  state,
  onAskUnpair,
  onMark,
  onSendNow,
  onStopRetry,
}: {
  state: BridgeState;
  onAskUnpair: () => void;
  onMark: () => void;
  onSendNow: () => Promise<void>;
  onStopRetry: () => Promise<void>;
}) {
  const history = useBitrateHistory(state);
  useSecondTick(state.retry.nextAt > 0);
  const [sendBusy, fireSend] = useAction(onSendNow);
  const [stopBusy, fireStop] = useAction(onStopRetry);
  const { phase } = state;
  const live = phase === 'live';
  const reconnecting = phase === 'reconnecting';
  const busy = phase === 'starting' || phase === 'stopping';
  const view = statusView(state, Date.now());

  return (
    <section class={styles.card} data-tone={view.tone} aria-live="polite">
      {live ? <div class={styles.glow} aria-hidden="true" /> : null}
      <div class={styles.cardBody}>
        <div>
          <h2 class={styles.cardTitle}>{view.title}</h2>
          <p class={styles.cardDesc}>{view.desc}</p>
        </div>

        {live ? (
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

        {view.alert ? (
          <div class={styles.alert} role="alert" data-tone={view.retrying ? 'warning' : undefined}>
            <span>{view.alert}</span>
          </div>
        ) : null}

        {view.retrying ? (
          <div class={styles.retryBox}>
            {/* 카드 전체가 aria-live라 초가 줄 때마다 읽지 않게 이 줄만 끈다 */}
            <span class={styles.retryLine} aria-live="off">
              {view.retryLine}
            </span>
            {view.keyHint ? <p class={styles.retryHint}>{KEY_SUSPECT_HINT}</p> : null}
            <div class={styles.actionRow}>
              <Button size="sm" variant="soft" loading={sendBusy} disabled={!view.canRetryNow} onClick={fireSend}>
                지금 다시 시도
              </Button>
              <Button size="sm" variant="ghost" loading={stopBusy} onClick={fireStop}>
                재시도 멈추기
              </Button>
            </div>
          </div>
        ) : null}

        {view.canSendNow ? (
          <div class={styles.actionRow}>
            <Button size="sm" loading={sendBusy} onClick={fireSend}>
              지금 보내기
            </Button>
          </div>
        ) : null}

        {/* 끊겨 다시 붙는 동안에도 순간 표시는 받는다 — 누른 시각이 서버로 따로 간다 */}
        {live || reconnecting ? <MarkRow state={state} onMark={onMark} /> : null}

        {!live ? (
          <div class={styles.metaRow}>
            <span>
              수신 <b>{state.ingest}</b>
            </span>
          </div>
        ) : null}

        {!live && !reconnecting ? (
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
        ) : null}
      </div>
    </section>
  );
}
