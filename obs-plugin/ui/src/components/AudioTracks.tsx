import { TriangleAlert } from 'lucide-preact';
import { AUDIO_KIND_LABEL } from '../lib/copy';
import type { BridgeState } from '../lib/types';
import styles from './dock.module.css';

// A2 — 트랙 1은 늘 최종 믹스(본방과 같은 소리), 트랙 2~6은 소스별 스템(편집용, ADR-017).
// 실제 OBS 트랙 체크 기준이라 자동 배정을 꺼도 지금 나가는 그대로를 보여준다.
export function AudioTracks({ state }: { state: BridgeState }) {
  const { audio } = state;
  if (!audio.known) return null;
  const names = (list: { name: string }[]) => list.map((s) => s.name).join(', ');

  return (
    <section class={styles.section} aria-labelledby="audio-title">
      <h3 id="audio-title" class={styles.sectionTitle}>
        {audio.autoAssign ? '오디오 트랙 · 자동 배정' : '오디오 트랙 · OBS 설정 그대로'}
      </h3>
      <ul class={styles.checks}>
        <li class={styles.check}>
          <span class={styles.trackNo} data-filled="true">
            <span class={styles.srOnly}>트랙 </span>1
          </span>
          <span class={styles.trackSources}>최종 믹스</span>
          <span class={styles.checkValue}>본방과 같은 소리</span>
        </li>
        {audio.tracks.map((t) => {
          const empty = t.sources.length === 0;
          const kind =
            t.sources.length === 1 ? AUDIO_KIND_LABEL[t.sources[0].kind] : t.sources.length > 1 ? `${t.sources.length}개 섞임` : '';
          return (
            <li class={styles.check} key={t.track}>
              <span class={styles.trackNo} data-filled={empty ? 'false' : 'true'}>
                <span class={styles.srOnly}>트랙 </span>
                {t.track}
              </span>
              <span class={styles.trackSources} data-empty={empty ? 'true' : 'false'} title={empty ? undefined : names(t.sources)}>
                {empty ? '비어 있음' : names(t.sources)}
              </span>
              {kind ? <span class={styles.checkValue}>{kind}</span> : null}
            </li>
          );
        })}
      </ul>
      {audio.mixOnly.length > 0 ? (
        <p class={styles.audioNote} data-tone={audio.applied ? 'warn' : undefined}>
          {audio.applied ? <TriangleAlert size={12} aria-hidden="true" /> : null}
          {audio.applied
            ? `트랙이 모자라 믹스에만 들어가요: ${names(audio.mixOnly)}`
            : `트랙 2~6에 없는 소스: ${names(audio.mixOnly)}`}
        </p>
      ) : null}
      {audio.monitorOnly.length > 0 ? (
        <p class={styles.audioNote}>모니터 전용이라 송출되지 않아요: {names(audio.monitorOnly)}</p>
      ) : null}
    </section>
  );
}
