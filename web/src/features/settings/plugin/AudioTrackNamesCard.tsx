'use client';

import { useEffect, useState, type CSSProperties } from 'react';
import { track } from '@/lib/analytics';
import { Button, Input, LoadingRegion, Skeleton, useToast } from '@/ui';
import {
  TRACK_COUNT,
  TRACK_LABEL_MAX,
  fetchMyTrackLabels,
  saveMyTrackLabels,
  trackDisplayName,
  type TrackLabels,
} from '@/api/audioTracks';
import styles from './PluginSettingsScreen.module.css';

const u = (n: number) => `calc(${n} * var(--pc-u))`;

// 입력 여섯 칸의 격자와 한 칸(이름표 위 · 입력 아래). 스켈레톤도 같은 값을 써서 로드 뒤 높이·열 배치가 그대로다
const TRACK_GRID: CSSProperties = {
  display: 'grid',
  gap: u(8),
  gridTemplateColumns: 'repeat(auto-fit, minmax(14rem, 1fr))',
};
const TRACK_CELL: CSSProperties = { display: 'grid', gap: u(4) };

// 오디오 트랙 이름(POK-240) — OBS는 트랙 여섯을 이름 없이 보낸다. 여기서 한 번 적어 두면 편집기 오디오 탭에
// 「트랙 3」 대신 「디스코드」가 뜬다. 시안에 없는 카드다(백엔드가 시안 뒤에 생겼다) — 플러그인 설정의 카드 모양을 그대로 쓴다.
export function AudioTrackNamesCard() {
  const { toast } = useToast();
  const [labels, setLabels] = useState<TrackLabels | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let alive = true;
    fetchMyTrackLabels()
      .then((got) => {
        if (alive) setLabels(got);
      })
      .catch((e: unknown) => {
        if (alive) setError(e instanceof Error ? e.message : String(e));
      });
    return () => {
      alive = false;
    };
  }, []);

  const save = () => {
    if (labels === null) return;
    setSaving(true);
    saveMyTrackLabels(labels.map((label) => (label && label.trim() ? label : null)))
      .then((saved) => {
        setLabels(saved);
        // 이름은 싣지 않는다 — 이름이 있는 칸 수(0~6)만 (POK-357)
        track('audio_track_names_saved', {
          named_count: saved.filter((label) => label !== null).length,
        });
        toast({ tone: 'success', title: '트랙 이름을 저장했어요' });
      })
      .catch((e: unknown) => {
        toast({
          tone: 'error',
          title: '저장하지 못했어요',
          description: e instanceof Error ? e.message : String(e),
        });
      })
      .finally(() => setSaving(false));
  };

  return (
    <section className={styles.card} aria-labelledby="audio-track-names-title">
      <h2 id="audio-track-names-title" className={styles.cardTitle}>
        오디오 트랙 이름
      </h2>
      <p className={styles.codeCardDesc}>
        OBS의 트랙 1~6에 이름을 붙여 두면 편집기 오디오 탭에 그 이름이 보여요. BGM 트랙을 끄고
        내보낼 때 어느 줄인지 알 수 있어요.
      </p>
      {error !== null ? (
        <p role="alert">트랙 이름을 불러오지 못했어요: {error}</p>
      ) : labels === null ? (
        <TrackNamesSkeleton />
      ) : (
        <>
          <div style={TRACK_GRID}>
            {Array.from({ length: TRACK_COUNT }, (_, i) => (
              <label key={i} style={TRACK_CELL}>
                <span className={styles.codeHint}>{trackDisplayName(i, null)}</span>
                <Input
                  id={`audio-track-name-${i}`}
                  size="sm"
                  maxLength={TRACK_LABEL_MAX}
                  placeholder={i === 0 ? '예: 전체' : '예: 마이크 · 게임 · BGM'}
                  value={labels[i] ?? ''}
                  onChange={(event) =>
                    setLabels((prev) =>
                      prev === null ? prev : prev.map((v, j) => (j === i ? event.target.value : v)),
                    )
                  }
                />
              </label>
            ))}
          </div>
          <div className={styles.confirmActions}>
            <Button variant="solid" size="sm" loading={saving} onClick={save}>
              이름 저장
            </Button>
          </div>
        </>
      )}
    </section>
  );
}

// 첫 읽기 전의 입력 여섯 칸과 저장 버튼 자리(POK-446). 로드 뒤와 같은 격자·칸이라 이름이 와도 카드 높이가 바뀌지 않는다.
// Skeleton은 폭·높이·반경을 인라인으로 덮으므로 치수를 여기서 준다: 이름표 한 줄은 11u × 줄높이 1.5라 글자만 한 막대
// 위아래를 여백으로 채우고, 입력·버튼은 sm 높이(32u)·md 반경이다
function TrackNamesSkeleton() {
  return (
    <LoadingRegion label="트랙 이름을 불러오는 중">
      <div style={TRACK_GRID} aria-hidden="true">
        {Array.from({ length: TRACK_COUNT }, (_, i) => (
          <div key={i} style={TRACK_CELL}>
            <Skeleton width={u(44)} height={u(11)} style={{ marginBlock: u(2.75) }} />
            <Skeleton height={u(32)} radius="md" />
          </div>
        ))}
      </div>
      <div className={styles.confirmActions} aria-hidden="true">
        <Skeleton width={u(76)} height={u(32)} radius="md" />
      </div>
    </LoadingRegion>
  );
}
