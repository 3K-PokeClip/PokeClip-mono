'use client';

import {
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type FormEvent,
  type KeyboardEvent,
} from 'react';
import {
  Badge,
  Button,
  Dialog,
  Field,
  HStack,
  Input,
  RadioGroup,
  Select,
  Switch,
  Tabs,
  Tag,
  Text,
  Textarea,
} from '@/ui';
import type { PrivacyStatus, ThumbnailSource } from '@/api/clipEditor';
import { uploadTitleProblem } from '@/features/clips/library/libraryView';
import {
  addTags,
  CATEGORIES,
  DEFAULT_CATEGORY,
  defaultSchedule,
  DESCRIPTION_MAX_BYTES,
  descriptionBytes,
  descriptionProblem,
  localDate,
  privacyNote,
  quickSchedules,
  sceneLabel,
  scenePicks,
  scheduleLabel,
  scheduleProblem,
} from './uploadInfo';
import { stripTileTimes, UploadThumbnailPane } from './UploadThumbnailPane';
import { useSceneFrames, type SceneFrameSource } from './useSceneFrames';
import styles from './UploadInfoDialog.module.css';

// 「영상 만들기」를 누르면 뜨는 유튜브 업로드 창(POK-291 → POK-381 개편). 확인하면 렌더 주문에 이 정보가 실리고, 렌더가 끝나는
// 대로 clip이 스트리머 채널에 올린다.
//
// 틀은 시안 C3(2026-10-22 사용자 결정): 왼쪽은 쇼츠에서 보일 모습(세로 썸네일 + 제목), 오른쪽은 탭 「정보 · 썸네일 · 공개」.
// 탭은 아무 때나 오가지만 「업로드」는 공개 탭에만 있다(정보·썸네일 탭은 「다음」). 썸네일 탭은 시안 T2(펼치는 목록).

/** 창 안의 값. 썸네일 파일은 서버에 다시 받을 수 없어(정보에는 「이미지」라는 표시만 남는다) 이 화면이 쥐고 있다 */
export interface UploadDraft {
  title: string;
  description: string;
  tags: string[];
  /** 유튜브 분류 번호(POK-381) */
  categoryId: string;
  privacyStatus: PrivacyStatus;
  /** 예약 공개(POK-381). 켜면 비공개로 올리고 그 시각에 공개로 바뀐다(privacyStatus는 보낼 때 private) */
  scheduled: boolean;
  /** 예약 날짜·시각(로컬, 2026-10-23 · 18:00). 예약을 꺼도 값은 남는다(다시 켜면 그대로) */
  scheduleDate: string;
  scheduleTime: string;
  madeForKids: boolean;
  thumbnailSource: ThumbnailSource;
  /** 장면 고르기의 시각(완성 영상 첫 장면 기준 ms) */
  sceneOffsetMs: number;
  file: File | null;
}

/** 서버(또는 창)가 짚은 칸별 사유 */
export type UploadFieldErrors = Partial<
  Record<
    'title' | 'description' | 'tags' | 'categoryId' | 'privacyStatus' | 'publishAt' | 'thumbnail',
    string
  >
>;

/** 처음 값. 썸네일은 처음부터 장면 하나를 골라 둔다(POK-381 사용자 결정, 전에는 「자동」) */
export function emptyUploadDraft(sceneOffsetMs: number, now: number = Date.now()): UploadDraft {
  const schedule = defaultSchedule(now);
  return {
    title: '',
    description: '',
    tags: [],
    categoryId: DEFAULT_CATEGORY,
    privacyStatus: 'private',
    scheduled: false,
    scheduleDate: schedule.date,
    scheduleTime: schedule.time,
    madeForKids: false,
    thumbnailSource: 'scene',
    sceneOffsetMs,
    file: null,
  };
}

type UploadTab = 'info' | 'thumbnail' | 'publish';
const TABS: { value: UploadTab; label: string }[] = [
  { value: 'info', label: '정보' },
  { value: 'thumbnail', label: '썸네일' },
  { value: 'publish', label: '공개' },
];
const NEXT_TAB: Partial<Record<UploadTab, UploadTab>> = { info: 'thumbnail', thumbnail: 'publish' };
const PREV_TAB: Partial<Record<UploadTab, UploadTab>> = { thumbnail: 'info', publish: 'thumbnail' };

/** 사유 칸 → 그 칸이 있는 탭. 보낼 때 걸린 칸의 탭으로 옮겨 준다 */
const TAB_OF: Record<keyof UploadFieldErrors, UploadTab> = {
  title: 'info',
  description: 'info',
  tags: 'info',
  categoryId: 'info',
  thumbnail: 'thumbnail',
  privacyStatus: 'publish',
  publishAt: 'publish',
};

type Visibility = PrivacyStatus | 'scheduled';
const VISIBILITY: { value: Visibility; label: string }[] = [
  { value: 'private', label: '비공개' },
  { value: 'unlisted', label: '일부 공개' },
  { value: 'public', label: '공개' },
  { value: 'scheduled', label: '예약' },
];

function firstErrorTab(errors: UploadFieldErrors): UploadTab | null {
  for (const tab of TABS) {
    const hit = Object.entries(errors).some(
      ([key, message]) =>
        message !== undefined && TAB_OF[key as keyof UploadFieldErrors] === tab.value,
    );
    if (hit) return tab.value;
  }
  return null;
}

export function UploadInfoDialog({
  open,
  busy,
  initial,
  cutLengthMs,
  playheadOffsetMs,
  pickedInEditor = false,
  highlightOffsetMs = null,
  frameSource = null,
  serverErrors,
  onCancel,
  onSubmit,
}: {
  open: boolean;
  /** 저장·주문 중: 업로드 단추가 잠기고 Esc·바깥 누름으로도 안 닫힌다 */
  busy: boolean;
  /** 창을 열 때의 값. 열 때마다 이 값으로 다시 채운다 */
  initial: UploadDraft;
  /** 완성 영상 길이(ms): 장면 띠의 끝 */
  cutLengthMs: number;
  /** 지금 재생 위치를 완성 영상 시각으로 옮긴 값. 녹화가 없어 모르면 null */
  playheadOffsetMs: number | null;
  /** 장면이 편집기의 「이 장면을 썸네일로」로 채워졌는가(POK-389) */
  pickedInEditor?: boolean;
  /** 하이라이트(카드 시각)를 완성 영상 시각으로 옮긴 값. 카드 없이 연 편집본이면 null */
  highlightOffsetMs?: number | null;
  /** 장면 그림을 뽑을 재료. 녹화가 없거나 아직 못 받았으면 null(시각만 보인다) */
  frameSource?: SceneFrameSource | null;
  serverErrors: UploadFieldErrors;
  onCancel: () => void;
  onSubmit: (draft: UploadDraft) => void;
}) {
  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next && !busy) onCancel();
      }}
    >
      <Dialog.Content className={styles.dialog}>
        <div className={styles.head}>
          <Dialog.Title>유튜브에 올리기</Dialog.Title>
          <Dialog.Description>
            영상을 다 만들면 스트리머 채널에 이 정보로 올려요. 진행은 보관함에서 볼 수 있어요.
          </Dialog.Description>
        </div>
        {/* 열릴 때마다 새로 마운트된다(Dialog.Content는 닫히면 그리지 않는다): 처음 값이 매번 initial이다 */}
        <UploadInfoForm
          busy={busy}
          initial={initial}
          cutLengthMs={cutLengthMs}
          playheadOffsetMs={playheadOffsetMs}
          pickedInEditor={pickedInEditor}
          highlightOffsetMs={highlightOffsetMs}
          frameSource={frameSource}
          serverErrors={serverErrors}
          onCancel={onCancel}
          onSubmit={onSubmit}
        />
      </Dialog.Content>
    </Dialog>
  );
}

function UploadInfoForm({
  busy,
  initial,
  cutLengthMs,
  playheadOffsetMs,
  pickedInEditor = false,
  highlightOffsetMs,
  frameSource,
  serverErrors,
  onCancel,
  onSubmit,
}: Omit<Parameters<typeof UploadInfoDialog>[0], 'open'> & {
  highlightOffsetMs: number | null;
  frameSource: SceneFrameSource | null;
}) {
  const [draft, setDraft] = useState<UploadDraft>(initial);
  const [tab, setTab] = useState<UploadTab>('info');
  const [tagText, setTagText] = useState('');
  const [errors, setErrors] = useState<UploadFieldErrors>({});
  const [hover, setHover] = useState<number | null>(null);
  const [openedAt] = useState(() => Date.now());
  const privacyLabelId = useId();
  const privacyNoteId = useId();
  const titleRef = useRef<HTMLInputElement>(null);

  // 창을 열면 제목 칸부터 친다. DS 창은 첫 칸(탭)에 초점을 두는데, 이 효과가 그 레이아웃 효과보다 늦게 돌아 제목으로 옮긴다
  useEffect(() => {
    titleRef.current?.focus();
  }, []);

  const picks = useMemo(
    // 편집기에서 「이 장면을 썸네일로」 고른 장면(POK-389)을 추천 맨 앞에 둔다
    () =>
      scenePicks(
        cutLengthMs,
        highlightOffsetMs,
        playheadOffsetMs,
        pickedInEditor ? initial.sceneOffsetMs : null,
      ),
    [cutLengthMs, highlightOffsetMs, playheadOffsetMs, pickedInEditor, initial.sceneOffsetMs],
  );
  const wanted = useMemo(
    () => [...picks.map((p) => p.offsetMs), ...stripTileTimes(cutLengthMs)],
    [picks, cutLengthMs],
  );
  const chosenScene = draft.thumbnailSource === 'scene' ? draft.sceneOffsetMs : null;
  const { frameOf, failed: frameFailed } = useSceneFrames(
    frameSource,
    wanted,
    hover ?? chosenScene,
    chosenScene,
  );

  const clearError = (field: keyof UploadFieldErrors) =>
    setErrors((e) => ({ ...e, [field]: undefined }));
  const set = <K extends keyof UploadDraft>(key: K, value: UploadDraft[K]) => {
    setDraft((d) => ({ ...d, [key]: value }));
    const field = errorKey(key);
    if (field !== null) clearError(field);
  };

  // 서버가 칸을 짚어 거절하면 그 칸이 있는 탭을 연다(업로드는 공개 탭에서 누르므로 다른 탭의 칸은 안 보인다)
  useEffect(() => {
    const target = firstErrorTab(serverErrors);
    if (target !== null) setTab(target);
  }, [serverErrors]);

  // 고른 이미지의 미리보기 주소: 바꾸거나 닫으면 놓아준다. 만드는 것과 놓는 것을 한 effect에 둔다(개발 모드가 effect를
  // 두 번 돌려도 놓은 주소를 계속 쓰지 않게)
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  useEffect(() => {
    if (draft.file === null || typeof URL.createObjectURL !== 'function') {
      setPreviewUrl(null);
      return undefined;
    }
    const url = URL.createObjectURL(draft.file);
    setPreviewUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [draft.file]);

  const commitTags = (raw: string): string[] | null => {
    const { tags, problem } = addTags(draft.tags, raw);
    if (problem !== undefined) {
      setErrors((e) => ({ ...e, tags: problem }));
      return null;
    }
    setDraft((d) => ({ ...d, tags }));
    clearError('tags');
    return tags;
  };

  const onTagKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key !== 'Enter' || event.nativeEvent.isComposing) return;
    // Enter는 폼을 보내지 않고 태그를 넣는다
    event.preventDefault();
    if (commitTags(tagText) !== null) setTagText('');
  };

  const onTagChange = (value: string) => {
    // 쉼표까지 친 것은 태그로 넣고 뒤의 글자만 칸에 남긴다
    const cut = value.lastIndexOf(',');
    if (cut < 0) {
      setTagText(value);
      return;
    }
    if (commitTags(value.slice(0, cut)) !== null) setTagText(value.slice(cut + 1));
    else setTagText(value);
  };

  const visibility: Visibility = draft.scheduled ? 'scheduled' : draft.privacyStatus;
  const setVisibility = (next: Visibility) => {
    setDraft((d) => ({
      ...d,
      scheduled: next === 'scheduled',
      // 예약은 비공개로 올리고 그 시각에 공개로 바뀐다(유튜브 publishAt은 비공개 영상에만 받는다)
      privacyStatus: next === 'scheduled' ? 'private' : next,
    }));
    setErrors((e) => ({ ...e, privacyStatus: undefined, publishAt: undefined }));
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (busy) return;
    // 업로드는 공개 탭에서만. 다른 탭에서 Enter로 보내지면 다음 탭으로 넘긴다
    const following = NEXT_TAB[tab];
    if (following !== undefined) {
      setTab(following);
      return;
    }
    // 칸에 친 채 Enter를 안 누른 태그도 넣는다: 보이는 글자가 빠지면 안 된다
    const tags = tagText.trim() === '' ? draft.tags : commitTags(tagText);
    const found: UploadFieldErrors = {
      title: uploadTitleProblem(draft.title) ?? undefined,
      description: descriptionProblem(draft.description) ?? undefined,
      thumbnail:
        draft.thumbnailSource === 'file' && draft.file === null
          ? '이미지를 골라 주세요'
          : undefined,
      publishAt: draft.scheduled
        ? (scheduleProblem(draft.scheduleDate, draft.scheduleTime, Date.now()) ?? undefined)
        : undefined,
    };
    // 문제 있는 칸만 얹는다. 문제없는 칸에 빈 키를 남기면 그 칸의 서버 오류까지 가린다(errorOf)
    const problems = Object.fromEntries(
      Object.entries(found).filter(([, v]) => v !== undefined),
    ) as UploadFieldErrors;
    if (tags === null || Object.keys(problems).length > 0) {
      setErrors((e) => ({ ...e, ...problems }));
      // 걸린 칸이 있는 첫 탭으로(태그가 걸렸으면 commitTags가 사유를 이미 얹었다)
      setTab(firstErrorTab(tags === null ? { ...problems, tags: '태그' } : problems) ?? tab);
      return;
    }
    setTagText('');
    // 고쳤다는 표시를 비운다: 이번에 보낸 것이 또 거절되면 서버 오류가 다시 보여야 한다
    setErrors({});
    onSubmit({ ...draft, tags });
  };

  // 칸을 고치면 set이 그 칸 키를 undefined로 남긴다. 키가 있으면 그 칸은 서버 오류를 가린다(고친 값이 아직 틀렸다고
  // 읽히지 않게). 다음 보내기 때 비운다
  const errorOf = (key: keyof UploadFieldErrors) =>
    key in errors ? errors[key] : serverErrors[key];
  const tabHasError = (value: UploadTab) =>
    (Object.keys(TAB_OF) as (keyof UploadFieldErrors)[]).some(
      (key) => TAB_OF[key] === value && errorOf(key) !== undefined,
    );
  const bytes = descriptionBytes(draft.description);
  const schedule = scheduleLabel(draft.scheduleDate, draft.scheduleTime);
  const previewSrc =
    draft.thumbnailSource === 'scene'
      ? frameOf(draft.sceneOffsetMs)
      : draft.thumbnailSource === 'file'
        ? previewUrl
        : null;
  const footNote =
    tab !== 'publish'
      ? '공개 탭에서 올려요'
      : draft.scheduled
        ? schedule === ''
          ? '예약 시각을 골라 주세요'
          : `지금 비공개로 올리고 ${schedule}에 공개로 바꿔요`
        : '영상을 다 만들면 바로 올려요';
  const prev = PREV_TAB[tab];
  const next = NEXT_TAB[tab];

  return (
    <form onSubmit={submit} noValidate className={styles.form}>
      <div className={styles.body}>
        <div className={styles.left}>
          <span className={styles.label}>쇼츠에서 이렇게 보여요</span>
          <div className={styles.shorts}>
            {previewSrc !== null ? (
              // 뽑은 장면(data:)·고른 파일(blob:)이라 next/image가 다룰 대상이 아니다
              // eslint-disable-next-line @next/next/no-img-element
              <img src={previewSrc} alt="썸네일 미리보기" />
            ) : (
              <span className={styles.shortsEmpty}>
                {draft.thumbnailSource === 'none'
                  ? '유튜브가 영상에서 골라요'
                  : draft.thumbnailSource === 'file'
                    ? '고른 이미지가 여기 보여요'
                    : frameFailed(draft.sceneOffsetMs)
                      ? `장면 그림을 못 불러와요 · ${sceneLabel(draft.sceneOffsetMs)}`
                      : '장면을 불러오는 중이에요'}
              </span>
            )}
            {draft.scheduled && schedule !== '' ? (
              <span className={styles.shortsBadge}>{schedule} 공개</span>
            ) : null}
            <div className={styles.shortsShade}>
              <p className={styles.shortsTitle}>
                {draft.title.trim() === '' ? '제목' : draft.title}
              </p>
            </div>
          </div>
        </div>

        <div className={styles.right}>
          <Tabs value={tab} onValueChange={(v) => setTab(v as UploadTab)}>
            <Tabs.List aria-label="업로드 정보" className={styles.tabs}>
              {TABS.map((t) => (
                <Tabs.Trigger key={t.value} value={t.value} className={styles.tab}>
                  {t.label}
                  {tabHasError(t.value) ? (
                    <span className={styles.tabError} role="img" aria-label="확인할 칸 있음" />
                  ) : null}
                </Tabs.Trigger>
              ))}
            </Tabs.List>

            <Tabs.Panel value="info" className={styles.panel}>
              <Field invalid={errorOf('title') !== undefined} required>
                <Field.Label>제목</Field.Label>
                <Input
                  ref={titleRef}
                  value={draft.title}
                  autoComplete="off"
                  onChange={(e) => set('title', e.target.value)}
                />
                {errorOf('title') ? (
                  <Field.Error>{errorOf('title')}</Field.Error>
                ) : (
                  <Field.Description>100자까지, &lt; &gt; 는 쓸 수 없어요</Field.Description>
                )}
              </Field>

              <Field invalid={errorOf('description') !== undefined}>
                <Field.Label>설명</Field.Label>
                <Textarea
                  value={draft.description}
                  rows={4}
                  onChange={(e) => set('description', e.target.value)}
                />
                {errorOf('description') ? (
                  <Field.Error>{errorOf('description')}</Field.Error>
                ) : null}
                <Field.Description>
                  {bytes} / {DESCRIPTION_MAX_BYTES}바이트
                </Field.Description>
              </Field>

              <Field invalid={errorOf('tags') !== undefined}>
                <Field.Label>태그</Field.Label>
                <Input
                  value={tagText}
                  autoComplete="off"
                  onChange={(e) => onTagChange(e.target.value)}
                  onKeyDown={onTagKeyDown}
                />
                {errorOf('tags') ? (
                  <Field.Error>{errorOf('tags')}</Field.Error>
                ) : (
                  <Field.Description>Enter나 쉼표로 넣어요. 합쳐서 500자까지예요</Field.Description>
                )}
                {draft.tags.length > 0 ? (
                  <HStack gap={2} wrap>
                    {draft.tags.map((tag) => (
                      <Tag
                        key={tag}
                        size="sm"
                        onRemove={() =>
                          set(
                            'tags',
                            draft.tags.filter((t) => t !== tag),
                          )
                        }
                        removeLabel={`태그 ${tag} 빼기`}
                      >
                        {tag}
                      </Tag>
                    ))}
                  </HStack>
                ) : null}
              </Field>

              <Field invalid={errorOf('categoryId') !== undefined}>
                <Field.Label>카테고리</Field.Label>
                <Select
                  aria-label="카테고리"
                  options={CATEGORIES.map((c) => ({ value: c.value, label: c.label }))}
                  value={draft.categoryId}
                  onValueChange={(v) => set('categoryId', v)}
                  invalid={errorOf('categoryId') !== undefined}
                />
                {errorOf('categoryId') ? <Field.Error>{errorOf('categoryId')}</Field.Error> : null}
              </Field>
            </Tabs.Panel>

            <Tabs.Panel value="thumbnail" className={styles.panel}>
              <UploadThumbnailPane
                source={draft.thumbnailSource}
                sceneOffsetMs={draft.sceneOffsetMs}
                file={draft.file}
                previewUrl={previewUrl}
                cutLengthMs={cutLengthMs}
                picks={picks}
                frameOf={frameOf}
                error={errorOf('thumbnail')}
                onSource={(source) => set('thumbnailSource', source)}
                onScene={(ms) => set('sceneOffsetMs', ms)}
                onFile={(file) => set('file', file)}
                onFileProblem={(problem) => setErrors((e) => ({ ...e, thumbnail: problem }))}
                onHover={setHover}
              />
            </Tabs.Panel>

            <Tabs.Panel value="publish" className={styles.panel}>
              <Field invalid={errorOf('privacyStatus') !== undefined}>
                <Field.Label id={privacyLabelId}>공개 범위</Field.Label>
                <RadioGroup
                  value={visibility}
                  onValueChange={(v) => setVisibility(v as Visibility)}
                  orientation="horizontal"
                  aria-labelledby={privacyLabelId}
                  aria-describedby={privacyNoteId}
                  className={styles.segmented}
                >
                  {VISIBILITY.map((v) => (
                    <RadioGroup.Item
                      key={v.value}
                      value={v.value}
                      label={v.label}
                      className={styles.segment}
                    />
                  ))}
                </RadioGroup>
                {errorOf('privacyStatus') ? (
                  <Field.Error>{errorOf('privacyStatus')}</Field.Error>
                ) : null}
                <Text as="p" size="sm" tone="muted" id={privacyNoteId}>
                  {draft.scheduled
                    ? '예약한 시각까지는 비공개예요. 그때 공개로 바뀌어 누구나 볼 수 있고, 구독자에게 새 영상 알림이 갈 수 있어요.'
                    : privacyNote(draft.privacyStatus)}
                </Text>
              </Field>

              {draft.scheduled ? (
                <SchedulePicker
                  date={draft.scheduleDate}
                  time={draft.scheduleTime}
                  openedAt={openedAt}
                  error={errorOf('publishAt')}
                  onChange={(date, time) => {
                    setDraft((d) => ({ ...d, scheduleDate: date, scheduleTime: time }));
                    clearError('publishAt');
                  }}
                />
              ) : null}

              <Switch
                className={draft.madeForKids ? `${styles.kids} ${styles.kidsOn}` : styles.kids}
                checked={draft.madeForKids}
                onChange={(e) => set('madeForKids', e.target.checked)}
                label={
                  <span className={styles.kidsText}>
                    <span className={styles.kidsTitle}>
                      아동용 영상으로 올리기
                      {draft.madeForKids ? (
                        <Badge tone="accent" size="sm">
                          아동용
                        </Badge>
                      ) : null}
                    </span>
                    <span className={styles.kidsDesc}>
                      {draft.madeForKids
                        ? '아동용으로 올려요 · 댓글 · 알림 · 맞춤 광고가 꺼져요'
                        : '어린이(만 13세 미만)를 보라고 만든 영상일 때만 켜요'}
                    </span>
                  </span>
                }
              />
            </Tabs.Panel>
          </Tabs>
        </div>
      </div>

      <div className={styles.foot}>
        <span className={styles.footNote}>{footNote}</span>
        <HStack gap={2} justify="flex-end">
          {/* 단추마다 key를 달리한다: 같은 자리 단추를 다시 쓰면 「다음」을 누른 그 단추가 업로드(submit)로 바뀌어 보내진다 */}
          {prev !== undefined ? (
            <Button key="prev" variant="ghost" size="md" type="button" onClick={() => setTab(prev)}>
              이전
            </Button>
          ) : (
            <Button
              key="cancel"
              variant="ghost"
              size="md"
              type="button"
              onClick={onCancel}
              disabled={busy}
            >
              취소
            </Button>
          )}
          {next !== undefined ? (
            <Button key="next" variant="solid" size="md" type="button" onClick={() => setTab(next)}>
              다음
            </Button>
          ) : (
            <Button key="upload" variant="solid" size="md" type="submit" loading={busy}>
              {draft.scheduled ? '예약 업로드' : '업로드'}
            </Button>
          )}
        </HStack>
      </div>
    </form>
  );
}

/** 예약 시각 고르기: 빠른 고르기 셋 + 날짜·시각 칸. 지금부터 1시간 뒤부터(보낼 때 다시 잰다) */
function SchedulePicker({
  date,
  time,
  openedAt,
  error,
  onChange,
}: {
  date: string;
  time: string;
  openedAt: number;
  error: string | undefined;
  onChange: (date: string, time: string) => void;
}) {
  const quick = quickSchedules(openedAt);
  return (
    <div className={styles.schedule}>
      {quick.length > 0 ? (
        <HStack gap={2} wrap>
          {quick.map((q) => {
            const on = q.date === date && q.time === time;
            return (
              <Button
                key={q.label}
                type="button"
                size="sm"
                variant={on ? 'soft' : 'outline'}
                aria-pressed={on}
                onClick={() => onChange(q.date, q.time)}
              >
                {q.label}
              </Button>
            );
          })}
        </HStack>
      ) : null}
      <div className={styles.scheduleRow}>
        <Field invalid={error !== undefined}>
          <Field.Label>공개 날짜</Field.Label>
          <Input
            type="date"
            value={date}
            min={localDate(new Date(openedAt))}
            onChange={(e) => onChange(e.target.value, time)}
          />
        </Field>
        <Field invalid={error !== undefined}>
          <Field.Label>공개 시각</Field.Label>
          <Input type="time" value={time} onChange={(e) => onChange(date, e.target.value)} />
        </Field>
      </div>
      {error !== undefined ? (
        <Text as="p" size="sm" tone="danger" role="alert">
          {error}
        </Text>
      ) : (
        <Text as="p" size="xs" tone="muted">
          지금부터 1시간 뒤부터 고를 수 있어요
        </Text>
      )}
    </div>
  );
}

/** 칸 이름 → 사유 칸. 장면 시각·파일은 썸네일 칸이 말하고, 아동용은 거절될 일이 없다 */
function errorKey(key: keyof UploadDraft): keyof UploadFieldErrors | null {
  switch (key) {
    case 'title':
    case 'description':
    case 'tags':
    case 'categoryId':
    case 'privacyStatus':
      return key;
    case 'scheduled':
    case 'scheduleDate':
    case 'scheduleTime':
      return 'publishAt';
    case 'thumbnailSource':
    case 'sceneOffsetMs':
    case 'file':
      return 'thumbnail';
    case 'madeForKids':
      return null;
  }
}
