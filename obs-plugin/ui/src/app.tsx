import { CircleCheck, CircleDashed, CircleX, type LucideIcon, Radio, RefreshCw, WifiOff } from 'lucide-preact';
import { useCallback, useEffect, useState } from 'preact/hooks';
import { Checks } from './components/Checks';
import styles from './components/dock.module.css';
import { Logo } from './components/Logo';
import { PairCard } from './components/PairCard';
import { Settings } from './components/Settings';
import { StatusCard } from './components/StatusCard';
import { Toast } from './components/Toast';
import { UnpairDialog } from './components/UnpairDialog';
import type { Bridge } from './lib/bridge';
import { PHASE_LABEL, reasonText } from './lib/copy';
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

  useEffect(() => {
    bridge
      .hello()
      .then((h) => {
        setVersion(h.pluginVersion);
        setState(h.state);
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

  const closeDialog = useCallback(() => setConfirmUnpair(false), []);

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
        />
      ) : (
        <PairCard onPair={(code) => bridge.pair(code)} />
      )}

      {state.paired ? <Checks state={state} /> : null}

      <Settings bridge={bridge} locked={locked} />

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

      {showNoKeyToast ? <Toast message={reasonText('no_key')} onDismiss={() => setToastDismissed(true)} /> : null}
    </main>
  );
}
