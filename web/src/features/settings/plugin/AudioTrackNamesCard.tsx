'use client';

import { useEffect, useState } from 'react';
import { Button, Input, useToast } from '@/ui';
import {
  TRACK_COUNT,
  TRACK_LABEL_MAX,
  fetchMyTrackLabels,
  saveMyTrackLabels,
  trackDisplayName,
  type TrackLabels,
} from '@/api/audioTracks';
import styles from './PluginSettingsScreen.module.css';

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
        <p role="status">불러오는 중…</p>
      ) : (
        <>
          <div
            style={{
              display: 'grid',
              gap: 'calc(8 * var(--pc-u))',
              gridTemplateColumns: 'repeat(auto-fit, minmax(14rem, 1fr))',
            }}
          >
            {Array.from({ length: TRACK_COUNT }, (_, i) => (
              <label key={i} style={{ display: 'grid', gap: 'calc(4 * var(--pc-u))' }}>
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
