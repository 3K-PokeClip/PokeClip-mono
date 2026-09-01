'use client';

import { useEffect, useRef, useState, type DragEvent } from 'react';
import { AlertCircle, Upload, X } from 'lucide-react';
import { Button, Dialog, IconButton, Progress } from '@/ui';
import { cropToDataUrl, INITIAL_CROP, MASK_PX, type CropTransform } from './cropImage';
import { PhotoCropStage, type CropStatus } from './PhotoCropStage';
import { cssColor, PRESET_AVATARS, presetAvatarDataUrl } from './profilePresets';
import type { ProfilePhotoState } from './useProfilePhotoState';
import styles from './AccountSettingsScreen.module.css';

// 디자인 1p 「프로필 사진」 모달 — 선택 → 크롭 → 업로드 3단계 (순서가 시안과 다른 이유는
// useProfilePhotoState 머리말). 단계 판정과 요청은 useProfilePhotoState가 갖고, 여기서는 그리기와
// 캔버스만 맡는다.
//
// 업로드 중에도 크롭 화면을 그대로 둔다 — 로컬·CI는 사진 창고가 꺼져 있어(503) 「실패 → 다시
// 적용」이 가장 흔한 동선인데, 전용 업로드 화면으로 갈아끼우면 복귀마다 원본을 다시 디코드해야
// 하고 포커스가 적용 버튼에서 떨어진다. 머리의 「3 / 3 · 업로드」·진행 표시·취소는 살아 있다.

export function ProfilePhotoDialog({ photo, glyph }: { photo: ProfilePhotoState; glyph: string }) {
  const fileInput = useRef<HTMLInputElement>(null);
  const imgRef = useRef<HTMLImageElement>(null);
  const [transform, setTransform] = useState<CropTransform>(INITIAL_CROP);
  // 크롭 스테이지가 재서 알려 주는 마스크의 실제 지름. 미리보기와 내보내기가 같은 값을
  // 써야 보이는 대로 잘린다 — 시안 기준값(176)은 재기 전까지의 대비값일 뿐이다.
  const [maskPx, setMaskPx] = useState(MASK_PX);
  // 원본이 디코드되기 전에는 잘라낼 수 없다 — 그 상태로 「적용」하면 cropImage가 원본을
  // 그대로 돌려줘(잘리지 않은 사진) 올라가고, 디코드 자체가 안 되는 파일이면 아바타가
  // 깨진 채 토스트만 「변경했습니다」라고 말한다.
  const [cropStatus, setCropStatus] = useState<CropStatus>('loading');
  // 드래그 중에는 :hover가 서지 않는다 — 파일을 끌어오는 내내 브라우저가 hover를
  // 주지 않아 CSS만으로는 「여기 놓으면 된다」를 보여 줄 수 없다. 직접 든다.
  const [dragOver, setDragOver] = useState(false);

  // 새로 고른 그림이면 잘라내기를 처음부터 — 앞 사진의 위치·확대가 남으면 엉뚱하게 잘린다.
  // imageSrc가 아니라 선택 세대에 거는 이유: 같은 기본 아바타·같은 파일을 다시 고르면
  // 문자열이 그대로라 초기화가 안 돈다. 토스트의 「편집」과 업로드 실패 복귀는 세대를 올리지
  // 않아 자르던 자리를 지킨다.
  useEffect(() => {
    setTransform(INITIAL_CROP);
  }, [photo.selectionSeq]);

  // 단계가 바뀌면 표시를 걷는다 — 드롭 직후 dragleave가 안 오는 경우가 있어
  // 그대로 두면 크롭으로 넘어간 뒤에도 강조가 남는다.
  useEffect(() => {
    setDragOver(false);
  }, [photo.step]);

  function pick(files: FileList | null) {
    const file = files?.[0];
    if (file) photo.selectFile(file);
  }

  function handleDrop(e: DragEvent<HTMLElement>) {
    e.preventDefault();
    setDragOver(false);
    pick(e.dataTransfer.files);
  }

  /** 드롭을 받는 면이 공유하는 드래그 표시 — 자식은 pointer-events:none이라 헛발이 없다. */
  const dropTargetProps = {
    onDragEnter: (e: DragEvent<HTMLElement>) => {
      e.preventDefault();
      setDragOver(true);
    },
    onDragOver: (e: DragEvent<HTMLElement>) => {
      e.preventDefault();
      setDragOver(true);
    },
    onDragLeave: () => setDragOver(false),
    onDrop: handleDrop,
    'data-dragover': dragOver || undefined,
  };

  function choosePreset(index: number) {
    const preset = PRESET_AVATARS[index];
    if (!preset) return;
    const dataUrl = presetAvatarDataUrl(preset, glyph);
    if (dataUrl !== null) photo.selectImage(dataUrl);
  }

  function applyCrop() {
    const img = imgRef.current;
    const source = photo.imageSrc;
    if (photo.uploading || img === null || source === null || cropStatus !== 'ready') return;
    photo.apply(cropToDataUrl(img, transform, source, maskPx));
  }

  const presets = (
    <div className={styles.presetSwatches}>
      {PRESET_AVATARS.map((preset, i) => (
        <button
          key={preset.bg + preset.fg}
          type="button"
          className={styles.presetSwatch}
          style={{ background: cssColor(preset.bg), color: cssColor(preset.fg) }}
          aria-label={`기본 아바타 ${i + 1}`}
          onClick={() => choosePreset(i)}
        >
          {glyph}
        </button>
      ))}
    </div>
  );

  return (
    <Dialog
      open={photo.step !== 'idle'}
      onOpenChange={(next) => {
        // 요청이 나간 뒤에는 Esc·백드롭으로 닫히지 않는다 — 나가는 길은 「취소」 하나다
        // (InviteEditorDialog와 같은 규약). 닫기가 곧 취소인데 취소는 「안 올렸다」가 아니라서다.
        if (!next && !photo.uploading) photo.close();
      }}
    >
      <Dialog.Content className={styles.photoDialog}>
        <div className={styles.photoHead}>
          <Dialog.Title className={styles.photoTitle}>프로필 사진</Dialog.Title>
          <span className={styles.photoStep}>{photo.stepLabel}</span>
          <IconButton
            variant="ghost"
            size="sm"
            aria-label="닫기"
            className={styles.photoClose}
            disabled={photo.uploading}
            onClick={photo.close}
          >
            <X aria-hidden />
          </IconButton>
        </div>

        {photo.step === 'empty' && (
          <div className={styles.photoBody}>
            <button
              type="button"
              className={styles.dropzone}
              data-restoring={photo.restoring || undefined}
              onClick={() => fileInput.current?.click()}
              {...dropTargetProps}
            >
              <Upload aria-hidden className={styles.dropzoneIcon} />
              <span className={styles.dropzoneTitle}>사진을 끌어다 놓거나 클릭해 선택</span>
              <span className={styles.dropzoneHint}>
                JPG · PNG · WebP · 5MB 이하 · 정사각 512px 권장
              </span>
            </button>
            <div className={styles.presetRow}>
              <span className={styles.presetLabel}>사진 대신 기본 아바타</span>
              {presets}
            </div>
            {/* 시안 1p에는 없는 문장이다 — 서버가 올리는 순간 구글 사진 주소를 지우고 되돌리는
                창구가 없어(POK-207), 기본 아바타를 고르는 것도 구글 사진을 영구히 대체한다.
                결과가 되돌릴 수 없으면 누르기 전에 말한다(ADR-044의 정신). */}
            <p className={styles.photoNotice}>
              올린 사진은 구글 계정 사진 대신 쓰여요 · 구글 사진으로 되돌릴 수는 없어요
            </p>
          </div>
        )}

        {photo.step === 'error' && (
          <div className={styles.photoBody}>
            {/* 모달 전체가 아니라 드롭존만 흔든다 — 원인이 어디인지 짚어 주려고 (1p) */}
            {/* 안내가 「클릭해 선택」이라고 말하므로 실제로 눌려야 한다. 바깥을 버튼으로 두어
                포인터·키보드 양쪽에서 열리게 하고, 낭독용 alert는 안쪽 span이 든다 —
                버튼에 role="alert"를 얹으면 버튼이라는 사실이 지워진다. */}
            <button
              type="button"
              className={styles.errorZone}
              data-shaking={photo.shaking || undefined}
              onClick={() => fileInput.current?.click()}
              {...dropTargetProps}
            >
              <span role="alert" className={styles.errorMessage}>
                <AlertCircle aria-hidden className={styles.errorIcon} />
                <span className={styles.errorTitle}>{photo.errorTitle}</span>
                <span className={styles.errorHint}>{photo.errorHint}</span>
              </span>
            </button>
            <div className={styles.presetRow}>
              <span className={styles.presetLabel}>잠시 후 선택 화면으로 돌아갑니다</span>
              {presets}
            </div>
          </div>
        )}

        {(photo.step === 'crop' || photo.step === 'uploading') && photo.imageSrc !== null && (
          <>
            <div className={styles.cropBody}>
              <PhotoCropStage
                src={photo.imageSrc}
                transform={transform}
                onChange={setTransform}
                imgRef={imgRef}
                maskPx={maskPx}
                onMaskPxChange={setMaskPx}
                onStatusChange={setCropStatus}
              />
              {cropStatus === 'error' && (
                <p role="alert" className={styles.cropError}>
                  이 파일은 사진으로 읽을 수 없어요. 다른 사진을 골라 주세요.
                </p>
              )}
              {/* 진행률은 없다(fetch는 업로드 진행 이벤트를 주지 않는다) — 부정형 막대와 파일 표기만 */}
              {photo.uploading && (
                <div className={styles.uploadRow}>
                  <span className={styles.uploadBar}>
                    <Progress size="sm" label="업로드 진행률" />
                  </span>
                  <span className={styles.uploadFile}>{photo.fileLabel}</span>
                </div>
              )}
              {photo.uploadError !== null && (
                <p role="alert" className={styles.cropError}>
                  {photo.uploadError}
                </p>
              )}
            </div>
            <div className={styles.cropActions}>
              {/* 업로드 중의 취소는 요청을 끊고 자르던 자리로 돌아온다 — 모달을 닫지 않는다 */}
              <Button
                variant="outline"
                size="sm"
                onClick={photo.uploading ? photo.cancelUpload : photo.close}
              >
                취소
              </Button>
              <Button
                variant="solid"
                size="sm"
                disabled={cropStatus !== 'ready'}
                loading={photo.uploading}
                onClick={applyCrop}
              >
                적용
              </Button>
            </div>
          </>
        )}

        {/* 실제 파일 선택기. 드롭존 버튼이 대신 눌리므로 보조기술에는 감춘다 */}
        <input
          ref={fileInput}
          type="file"
          accept="image/jpeg,image/png,image/webp"
          className={styles.fileInput}
          tabIndex={-1}
          aria-hidden
          onChange={(e) => {
            pick(e.target.files);
            e.target.value = ''; // 같은 파일을 다시 골라도 change가 뜨게
          }}
        />
      </Dialog.Content>
    </Dialog>
  );
}
