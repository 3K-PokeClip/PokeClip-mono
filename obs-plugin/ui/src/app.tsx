import { CircleCheck, CircleDashed, CircleX, type LucideIcon, Radio, RefreshCw, WifiOff } from 'lucide-preact';
import { useCallback, useEffect, useRef, useState } from 'preact/hooks';
import { AudioAssignPrompt } from './components/AudioAssignPrompt';
import styles from './components/dock.module.css';
import { DockTabs } from './components/DockTabs';
import { Logo } from './components/Logo';
import { NotifyContext } from './components/notify';
import { PairCard } from './components/PairCard';
import { DevCard } from './components/Settings';
import { StatusCard } from './components/StatusCard';
import { LiveAnnouncer, Toast } from './components/Toast';
import { UnpairDialog } from './components/UnpairDialog';
import type { Bridge } from './lib/bridge';
import { markToast, noKeyToast, PHASE_LABEL, reasonText, type ToastTone } from './lib/copy';
import type { BridgeState } from './lib/types';

type BadgeTone = 'neutral' | 'success' | 'point' | 'warning' | 'danger';

// 개발자 모드 — 로고를 이 시간 안에 이만큼 누르면 켜지고, 한 번 더 그러면 꺼진다(이번 독 세션만). 켜면 「개발 서버 사용」
// (POK-267)·인증 서버·수신 서버가 보인다 — 연결 전엔 코드 입력 아래 칸, 연결 뒤엔 설정 탭 아래쪽. 따로 고르지 않았으면
// 개발 환경일 때 켜져 있다.
const DEV_UNLOCK_TAPS = 5;
const DEV_UNLOCK_WINDOW_MS = 3000;

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
  // 마크 결과·개발자 모드 켜고 끔·단축키와 설정 저장(NotifyContext)을 알리는 토스트 — 새 알림이 앞 알림을 갈아 끼운다
  const [notice, setNotice] = useState<{ tone: ToastTone; message: string } | null>(null);
  const notify = useCallback((tone: ToastTone, message: string) => setNotice({ tone, message }), []);
  const seenMarkSeq = useRef<number | null>(null);
  // null = 아직 정하지 않음 — 처음 받은 상태가 개발 환경이면 켠다(아래 효과가 고정한다). devFocus — 켠 순간 그 칸으로 데려가라는
  // 한 번짜리 신호. 데려간 쪽(SettingsPanel)이 내린다 — 남겨 두면 연결·재연결로 화면이 새로 붙을 때마다 다시 데려간다.
  const [devMode, setDevMode] = useState<boolean | null>(null);
  const [devFocus, setDevFocus] = useState(false);
  const devOn = devMode ?? (state?.serverEnv === 'dev');
  const clearDevFocus = useCallback(() => setDevFocus(false), []);
  const brandTaps = useRef<number[]>([]);
  const tapBrand = () => {
    const now = Date.now();
    const taps = [...brandTaps.current.filter((t) => now - t < DEV_UNLOCK_WINDOW_MS), now];
    if (taps.length < DEV_UNLOCK_TAPS) {
      brandTaps.current = taps;
      return;
    }
    brandTaps.current = [];
    const next = !devOn;
    setDevMode(next);
    setDevFocus(next);
    setNotice({ tone: 'success', message: next ? '개발자 모드를 켰어요.' : '개발자 모드를 껐어요.' });
  };

  // 개발자 모드를 처음 상태로 고정한다 — 서버 환경을 따라가게 두면 개발 빌드에서 「개발 서버 사용」을 끄는 순간 그 스위치가 든
  // 개발자 칸이 통째로 사라져, 되돌리려면 안내 없는 로고 5번만 남는다.
  useEffect(() => {
    if (state && devMode === null) setDevMode(state.serverEnv === 'dev');
  }, [state?.serverEnv, devMode]);

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
    const markNotice = markToast(state.marks);
    if (markNotice) setNotice(markNotice);
  }, [state?.marks.seq]);

  useEffect(() => {
    if (!notice) return;
    const t = setTimeout(() => setNotice(null), 4000);
    return () => clearTimeout(t);
  }, [notice]);

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
    setNotice({ tone: 'warning', message: reasonText(r.reason) });
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
  const noKeyText = noKeyToast(state.checks.gop2s === true);

  return (
    <NotifyContext.Provider value={notify}>
      <main class={styles.dock}>
        <header class={styles.header}>
          <div class={styles.brand} onClick={tapBrand}>
            <Logo className={styles.logo} />
            <span class={styles.brandName}>PokeClip</span>
            {state.serverEnv === 'dev' ? (
              <span class={styles.envTag} title={`${state.apiBase} · ${state.ingest}`}>
                개발 서버
              </span>
            ) : null}
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
          <PairCard onPair={(code) => bridge.pair(code)} onPasteCode={() => bridge.pasteCode()} />
        )}

        {state.paired && state.audio.prompt ? <AudioAssignPrompt state={state} bridge={bridge} /> : null}

        {/* 연결되면 송출 점검·오디오 트랙·설정(맨 위에 순간 표시 단축키)을 탭 하나씩. 연결 전엔 코드 입력만 — 개발자 모드일
            때만 그 아래에 서버(환경·인증·수신)를 고르는 칸을 둔다. */}
        {state.paired ? (
          <DockTabs
            state={state}
            bridge={bridge}
            locked={locked}
            devVisible={devOn}
            focusDev={devFocus}
            onDevFocused={clearDevFocus}
          />
        ) : devOn ? (
          <DevCard
            bridge={bridge}
            locked={locked}
            autoAssign={state.audio.autoAssign}
            serverEnv={state.serverEnv}
            apiBase={state.apiBase}
            paired={false}
            focusDev={devFocus}
            onDevFocused={clearDevFocus}
          />
        ) : null}

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

        {/* 지나가는 알림(저장·마크·개발자 모드)이 먼저 — 4초 뒤 사라지면 닫을 때까지 남는 no_key 안내가 다시 보인다.
            no_key가 먼저면 미연결·방송 중에 개발자 칸을 저장해도 알림이 가려진 채 사라진다. */}
        {notice ? (
          <Toast tone={notice.tone} message={notice.message} onDismiss={() => setNotice(null)} />
        ) : showNoKeyToast ? (
          <Toast message={noKeyText} onDismiss={() => setToastDismissed(true)} />
        ) : null}
        <LiveAnnouncer message={notice ? notice.message : showNoKeyToast ? noKeyText : ''} nonce={notice ?? 'no_key'} />
      </main>
    </NotifyContext.Provider>
  );
}
