import { WifiOff } from 'lucide-preact';
import { useEffect, useState } from 'preact/hooks';
import { Checks } from './components/Checks';
import styles from './components/dock.module.css';
import { PairCard } from './components/PairCard';
import { Settings } from './components/Settings';
import { StatusCard } from './components/StatusCard';
import { Badge, type Tone } from './components/ui';
import type { Bridge } from './lib/bridge';
import { PHASE_LABEL, reasonText } from './lib/copy';
import type { BridgeState } from './lib/types';

function phaseTone(state: BridgeState): { tone: Tone; pulse: boolean; label: string } {
  if (!state.paired) return { tone: 'neutral', pulse: false, label: '연결 안 됨' };
  switch (state.phase) {
    case 'live':
      return { tone: 'point', pulse: true, label: 'LIVE' };
    case 'reconnecting':
    case 'starting':
    case 'stopping':
      return { tone: 'warning', pulse: true, label: PHASE_LABEL[state.phase] };
    case 'error':
      return { tone: 'danger', pulse: false, label: PHASE_LABEL.error };
    default:
      return { tone: 'success', pulse: false, label: '준비됨' };
  }
}

export function App({ bridge }: { bridge: Bridge }) {
  const [state, setState] = useState<BridgeState | null>(null);
  const [connected, setConnected] = useState(true);
  const [versions, setVersions] = useState({ plugin: '', obs: '' });
  const [fatal, setFatal] = useState('');
  const [actionError, setActionError] = useState('');

  useEffect(() => {
    bridge
      .hello()
      .then((h) => {
        setVersions({ plugin: h.pluginVersion, obs: h.obsVersion });
        setState(h.state);
      })
      .catch(() => setFatal(reasonText('unauthorized')));
    return bridge.subscribe(setState, setConnected);
  }, [bridge]);

  useEffect(() => {
    if (state) document.documentElement.dataset.theme = state.theme;
  }, [state?.theme]);

  if (fatal && !state) {
    return (
      <main class={styles.dock}>
        <div class={styles.banner} role="alert">
          <WifiOff size={14} aria-hidden /> {fatal}
        </div>
      </main>
    );
  }
  if (!state) return <main class={styles.dock} aria-busy="true" />;

  const pill = phaseTone(state);
  const locked = ['starting', 'live', 'reconnecting', 'stopping'].includes(state.phase);

  return (
    <main class={styles.dock}>
      <header class={styles.header}>
        <div class={styles.brand}>
          <span class={styles.mark} aria-hidden>
            P
          </span>
          <span class={styles.brandName}>PokeClip</span>
        </div>
        <Badge tone={pill.tone} dot pulse={pill.pulse}>
          {pill.label}
        </Badge>
      </header>

      {!connected ? (
        <div class={styles.banner} role="status">
          <WifiOff size={14} aria-hidden /> 플러그인과 연결이 끊겼어요. 다시 붙는 중…
        </div>
      ) : null}

      {state.paired ? (
        <StatusCard
          state={state}
          onUnpair={async () => {
            // CEF 독에서 window.alert는 막히거나 OBS를 멈추는 네이티브 창이 된다 — 인라인으로 알린다.
            const r = await bridge.unpair();
            setActionError(r.ok ? '' : reasonText(r.reason));
          }}
        />
      ) : (
        <PairCard
          onPair={(code) => bridge.pair(code)}
          notice={state.errorCode === 'no_key' && state.obsStreaming ? reasonText('no_key') : undefined}
        />
      )}

      {actionError ? (
        <div class={styles.banner} role="alert">
          {actionError}
        </div>
      ) : null}

      {state.paired ? <Checks state={state} /> : null}

      <Settings bridge={bridge} locked={locked} />

      <footer class={styles.footer}>
        <span>PokeClip for OBS {versions.plugin && `v${versions.plugin}`}</span>
        <span>{versions.obs && `OBS ${versions.obs}`}</span>
      </footer>
    </main>
  );
}
