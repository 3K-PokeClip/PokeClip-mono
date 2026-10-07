import { BookmarkPlus, Clock, RefreshCw, RotateCw, Square, Unlink } from 'lucide-preact';
import { useEffect, useState } from 'preact/hooks';
import { formatKbps, formatUptime, sparklinePath } from '../lib/format';
import { statusView } from '../lib/status';
import type { BridgeState } from '../lib/types';
import styles from './dock.module.css';

const SPARK_POINTS = 60;

function useBitrateHistory(state: BridgeState): number[] {
  const [history, setHistory] = useState<number[]>([]);
  useEffect(() => {
    if (state.phase !== 'live') {
      setHistory([]);
      return;
    }
    // 첫 통계 전 0 샘플로 선이 바닥에서 튀지 않게 — 다시 붙었을 때는 전송 시간이 이어져 있어 시간으로는 못 가린다
    const kbps = state.stats.bitrateKbps;
    setHistory((h) => (h.length === 0 && kbps <= 0 ? h : [...h, kbps].slice(-SPARK_POINTS)));
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

// A4 「순간 표시」 — 핫키와 같은 대기열로 간다. 누른 뒤 2초는 플러그인도 연타로 거른다.
// 방송 중에 독에서 누르는 것은 사실상 이 버튼 하나라 카드 폭을 다 쓰고, 단축키는 버튼 안에 둔다.
const MARK_COOLDOWN_MS = 2000;

// live가 아니면(재연결 중) 무채색으로 그린다 — 카드 안의 색은 상태색 하나만 쓴다. 분홍은 전송 중 카드의 상태색이다.
function MarkButton({ hotkey, live, onMark }: { hotkey: string; live: boolean; onMark: () => void }) {
  const [cooling, setCooling] = useState(false);
  useEffect(() => {
    if (!cooling) return;
    const t = setTimeout(() => setCooling(false), MARK_COOLDOWN_MS);
    return () => clearTimeout(t);
  }, [cooling]);

  return (
    <>
      <button
        type="button"
        class={styles.markButton}
        data-tone={live ? undefined : 'neutral'}
        disabled={cooling}
        title={hotkey ? '다른 앱 단축키와 겹치면 OBS 설정 › 단축키에서 바꾸세요 — OBS가 뒤에 있어도 받아요.' : undefined}
        onClick={() => {
          setCooling(true);
          onMark();
        }}
      >
        <span class={styles.markLabel}>
          <BookmarkPlus size={15} strokeWidth={2.2} aria-hidden="true" />
          순간 표시
        </span>
        {hotkey ? <kbd class={styles.markKbd}>{hotkey}</kbd> : null}
      </button>
      {hotkey ? null : <p class={styles.markHint}>단축키 없음 · OBS 설정 › 단축키</p>}
    </>
  );
}

// 표시 개수는 수신 주소와 같은 정보 줄에 싣는다. 지난 방송 것이 남아 있어도 보여 준다.
// 보내는 중인 개수는 싣지 않는다 — 누른 결과는 토스트가 알린다(다시 보내는 중이면 그 사유와 함께).
function MarkCounts({ state }: { state: BridgeState }) {
  const { sent, failed } = state.marks;
  if (sent + failed === 0) return null;
  return (
    <>
      <span>
        표시 <b>{sent}</b>
      </span>
      {failed > 0 ? (
        <span>
          실패 <b>{failed}</b>
        </span>
      ) : null}
    </>
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
  // 끊겨 다시 붙는 동안에도 순간 표시는 받는다 — 누른 시각이 서버로 따로 간다
  const marking = live || phase === 'reconnecting';
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
          </>
        ) : null}

        {/* 「무슨 일 → 다음에 무엇을」을 한 상자로 읽히게 한다 — 재시도 중이면 원인 아래에 진행 문장이 붙는다.
            재시도 중의 상자는 급한 알림이 아니라 진행 상황이라 role="alert"를 달지 않는다(초가 줄 때마다 읽힌다). */}
        {view.alert || view.retrying ? (
          <div
            class={styles.alert}
            role={view.retrying ? undefined : 'alert'}
            data-tone={view.retrying ? 'warning' : undefined}
            data-stack={view.retrying ? '' : undefined}
          >
            {view.alert ? <span>{view.alert}</span> : null}
            {view.retrying ? (
              <span class={styles.alertNext} aria-live="off">
                <span class={styles.alertNextText}>
                  {/* 기다리는 동안은 시계, 실제로 접속할 때만 돈다 — 색은 문장과 같다(currentColor) */}
                  {view.canRetryNow ? (
                    <Clock size={12} strokeWidth={2.2} aria-hidden="true" />
                  ) : (
                    <RefreshCw size={12} strokeWidth={2.2} class={styles.spinIcon} aria-hidden="true" />
                  )}
                  {view.retryText}
                </span>
                <span class={styles.alertCount}>{view.retryCount}</span>
              </span>
            ) : null}
          </div>
        ) : null}

        {view.retrying ? (
          <>
            {/* 카드 폭을 똑같이 반씩 나눈다 — 높이·모서리·테두리는 「순간 표시」·「연결 해제」와 같다 */}
            <div class={styles.actionSplit}>
              <button
                type="button"
                class={styles.actionButton}
                data-variant="strong"
                disabled={!view.canRetryNow || sendBusy}
                onClick={fireSend}
              >
                <RotateCw size={13} strokeWidth={2.2} aria-hidden="true" />
                다시 시도
              </button>
              <button type="button" class={styles.actionButton} data-variant="neutral" disabled={stopBusy} onClick={fireStop}>
                <Square size={11} strokeWidth={2.4} aria-hidden="true" />
                재시도 중지
              </button>
            </div>
          </>
        ) : null}

        {view.canSendNow ? (
          <button type="button" class={styles.actionButton} data-variant="solid" data-block="" disabled={sendBusy} onClick={fireSend}>
            <RotateCw size={14} strokeWidth={2.2} aria-hidden="true" />
            다시 연결
          </button>
        ) : null}

        <div class={styles.metaRow}>
          <span>
            수신 <b>{state.ingest}</b>
          </span>
          {live ? (
            <span>
              드롭 <b>{state.stats.droppedFrames.toLocaleString('ko-KR')}</b>
            </span>
          ) : null}
          {marking ? <MarkCounts state={state} /> : null}
        </div>

        {marking ? <MarkButton hotkey={state.marks.hotkey} live={live} onMark={onMark} /> : null}

        {!marking ? (
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
