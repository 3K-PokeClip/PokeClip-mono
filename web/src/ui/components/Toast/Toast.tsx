import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type FocusEvent,
  type PointerEvent,
  type ReactNode,
} from 'react';
import { OUTSIDE_POINTER_EXEMPT_ATTR } from '../../primitives/DismissableLayer';
import { Portal } from '../../primitives/Portal';
import { Button } from '../Button';
import { Progress } from '../Progress';
import { Spinner } from '../Spinner';
import styles from './Toast.module.css';

export type ToastTone = 'success' | 'info' | 'warning' | 'error' | 'progress';

export interface ToastAction {
  label: string;
  onClick: () => void;
}

interface ToastBase {
  tone: ToastTone;
  title: ReactNode;
  description?: ReactNode;
  /** 톤 기본값을 덮어쓴다. 0이면 자동으로 닫히지 않는다. */
  duration?: number;
  /** 같은 키가 연속으로 오면 새로 쌓지 않고 최신 토스트를 갱신한다. 기본값은 톤. */
  dedupeKey?: string;
  /** `progress` 톤 전용 0~100. 주면 본문 아래에 진행 바를 함께 그린다. */
  progress?: number;
}

/**
 * 액션은 최대 1개다 — `action`과 `undo`는 함께 쓸 수 없고, `destructive`로 표시한
 * 결과에는 `undo`를 붙일 수 없다. 되돌릴 수 없는 일에 되돌리기 버튼을 붙이면
 * 거짓말이 되기 때문이다(ADR-044). 삭제·연동 해제 같은 파괴적 동작은 모달로
 * 확인받고 토스트는 결과만 알린다.
 */
export type ToastOptions =
  | (ToastBase & { action?: ToastAction; undo?: never; destructive?: boolean })
  | (ToastBase & { undo: () => void; action?: never; destructive?: never });

/** 이미 떠 있는 토스트의 내용을 바꾼다. 톤이나 지속을 주면 타이머를 다시 건다. */
export interface ToastPatch {
  tone?: ToastTone;
  title?: ReactNode;
  description?: ReactNode;
  /** 진행률을 바꾼다. `null`이면 진행 바를 뗀다. */
  progress?: number | null;
  duration?: number;
  /** 액션을 갈아 끼운다. `null`이면 뗀다 — 끝난 일에 「취소」가 남지 않게. */
  action?: ToastAction | null;
}

/**
 * 톤별 자동 닫힘(ms). 0은 "자동으로 닫히지 않는다"는 뜻이다 — 오류는 조치를
 * 요구하고 진행 중은 아직 끝나지 않아서, 5초 뒤 지우면 사용자가 상태를 잃는다.
 */
const TONE_DURATION: Record<ToastTone, number> = {
  success: 5000,
  info: 5000,
  warning: 7000,
  error: 0,
  progress: 0,
};

/** 못 보는 사용자에게 읽어 주는 방식 — 조치가 필요한 톤만 하던 말을 끊는다. */
const TONE_ROLE: Record<ToastTone, 'status' | 'alert'> = {
  success: 'status',
  info: 'status',
  progress: 'status',
  warning: 'alert',
  error: 'alert',
};

/**
 * 동시에 보이는 개수. 넘치면 「이전 알림 N개 더」로 접힌다.
 *
 * 초과분을 버리지 않는 이유 — 접기는 "지운 게 아니라 숨긴 것"이라(ADR-044) 개수가
 * 사실이어야 한다. 보관 상한을 두면 라벨이 실제보다 적게 세게 된다.
 *
 * 대신 목록은 자랄 수 있다. dedupe는 **직전 토스트하고만** 비교하므로 오류와
 * 진행처럼 자동으로 안 닫히는 톤이 번갈아 들어오면 합쳐지지 않고 쌓인다. 렌더는
 * 3개로 묶여 있고 항목 하나가 가벼워 받아들이는 비용이지, 안 자란다는 뜻이 아니다.
 */
const MAX_VISIBLE = 3;
/**
 * 렌더 트리에 남기는 개수. 접힌 카드를 마운트한 채 두는 건 잠깐 밀렸다 돌아오는
 * 토스트의 타이머 바와 낭독을 지키려는 것이지 전부 들고 있으려는 게 아니다.
 * 보이는 3개 + 완충 3개면 그 목적에 충분하고, 그보다 아래는 DOM에서 뺀다 —
 * 자동으로 안 닫히는 톤이 쌓이면 카드 수백 개가 숨은 채 남는다.
 *
 * 목록(listRef) 자체에는 상한을 두지 않는다. 버리면 「이전 알림 N개 더」가 실제보다
 * 적게 세는데, 접기는 "지운 게 아니라 숨긴 것"이라(ADR-044) 개수는 사실이어야 한다.
 */
const MAX_MOUNTED = 6;

interface ToastItem extends ToastBase {
  id: string;
  /** 갱신될 때마다 오른다. 타이머 바를 리마운트해 처음부터 다시 흐르게 하는 키. */
  version: number;
  action?: ToastAction;
}

interface TimerState {
  /** 남은 시간(ms). 정지할 때마다 흘러간 만큼 깎아서 되돌려 담는다. */
  remaining: number;
  startedAt: number;
  handle: number | null;
}

interface ToastContextValue {
  toast: (options: ToastOptions) => string;
  update: (id: string, patch: ToastPatch) => void;
  dismiss: (id: string) => void;
  dismissAll: () => void;
}
const ToastContext = createContext<ToastContextValue | null>(null);

function resolveDuration(item: Pick<ToastBase, 'tone' | 'duration'>): number {
  return item.duration ?? TONE_DURATION[item.tone];
}

/**
 * 연속으로 온 토스트를 합칠지 가르는 키. `null`이면 절대 합치지 않는다.
 *
 * 지정이 없으면 톤이 그 역할을 하되 **`progress`는 예외**다. 진행 토스트는 저마다
 * 다른 작업이고 호출자가 id를 들고 갱신하는데, 톤으로 합치면 동시에 도는 업로드
 * 둘이 한 카드와 한 id를 공유해 서로의 진행률을 덮어쓴다. 합치려면 같은 작업임을
 * `dedupeKey`로 밝혀야 한다.
 */
function mergeKey(item: Pick<ToastBase, 'tone' | 'dedupeKey' | 'title'>): string | null {
  if (item.dedupeKey !== undefined) return item.dedupeKey;
  if (item.tone === 'progress') return null;
  // 톤만으로 합치면 **내용이 다른** 알림도 앞 것을 덮어쓴다. 오류는 자동으로 닫히지
  // 않아 계속 최신 자리에 남으므로, 같은 화면의 다른 오류 안내가 통째로 사라진다.
  // ADR-044의 "같은 종류가 연속으로 발생하면 갱신"은 같은 사건의 반복을 겨눈 규칙이라
  // 제목까지 같을 때만 합친다. 그래도 합치려면 dedupeKey로 밝힌다.
  return typeof item.title === 'string' ? `${item.tone}:${item.title}` : null;
}

/** `undo`를 액션 자리 하나로 정규화한다 — 둘은 같은 자리를 쓰고 최대 1개다. */
function normalize(options: ToastOptions): Omit<ToastItem, 'id' | 'version'> {
  // `destructive`는 되돌리기를 컴파일 단계에서 막는 타입 표식일 뿐이라 런타임까지
  // 싣지 않는다 — 렌더·톤·닫힘 어디에도 쓰이지 않는 상태를 남기지 않는다.
  const { undo, action, destructive, ...base } = options as ToastBase & {
    undo?: () => void;
    action?: ToastAction;
    destructive?: boolean;
  };
  void destructive;
  return {
    ...base,
    action: undo ? { label: '되돌리기', onClick: undo } : action,
  };
}

function ToneIcon({ tone }: { tone: Exclude<ToastTone, 'progress'> }) {
  const common = {
    viewBox: '0 0 24 24',
    width: 18,
    height: 18,
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: 2.1,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
    'aria-hidden': true,
    className: styles.icon,
  };
  switch (tone) {
    case 'success':
      return (
        <svg {...common}>
          <path d="M5 12.5l4.5 4.5L19 7.5" />
        </svg>
      );
    case 'error':
      return (
        <svg {...common}>
          <circle cx="12" cy="12" r="9.1" strokeWidth="1.9" />
          <path d="M12 7.2v6.2M12 16.9h.01" />
        </svg>
      );
    case 'warning':
      return (
        <svg {...common}>
          <path d="M10.3 3.9 2.6 17.4A2 2 0 0 0 4.3 20.4h15.4a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z" />
          <path d="M12 9.4v4.3M12 17h.01" />
        </svg>
      );
    case 'info':
      return (
        <svg {...common}>
          <path d="M13 3L5 13.5h6L11 21l8-10.5h-6L13 3z" />
        </svg>
      );
  }
}

function ToastCard({
  item,
  depth,
  collapsed,
  paused,
  onClose,
}: {
  item: ToastItem;
  depth: number;
  collapsed: boolean;
  paused: boolean;
  onClose: () => void;
}) {
  const role = TONE_ROLE[item.tone];
  const duration = resolveDuration(item);
  return (
    <div
      className={styles.toast}
      data-toast-id={item.id}
      data-collapsed={collapsed || undefined}
      data-tone={item.tone}
      style={{ '--pc-toast-depth': depth } as CSSProperties}
      role={role}
      aria-live={role === 'alert' ? 'assertive' : 'polite'}
      // 진행 토스트는 내용이 계속 바뀐다. atomic이면 갱신마다 제목+보조설명 전체가
      // 다시 읽혀 다른 안내를 덮는다 — 바뀐 부분만 읽히게 둔다.
      aria-atomic={item.tone === 'progress' ? 'false' : 'true'}
    >
      <div className={styles.row}>
        {item.tone === 'progress' ? (
          <Spinner size="sm" className={styles.icon} aria-hidden="true" />
        ) : (
          <ToneIcon tone={item.tone} />
        )}
        <div className={styles.body}>
          <div className={styles.title}>{item.title}</div>
          {item.description != null ? (
            <div
              className={styles.description}
              // 한 줄로 자르는 대신 전문을 볼 수단은 남긴다.
              title={typeof item.description === 'string' ? item.description : undefined}
            >
              {item.description}
            </div>
          ) : null}
        </div>
        {item.action ? (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              // 누른 뒤 닫는다 — 안 닫으면 되돌리기를 두 번 눌러 같은 동작이 두 번
              // 실행되고, 되돌린 뒤에도 원래 결과 문구가 사실인 척 남는다.
              item.action?.onClick();
              onClose();
            }}
          >
            {item.action.label}
          </Button>
        ) : null}
        <button type="button" className={styles.close} aria-label="닫기" onClick={onClose}>
          <svg width="13" height="13" viewBox="0 0 24 24" aria-hidden="true">
            <path
              d="M6 6l12 12M18 6L6 18"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              fill="none"
            />
          </svg>
        </button>
      </div>
      {item.tone === 'progress' && item.progress != null ? (
        <div className={styles.progress}>
          <Progress value={item.progress} size="sm" label="진행률" />
        </div>
      ) : null}
      {duration > 0 ? (
        <div className={styles.timer} aria-hidden="true">
          <div
            key={item.version}
            className={styles.timerFill}
            style={{
              animationDuration: `${duration}ms`,
              animationPlayState: paused ? 'paused' : 'running',
            }}
          />
        </div>
      ) : null}
    </div>
  );
}

export interface ToastProviderProps {
  children: ReactNode;
}

/** 전역 피드백 표면. 명령형 `toast()` API와 우측 하단 스택을 함께 제공한다. */
export function ToastProvider({ children }: ToastProviderProps) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const [paused, setPaused] = useState(false);

  // 목록의 정본은 ref다 — 같은 틱에 연달아 들어오는 토스트가 서로의 결과를 못 보고
  // 갱신 대신 새로 쌓이는 일을 막는다. state는 그 사본이다.
  const listRef = useRef<ToastItem[]>([]);
  const timers = useRef(new Map<string, TimerState>());
  const counter = useRef(0);
  // 어떤 카드가 정지를 붙들고 있는지 id로 기억한다. 불리언으로 두면 그 카드가
  // 사라질 때 해제 이벤트가 오지 않아(제거된 노드는 React 트리에서도 빠진다)
  // 정지가 영영 안 풀린다.
  const hoveredId = useRef<string | null>(null);
  const focusedId = useRef<string | null>(null);
  const pausedRef = useRef(false);
  const viewportRef = useRef<HTMLDivElement>(null);
  // 포커스를 들고 있던 카드를 닫았을 때, 어디로 포커스를 넘길지 표시.
  const restoreFocus = useRef(false);
  // 스택 밖에서 포커스가 들어온 자리 — 마지막 토스트를 닫으면 여기로 돌려준다.
  const focusOrigin = useRef<HTMLElement | null>(null);

  const commit = useCallback(() => setToasts([...listRef.current]), []);

  const clearTimer = useCallback((id: string) => {
    const timer = timers.current.get(id);
    if (timer?.handle != null) window.clearTimeout(timer.handle);
    timers.current.delete(id);
  }, []);

  const dismiss = useCallback(
    (id: string) => {
      const active = document.activeElement;
      restoreFocus.current =
        active instanceof Element &&
        active.closest('[data-toast-id]')?.getAttribute('data-toast-id') === id;
      clearTimer(id);
      listRef.current = listRef.current.filter((t) => t.id !== id);
      commit();
    },
    [clearTimer, commit],
  );

  const startTimer = useCallback(
    (id: string, ms: number) => {
      clearTimer(id);
      if (ms <= 0) return;
      if (pausedRef.current) {
        // 포인터가 이미 올라와 있으면 재개될 때까지 남은 시간만 들고 기다린다.
        timers.current.set(id, { remaining: ms, startedAt: 0, handle: null });
        return;
      }
      timers.current.set(id, {
        remaining: ms,
        startedAt: Date.now(),
        handle: window.setTimeout(() => dismiss(id), ms),
      });
    },
    [clearTimer, dismiss],
  );

  const setPausedState = useCallback(
    (next: boolean) => {
      if (next === pausedRef.current) return;
      pausedRef.current = next;
      const now = Date.now();
      timers.current.forEach((timer, id) => {
        if (next) {
          if (timer.handle == null) return;
          window.clearTimeout(timer.handle);
          timer.handle = null;
          timer.remaining = Math.max(1, timer.remaining - (now - timer.startedAt));
        } else {
          if (timer.handle != null) return;
          timer.startedAt = now;
          timer.handle = window.setTimeout(() => dismiss(id), timer.remaining);
        }
      });
      setPaused(next);
    },
    [dismiss],
  );

  const syncPaused = useCallback(() => {
    setPausedState(hoveredId.current !== null || focusedId.current !== null);
  }, [setPausedState]);

  const toast = useCallback(
    (options: ToastOptions) => {
      const next = normalize(options);
      const list = listRef.current;
      const last = list[list.length - 1];

      let id: string;
      // 키는 저장하지 않고 비교할 때 파생한다 — 파생값을 담아 두면 update()로 톤이
      // 바뀌어도 키가 옛 톤에 묶여, 원래 톤의 다음 토스트가 그 자리를 덮어쓴다.
      const key = mergeKey(next);
      if (last && key !== null && mergeKey(last) === key) {
        // 같은 종류가 연속으로 발생하면 새로 쌓지 않고 최신 토스트를 갱신한다.
        id = last.id;
        list[list.length - 1] = { ...next, id, version: last.version + 1 };
      } else {
        counter.current += 1;
        id = `pc-toast-${counter.current}`;
        list.push({ ...next, id, version: 0 });
      }

      startTimer(id, resolveDuration(next));
      commit();
      return id;
    },
    [commit, startTimer],
  );

  const update = useCallback(
    (id: string, patch: ToastPatch) => {
      const list = listRef.current;
      const index = list.findIndex((t) => t.id === id);
      const current = list[index];
      if (!current) return;
      // 내용만 바꾸는 갱신은 데드라인을 건드리지 않는다. version은 타이머 바를
      // 리마운트하는 키라, 여기서 같이 올리면 바만 처음부터 흘러 실제 닫힘 시각과
      // 어긋난다 — 타이머를 다시 거는 갱신에서만 올린다.
      // `undefined`는 "안 준 것"으로만 읽는다. 스프레드는 키만 있으면 값이
      // undefined여도 덮어써서, `update(id, { tone: 없을수도있는값 })` 한 번에
      // 카드가 톤과 라이브 리전 자격을 조용히 잃는다. 지우려면 `null`을 준다.
      const given = Object.fromEntries(
        Object.entries(patch).filter(([, v]) => v !== undefined),
      ) as ToastPatch;
      const rearm = given.tone !== undefined || given.duration !== undefined;
      const merged: ToastItem = {
        ...current,
        ...given,
        progress: given.progress === null ? undefined : (given.progress ?? current.progress),
        action: given.action === null ? undefined : (given.action ?? current.action),
        version: current.version + (rearm ? 1 : 0),
      };
      list[index] = merged;
      if (rearm) startTimer(id, resolveDuration(merged));
      commit();
    },
    [commit, startTimer],
  );

  const dismissAll = useCallback(() => {
    restoreFocus.current = viewportRef.current?.contains(document.activeElement) ?? false;
    listRef.current.forEach((t) => clearTimer(t.id));
    listRef.current = [];
    commit();
  }, [clearTimer, commit]);

  // 정지를 붙들던 카드가 사라지면(닫기·스택 밖으로 밀림) 해제 이벤트가 오지
  // 않는다. 렌더가 바뀔 때마다 보이는 카드 기준으로 정지 상태를 다시 계산한다 —
  // 여기서 "안 보이면 해제"로 두어야 정지가 고착되지 않는다. 밀려난 자리에 포인터가
  // 그대로 있는 경우는 아래 onPointerMove가 다시 잡는다.
  useEffect(() => {
    const visibleIds = new Set(toasts.slice(-MAX_VISIBLE).map((t) => t.id));
    let changed = false;
    if (hoveredId.current !== null && !visibleIds.has(hoveredId.current)) {
      hoveredId.current = null;
      changed = true;
    }
    // 포커스는 카드 id만으로 못 믿는다 — 카드는 그대로인데 안쪽 버튼만 사라지면
    // (update로 액션을 뗄 때) blur가 안 와서 정지가 영영 안 풀린다. 실제 위치를 본다.
    const focusInside = viewportRef.current?.contains(document.activeElement) ?? false;
    if (focusedId.current !== null && (!focusInside || !visibleIds.has(focusedId.current))) {
      focusedId.current = null;
      changed = true;
      // 포커스가 갈 곳 없이 body로 떨어졌으면(접힘·액션 제거) 남은 카드로 넘긴다.
      if (document.activeElement === document.body) restoreFocus.current = true;
    }
    if (changed) syncPaused();
  }, [toasts, syncPaused]);

  // #5 — 포커스를 들고 있던 카드가 닫히면 포커스가 body로 떨어져 다음 Tab이 문서
  // 처음부터 시작한다. 남은 카드가 있으면 그 닫기 버튼으로 옮겨 자리를 지킨다.
  useEffect(() => {
    if (!restoreFocus.current) return;
    restoreFocus.current = false;
    // 토스트 액션이 모달을 열었다면 그쪽 FocusScope가 이미 포커스를 잡았다. 자식 효과가
    // 먼저 돌기 때문에, 여기서 옮기면 방금 연 모달에서 포커스를 도로 빼앗는다.
    const active = document.activeElement;
    if (active && active !== document.body && !viewportRef.current?.contains(active)) return;
    const buttons = viewportRef.current?.querySelectorAll<HTMLButtonElement>(
      '[data-toast-id]:not([data-collapsed]) button[aria-label="닫기"]',
    );
    const next = buttons?.[buttons.length - 1];
    if (next) {
      next.focus();
      return;
    }
    // 남은 카드가 없으면 스택에 들어오기 전 자리로 돌려준다. 그러지 않으면 포커스가
    // body로 떨어져 다음 Tab이 문서 처음부터 시작한다.
    const origin = focusOrigin.current;
    focusOrigin.current = null;
    if (origin?.isConnected) origin.focus();
  }, [toasts]);

  useEffect(() => {
    const pending = timers.current;
    return () => {
      pending.forEach((timer) => {
        if (timer.handle != null) window.clearTimeout(timer.handle);
      });
      pending.clear();
    };
  }, []);

  // 접힌 것도 완충 범위까지는 렌더 트리에 남긴다. 언마운트했다가 다시 붙이면 같은
  // 토스트인데도 타이머 바가 처음부터 다시 흐르고 role="alert"가 같은 경고를 또 읽는다.
  const mounted = toasts.slice(-MAX_MOUNTED);
  const hidden = Math.max(0, toasts.length - MAX_VISIBLE);

  // 함수 넷은 안정적인데 감싼 객체가 매 렌더 새로 만들어지면, 포인터를 올렸다 떼는
  // 것만으로도(paused 변경) useToast 소비자가 전부 리렌더된다.
  const api = useMemo(
    () => ({ toast, update, dismiss, dismissAll }),
    [toast, update, dismiss, dismissAll],
  );

  // pointerover/focusin은 자식에서 버블링돼 pointer-events:none인 뷰포트까지 올라온다.
  // 카드 사이를 옮겨 다닐 때 정지가 풀리지 않도록 relatedTarget으로 걸러 낸다.
  const leaving = (e: PointerEvent<HTMLDivElement> | FocusEvent<HTMLDivElement>) =>
    !e.currentTarget.contains(e.relatedTarget as Node | null);

  const cardIdOf = (e: PointerEvent<HTMLDivElement> | FocusEvent<HTMLDivElement>) =>
    (e.target as Element | null)?.closest?.('[data-toast-id]')?.getAttribute('data-toast-id') ??
    null;

  return (
    <ToastContext.Provider value={api}>
      {children}
      <Portal>
        <div
          ref={viewportRef}
          className={styles.viewport}
          // 토스트는 모달·드로어 위에 뜨는 표면이다. 표식이 없으면 그 레이어들이
          // 토스트 클릭을 "바깥 클릭"으로 읽고 닫혀 버린다.
          {...{ [OUTSIDE_POINTER_EXEMPT_ATTR]: '' }}
          onPointerOver={(e) => {
            hoveredId.current = cardIdOf(e);
            syncPaused();
          }}
          onPointerMove={(e) => {
            // 스택이 밀려 카드가 갈리면 브라우저는 멈춰 있는 포인터에 pointerover를
            // 다시 쏘지 않는다. 움직임에서 현재 카드를 다시 읽어 정지를 되찾는다.
            const id = cardIdOf(e);
            if (id === hoveredId.current) return;
            hoveredId.current = id;
            syncPaused();
          }}
          onPointerOut={(e) => {
            if (!leaving(e)) return;
            hoveredId.current = null;
            syncPaused();
          }}
          onFocus={(e) => {
            // 스택 밖에서 처음 들어왔다면 그 자리를 기억해 둔다.
            if (focusedId.current === null && e.relatedTarget instanceof HTMLElement) {
              if (!e.currentTarget.contains(e.relatedTarget)) focusOrigin.current = e.relatedTarget;
            }
            focusedId.current = cardIdOf(e);
            syncPaused();
          }}
          onBlur={(e) => {
            if (!leaving(e)) return;
            focusedId.current = null;
            syncPaused();
          }}
        >
          {hidden > 0 ? <div className={styles.collapsed}>이전 알림 {hidden}개 더</div> : null}
          {mounted.map((item, i) => {
            const depth = mounted.length - 1 - i;
            return (
              <ToastCard
                key={item.id}
                item={item}
                depth={Math.min(depth, MAX_VISIBLE - 1)}
                collapsed={depth >= MAX_VISIBLE}
                paused={paused}
                onClose={() => dismiss(item.id)}
              />
            );
          })}
        </div>
      </Portal>
    </ToastContext.Provider>
  );
}

/** 명령형 토스트 API. `ToastProvider` 하위에서만 쓸 수 있다. */
export function useToast(): ToastContextValue {
  const c = useContext(ToastContext);
  if (!c) throw new Error('useToast must be used within a ToastProvider.');
  return c;
}
