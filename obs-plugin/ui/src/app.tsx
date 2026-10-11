import { CircleCheck, CircleDashed, CircleX, type LucideIcon, Radio, RefreshCw, WifiOff } from 'lucide-preact';
import { useCallback, useEffect, useRef, useState } from 'preact/hooks';
import { AudioAssignPrompt } from './components/AudioAssignPrompt';
import { AudioTracks } from './components/AudioTracks';
import { Checks } from './components/Checks';
import styles from './components/dock.module.css';
import { Logo } from './components/Logo';
import { PairCard } from './components/PairCard';
import { Settings } from './components/Settings';
import { StatusCard } from './components/StatusCard';
import { Toast } from './components/Toast';
import { UnpairDialog } from './components/UnpairDialog';
import type { Bridge } from './lib/bridge';
import { markToast, noKeyToast, PHASE_LABEL, reasonText, type ToastTone } from './lib/copy';
import type { BridgeState } from './lib/types';

type BadgeTone = 'neutral' | 'success' | 'point' | 'warning' | 'danger';

function badgeFor(state: BridgeState): { tone: BadgeTone; label: string; Icon: LucideIcon } {
  if (!state.paired) return { tone: 'neutral', label: '연결 안 됨', Icon: CircleDashed };
  switch (state.phase) {
    case 'live':
      return { tone: 'point', label: 'LIVE', Icon: Radio };
    case 'reconnecting':
    case 'starting':
    case 'stopping':
      return { tone: 'warning', label: PHASE_LABEL[state.phase], Icon: RefreshCw };
    case 'error':
      if (state.errorCode) return { tone: 'danger', label: PHASE_LABEL.error, Icon: CircleX };
      return { tone: 'success', label: '준비됨', Icon: CircleCheck };
    default:
      // 본방은 나가는데 우리만 안 보내고 있다(방송 중에 페어링함 등) — 「준비됨」이 아니다
      if (state.canSendNow) return { tone: 'warning', label: '전송 꺼짐', Icon: CircleDashed };
      return { tone: 'success', label: '준비됨', Icon: CircleCheck };
  }
}

export function App({ bridge }: { bridge: Bridge }) {
  const [state, setState] = useState<BridgeState | null>(null);
  const [connected, setConnected] = useState(true);
  const [version, setVersion] = useState('');
  const [fatal, setFatal] = useState('');
  const [confirmUnpair, setConfirmUnpair] = useState(false);
  const [actionError, setActionError] = useState('');
  const [toastDismissed, setToastDismissed] = useState(false);
  const [markNotice, setMarkNotice] = useState<{ tone: ToastTone; message: string } | null>(null);
  const seenMarkSeq = useRef<number | null>(null);

  useEffect(() => {
    bridge
      .hello()
      .then((h) => {
        setVersion(h.pluginVersion);
        // 구독(SSE) 첫 프레임이 먼저 왔으면 그쪽이 더 새 상태다 — 옛 스냅샷으로 되돌리면 화면이 한 번 뒤로 가고
        // 마크 seq가 낮아져 지난 마크 토스트가 다시 뜬다.
        setState((prev) => (prev && prev.version >= h.state.version ? prev : h.state));
      })
      .catch(() => setFatal(reasonText('unauthorized')));
    return bridge.subscribe(setState, setConnected);
  }, [bridge]);

  useEffect(() => {
    if (state) document.documentElement.dataset.theme = state.theme;
  }, [state?.theme]);

  // 같은 방송에서 닫은 토스트는 다시 띄우지 않는다. 방송이 끝나면 다음 방송을 위해 되돌린다.
  useEffect(() => {
    if (!state?.obsStreaming) setToastDismissed(false);
  }, [state?.obsStreaming]);

  // 마크 결과는 seq가 오를 때 한 번만 알린다. 처음 붙을 때 남아 있던 지난 결과는 띄우지 않는다.
  useEffect(() => {
    if (!state) return;
    const seq = state.marks.seq;
    if (seenMarkSeq.current === null || seq < seenMarkSeq.current) {
      seenMarkSeq.current = seq; // 첫 상태 · 플러그인 재시작
      return;
    }
    if (seq === seenMarkSeq.current) return;
    seenMarkSeq.current = seq;
    const notice = markToast(state.marks);
    if (notice) setMarkNotice(notice);
  }, [state?.marks.seq]);

  useEffect(() => {
    if (!markNotice) return;
    const t = setTimeout(() => setMarkNotice(null), 4000);
    return () => clearTimeout(t);
  }, [markNotice]);

  // 동작 실패 안내는 그때의 상태에 대한 것이다 — 단계가 바뀌면 지운다.
  useEffect(() => {
    setActionError('');
  }, [state?.phase]);

  const closeDialog = useCallback(() => setConfirmUnpair(false), []);
  // A5 — 받아들여진 뒤의 결과(붙었다·또 실패했다)는 상태로 온다. 여기서는 요청이 거절된 경우만 알린다.
  const sendNow = useCallback(async () => {
    const r = await bridge.sendNow();
    setActionError(r.ok ? '' : reasonText(r.reason));
  }, [bridge]);
  const stopRetry = useCallback(async () => {
    const r = await bridge.stopRetry();
    setActionError(r.ok ? '' : reasonText(r.reason));
  }, [bridge]);
  const mark = useCallback(async () => {
    // 거절(409 — 방송 아님 등)은 플러그인이 state.marks로 알린다. 여기서는 그 밖의 실패 — 연타(429),
    // 브리지 인증·서버 오류, 브리지에 닿지도 못한 경우(bridge_unreachable — 브리지 클라이언트가 바꿔 준다) — 만 알린다.
    const r = await bridge.mark();
    if (r.ok || r.status === 409) return;
    setMarkNotice({ tone: 'warning', message: reasonText(r.reason) });
  }, [bridge]);

  if (fatal && !state) {
    return (
      <main class={styles.dock}>
        <div class={styles.banner} role="alert">
          <WifiOff size={14} aria-hidden="true" /> {fatal}
        </div>
      </main>
    );
  }
  if (!state) return <main class={styles.dock} aria-busy="true" />;

  const badge = badgeFor(state);
  const locked = ['starting', 'live', 'reconnecting', 'stopping'].includes(state.phase);
  const showNoKeyToast = !state.paired && state.errorCode === 'no_key' && state.obsStreaming && !toastDismissed;

  return (
    <main class={styles.dock}>
      <header class={styles.header}>
        <div class={styles.brand}>
          <Logo className={styles.logo} />
          <span class={styles.brandName}>PokeClip</span>
        </div>
        <span class={styles.badge} data-tone={badge.tone}>
          <badge.Icon size={13} strokeWidth={2.2} aria-hidden="true" />
          {badge.label}
        </span>
      </header>

      {!connected ? (
        <div class={styles.banner} role="status">
          <WifiOff size={14} aria-hidden="true" /> 플러그인과 연결이 끊겼어요. 다시 붙는 중…
        </div>
      ) : null}

      {actionError ? (
        <div class={styles.banner} role="alert">
          {actionError}
        </div>
      ) : null}

      {state.paired ? (
        <StatusCard
          state={state}
          onAskUnpair={() => {
            setActionError('');
            setConfirmUnpair(true);
          }}
          onMark={mark}
          onSendNow={sendNow}
          onStopRetry={stopRetry}
        />
      ) : (
        <PairCard onPair={(code) => bridge.pair(code)} />
      )}

      {state.paired && state.audio.prompt ? <AudioAssignPrompt state={state} bridge={bridge} /> : null}

      {state.paired ? <Checks state={state} /> : null}

      {state.paired ? <AudioTracks state={state} bridge={bridge} /> : null}

      <Settings bridge={bridge} locked={locked} autoAssign={state.audio.autoAssign} />

      <footer class={styles.footer}>
        <span>PokeClip for OBS {version && `v${version}`}</span>
      </footer>

      {confirmUnpair ? (
        <UnpairDialog
          onCancel={closeDialog}
          onConfirm={async () => {
            // CEF 독에서 window.alert는 막히거나 OBS를 멈추는 네이티브 창이 된다 — 인라인으로 알린다.
            const r = await bridge.unpair();
            setConfirmUnpair(false);
            setActionError(r.ok ? '' : reasonText(r.reason));
          }}
        />
      ) : null}

      {showNoKeyToast ? (
        <Toast message={noKeyToast(state.checks.gop2s === true)} onDismiss={() => setToastDismissed(true)} />
      ) : markNotice ? (
        <Toast tone={markNotice.tone} message={markNotice.message} onDismiss={() => setMarkNotice(null)} />
      ) : null}
    </main>
  );
}
