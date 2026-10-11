'use client';

import { useEffect, useId, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import {
  AspectRatio,
  Button,
  Dialog,
  Field,
  HStack,
  Input,
  RadioGroup,
  Slider,
  Switch,
  Tag,
  Text,
  Textarea,
  VStack,
} from '@/ui';
import type { PrivacyStatus, ThumbnailSource } from '@/api/clipEditor';
import { uploadTitleProblem } from '@/features/clips/library/libraryView';
import {
  addTags,
  DESCRIPTION_MAX_BYTES,
  descriptionBytes,
  descriptionProblem,
  PRIVACY_LABEL,
  privacyNote,
  THUMBNAIL_PHONE_NOTE,
  thumbnailFileProblem,
} from './uploadInfo';

// 「영상 만들기」를 누르면 뜨는 유튜브 업로드 정보 창(POK-291). 칸 여섯: 제목 · 설명 · 태그 · 공개 범위 · 아동용 · 썸네일.
// 확인하면 렌더 주문에 이 정보가 실리고, 렌더가 끝나는 대로 clip이 스트리머 채널에 올린다.
//
// 시안이 없어(ADR-080) DS 부품(Dialog·Field·Input·Textarea·Tag·RadioGroup·Switch·Slider)만 조립한다: 새 스타일·색·배치
// 체계를 만들지 않는다. 생김새는 2번 몫이다.

/** 창 안의 값. 썸네일 파일은 서버에 다시 받을 수 없어(정보에는 「이미지」라는 표시만 남는다) 이 화면이 쥐고 있다 */
export interface UploadDraft {
  title: string;
  description: string;
  tags: string[];
  privacyStatus: PrivacyStatus;
  madeForKids: boolean;
  thumbnailSource: ThumbnailSource;
  /** 장면 고르기의 시각(완성 영상 첫 장면 기준 ms) */
  sceneOffsetMs: number;
  file: File | null;
}

/** 서버(또는 창)가 짚은 칸별 사유 */
export type UploadFieldErrors = Partial<
  Record<'title' | 'description' | 'tags' | 'privacyStatus' | 'thumbnail', string>
>;

export function emptyUploadDraft(sceneOffsetMs: number): UploadDraft {
  return {
    title: '',
    description: '',
    tags: [],
    privacyStatus: 'private',
    madeForKids: false,
    thumbnailSource: 'none',
    sceneOffsetMs,
    file: null,
  };
}

const PRIVACY_ORDER: PrivacyStatus[] = ['private', 'unlisted', 'public'];

const THUMBNAIL_LABEL: Record<ThumbnailSource, string> = {
  none: '자동(유튜브가 고름)',
  scene: '장면 고르기',
  file: '이미지 올리기',
};

/** 장면 시각 표기: 0:03.2 */
function sceneLabel(ms: number): string {
  const tenths = Math.floor(ms / 100);
  const minutes = Math.floor(tenths / 600);
  const seconds = (tenths % 600) / 10;
  return `${minutes}:${seconds.toFixed(1).padStart(4, '0')}`;
}

export function UploadInfoDialog({
  open,
  busy,
  initial,
  cutLengthMs,
  playheadOffsetMs,
  serverErrors,
  onCancel,
  onSubmit,
}: {
  open: boolean;
  /** 저장·주문 중: 확인 단추가 잠기고 Esc·바깥 누름으로도 안 닫힌다 */
  busy: boolean;
  /** 창을 열 때의 값. 열 때마다 이 값으로 다시 채운다 */
  initial: UploadDraft;
  /** 완성 영상 길이(ms): 장면 슬라이더의 끝 */
  cutLengthMs: number;
  /** 지금 재생 위치를 완성 영상 시각으로 옮긴 값. 녹화가 없어 모르면 null */
  playheadOffsetMs: number | null;
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
      <Dialog.Content>
        <Dialog.Title>유튜브 업로드 정보</Dialog.Title>
        <Dialog.Description>
          영상을 다 만들면 스트리머 채널에 이 정보로 올려요. 진행은 보관함에서 볼 수 있어요.
        </Dialog.Description>
        {/* 열릴 때마다 새로 마운트된다(Dialog.Content는 닫히면 그리지 않는다): 처음 값이 매번 initial이다 */}
        <UploadInfoForm
          busy={busy}
          initial={initial}
          cutLengthMs={cutLengthMs}
          playheadOffsetMs={playheadOffsetMs}
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
  serverErrors,
  onCancel,
  onSubmit,
}: Omit<Parameters<typeof UploadInfoDialog>[0], 'open'>) {
  const [draft, setDraft] = useState<UploadDraft>(initial);
  const [tagText, setTagText] = useState('');
  const [errors, setErrors] = useState<UploadFieldErrors>({});
  const fileInput = useRef<HTMLInputElement>(null);
  const privacyLabelId = useId();
  const privacyNoteId = useId();
  const thumbLabelId = useId();
  const sceneMax = Math.max(0, cutLengthMs - 1);

  const set = <K extends keyof UploadDraft>(key: K, value: UploadDraft[K]) => {
    setDraft((d) => ({ ...d, [key]: value }));
    const field = errorKey(key);
    if (field !== null) setErrors((e) => ({ ...e, [field]: undefined }));
  };

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
    setErrors((e) => ({ ...e, tags: undefined }));
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

  const pickFile = (files: FileList | null) => {
    const file = files?.[0];
    if (file === undefined) return;
    const problem = thumbnailFileProblem(file);
    if (problem !== null) {
      setErrors((e) => ({ ...e, thumbnail: problem }));
      return;
    }
    set('file', file);
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (busy) return;
    // 칸에 친 채 Enter를 안 누른 태그도 넣는다: 보이는 글자가 빠지면 안 된다
    const tags = tagText.trim() === '' ? draft.tags : commitTags(tagText);
    const next: UploadFieldErrors = {
      title: uploadTitleProblem(draft.title) ?? undefined,
      description: descriptionProblem(draft.description) ?? undefined,
      thumbnail:
        draft.thumbnailSource === 'file' && draft.file === null
          ? '이미지를 골라 주세요'
          : undefined,
    };
    if (tags === null || Object.values(next).some((v) => v !== undefined)) {
      // 문제 있는 칸만 얹는다. 문제없는 칸에 빈 키를 남기면 그 칸의 서버 오류까지 가린다(errorOf)
      const found = Object.fromEntries(Object.entries(next).filter(([, v]) => v !== undefined));
      setErrors((e) => ({ ...e, ...found }));
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
  const bytes = descriptionBytes(draft.description);

  return (
    <form onSubmit={submit} noValidate>
      <VStack gap={4}>
        <Field invalid={errorOf('title') !== undefined} required>
          <Field.Label>제목</Field.Label>
          <Input
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
            rows={3}
            onChange={(e) => set('description', e.target.value)}
          />
          {errorOf('description') ? <Field.Error>{errorOf('description')}</Field.Error> : null}
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

        <Field invalid={errorOf('privacyStatus') !== undefined}>
          <Field.Label id={privacyLabelId}>공개 범위</Field.Label>
          <RadioGroup
            value={draft.privacyStatus}
            onValueChange={(v) => set('privacyStatus', v as PrivacyStatus)}
            orientation="horizontal"
            aria-labelledby={privacyLabelId}
            aria-describedby={privacyNoteId}
          >
            {PRIVACY_ORDER.map((value) => (
              <RadioGroup.Item key={value} value={value} label={PRIVACY_LABEL[value]} />
            ))}
          </RadioGroup>
          {errorOf('privacyStatus') ? <Field.Error>{errorOf('privacyStatus')}</Field.Error> : null}
          <Text as="p" size="sm" tone="muted" id={privacyNoteId}>
            {privacyNote(draft.privacyStatus)}
          </Text>
        </Field>

        <Switch
          label="아동용 영상이에요"
          checked={draft.madeForKids}
          onChange={(e) => set('madeForKids', e.target.checked)}
        />

        <Field invalid={errorOf('thumbnail') !== undefined}>
          <Field.Label id={thumbLabelId}>썸네일</Field.Label>
          <RadioGroup
            value={draft.thumbnailSource}
            onValueChange={(v) => set('thumbnailSource', v as ThumbnailSource)}
            aria-labelledby={thumbLabelId}
          >
            {(['none', 'scene', 'file'] as const).map((value) => (
              <RadioGroup.Item key={value} value={value} label={THUMBNAIL_LABEL[value]} />
            ))}
          </RadioGroup>

          {draft.thumbnailSource === 'scene' ? (
            <VStack gap={2}>
              <Slider
                label="썸네일 장면"
                min={0}
                max={sceneMax}
                step={100}
                value={Math.min(sceneMax, draft.sceneOffsetMs)}
                onValueChange={(v) => set('sceneOffsetMs', v)}
              />
              <HStack gap={2} align="center" justify="space-between">
                <Text size="sm">{sceneLabel(Math.min(sceneMax, draft.sceneOffsetMs))}</Text>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  disabled={playheadOffsetMs === null}
                  onClick={() => {
                    if (playheadOffsetMs !== null) set('sceneOffsetMs', playheadOffsetMs);
                  }}
                >
                  지금 재생 위치로
                </Button>
              </HStack>
              <Text as="p" size="sm" tone="muted">
                완성 영상의 이 장면을 썸네일로 써요
              </Text>
            </VStack>
          ) : null}

          {draft.thumbnailSource === 'file' ? (
            <VStack gap={2}>
              <HStack gap={2} align="center">
                <Button
                  type="button"
                  variant="soft"
                  size="sm"
                  onClick={() => fileInput.current?.click()}
                >
                  {draft.file === null ? '이미지 고르기' : '다른 이미지 고르기'}
                </Button>
                {draft.file !== null ? <Text size="sm">{draft.file.name}</Text> : null}
              </HStack>
              {previewUrl !== null ? (
                <AspectRatio ratio={16 / 9}>
                  {/* 고른 파일의 로컬 주소라 next/image가 다룰 대상이 아니다(PhotoCropStage 선례) */}
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={previewUrl} alt="고른 썸네일 미리보기" />
                </AspectRatio>
              ) : null}
              <Text as="p" size="sm" tone="muted">
                JPG나 PNG, 10MB까지. 가로 1280×720 이상을 권해요
              </Text>
              {/* 실제 파일 선택기. 위 단추가 대신 누른다 */}
              <input
                ref={fileInput}
                type="file"
                accept="image/jpeg,image/png"
                hidden
                onChange={(e) => {
                  pickFile(e.target.files);
                  e.target.value = ''; // 같은 파일을 다시 골라도 change가 뜨게
                }}
              />
            </VStack>
          ) : null}

          {errorOf('thumbnail') ? <Field.Error>{errorOf('thumbnail')}</Field.Error> : null}
          {draft.thumbnailSource !== 'none' ? (
            <Text as="p" size="sm" tone="muted">
              {THUMBNAIL_PHONE_NOTE}
            </Text>
          ) : null}
        </Field>

        <HStack gap={2} justify="flex-end">
          <Button variant="ghost" size="md" type="button" onClick={onCancel} disabled={busy}>
            취소
          </Button>
          <Button variant="solid" size="md" type="submit" loading={busy}>
            만들고 올리기
          </Button>
        </HStack>
      </VStack>
    </form>
  );
}

/** 칸 이름 → 사유 칸. 장면 시각·파일은 썸네일 칸이 말하고, 아동용은 거절될 일이 없다 */
function errorKey(key: keyof UploadDraft): keyof UploadFieldErrors | null {
  switch (key) {
    case 'title':
    case 'description':
    case 'tags':
    case 'privacyStatus':
      return key;
    case 'thumbnailSource':
    case 'sceneOffsetMs':
    case 'file':
      return 'thumbnail';
    case 'madeForKids':
      return null;
  }
}
