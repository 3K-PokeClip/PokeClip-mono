'use client';

import { useCallback, useEffect, useState } from 'react';
import { subscribeApiActivity } from '@/api/client';
import { Avatar, VisuallyHidden } from '@/ui';
import { useSwitchCurtain, type SwitchCurtain } from './switchCurtain';
import styles from './ChannelSwitchCurtain.module.css';

// 채널 전환 화면(시안 비교 B안): 덮개는 switchCurtain.ts가 세우고, 여기는 그리고 걷는다. 새 채널 화면이 부르는 요청을
// 세어 다 끝나면 걷는다: 정해진 시간에 걷으면 느린 날엔 빈 칸 깜빡임이 그대로 드러난다. 막대도 그 요청을 따라간다:
// 늦어지면 늦어진 만큼 천천히 차고, 다 불러오기 전에는 끝까지 차지 않는다.

/** 최소로 보이는 시간: 너무 빨리 걷히면 무엇으로 바뀌었는지 못 읽는다 */
export const HOLD_MS = 600;
/** 다 불러온 뒤 막대가 끝까지 차는 시간 */
export const FINISH_MS = 180;
/** 걷히는 시간(CSS의 curtain-out과 같다) */
export const FADE_OUT_MS = 220;
/** 응답 없이 멈춘 요청이 화면을 막지 않게: 이만큼 지나면 다 못 불러와도 걷는다 */
export const MAX_MS = 10_000;
const TICK_MS = 100;

export function ChannelSwitchCurtain() {
  const curtain = useSwitchCurtain((s) => s.curtain);
  // 걷힐 때 「전환했어요」를 화면 낭독기에 알린다(토스트가 하던 일). 영역은 늘 떠 있어야 바뀐 글이 읽힌다
  const [announced, setAnnounced] = useState('');
  const announce = useCallback((message: string) => {
    setAnnounced('');
    requestAnimationFrame(() => setAnnounced(message));
  }, []);

  return (
    <>
      {curtain && <Cover key={curtain.key} curtain={curtain} onLeave={announce} />}
      <VisuallyHidden role="status" aria-live="polite" aria-atomic="true">
        {announced}
      </VisuallyHidden>
    </>
  );
}

function Cover({
  curtain,
  onLeave,
}: {
  curtain: SwitchCurtain;
  onLeave: (message: string) => void;
}) {
  const clear = useSwitchCurtain((s) => s.clear);
  const [now, setNow] = useState(() => Date.now());
  const [load, setLoad] = useState({ started: 0, finished: 0 });
  const [phase, setPhase] = useState<'loading' | 'finishing' | 'leaving'>('loading');

  // 덮개가 서 있는 동안 나간 API 요청과 끝난 요청을 센다: 새 채널 화면이 부르는 목록들이다. 덮기 전에 나가 있던
  // 요청(다른 화면의 주기 갱신 등)은 세지 않는다. react-query 밖의 화면 훅도 apiFetch는 지나므로 여기서 다 잡힌다
  useEffect(() => {
    const started = new Set<number>();
    const finished = new Set<number>();
    return subscribeApiActivity(({ type, id }) => {
      if (type === 'start') started.add(id);
      else if (started.has(id)) finished.add(id);
      else return;
      setLoad({ started: started.size, finished: finished.size });
    });
  }, []);

  const elapsed = now - curtain.shownAt;
  const pending = load.started - load.finished;

  // 최소 시간이 지났고 불러오는 것이 없으면 막대를 채우고 걷는다. 멈춘 요청은 MAX_MS에서 끊는다
  useEffect(() => {
    if (phase !== 'loading') return;
    if ((elapsed >= HOLD_MS && pending === 0) || elapsed >= MAX_MS) {
      setPhase('finishing');
      return;
    }
    const tick = setTimeout(() => setNow(Date.now()), TICK_MS);
    return () => clearTimeout(tick);
  }, [phase, elapsed, pending]);

  useEffect(() => {
    if (phase === 'finishing') {
      const t = setTimeout(() => setPhase('leaving'), FINISH_MS);
      return () => clearTimeout(t);
    }
    if (phase === 'leaving') {
      onLeave(`${curtain.name} 채널로 전환했어요`);
      const t = setTimeout(() => clear(curtain.key), FADE_OUT_MS);
      return () => clearTimeout(t);
    }
  }, [phase, curtain.name, curtain.key, clear, onLeave]);

  const editor = curtain.relation === 'EDITOR';
  const progress = phase === 'loading' ? loadingProgress(elapsed, load) : 1;

  return (
    <div className={styles.curtain} data-leaving={phase === 'leaving' || undefined}>
      <Avatar size="xl" src={curtain.photo ?? undefined} name={curtain.name} />
      <div className={styles.title}>{curtain.name} 채널로 전환 중</div>
      <div className={styles.sub}>{editor ? '편집자로 들어가요' : '스트리머로 돌아가요'}</div>
      <div
        className={styles.track}
        data-tone={editor ? 'editor' : 'own'}
        role="progressbar"
        aria-label="채널 전환"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(progress * 100)}
      >
        <div className={styles.fill} style={{ transform: `scaleX(${progress})` }} />
      </div>
    </div>
  );
}

/**
 * 불러오는 동안의 막대(0~0.95). 시간이 갈수록 천천히 다가가는 곡선과 끝난 요청의 비율 중 큰 쪽을 쓴다: 요청이 길어지면
 * 곡선만큼 느리게 나아가고, 요청이 하나씩 끝날 때마다 앞으로 간다. 처음 HOLD_MS 동안은 시간만큼만 나아가 한 번에
 * 튀지 않게 하고, 다 불러오기 전에는 끝까지 차지 않는다.
 */
function loadingProgress(elapsed: number, load: { started: number; finished: number }): number {
  const byTime = 0.9 * (1 - Math.exp(-elapsed / 1200));
  const byRequests = load.started > 0 ? (0.9 * load.finished) / load.started : 0;
  const allowed = Math.min(1, elapsed / HOLD_MS);
  return Math.min(0.95, Math.max(byTime, byRequests * allowed));
}
