'use client';

import { useEffect, useRef, useState, type DragEvent } from 'react';
import { AlertCircle, Upload, X } from 'lucide-react';
import { Button, Dialog, IconButton, Progress, Spinner } from '@/ui';
import { cropToDataUrl, INITIAL_CROP, type CropTransform } from './cropImage';
import { PhotoCropStage } from './PhotoCropStage';
import { cssColor, PRESET_AVATARS, presetAvatarDataUrl } from './profilePresets';
import type { ProfilePhotoState } from './useProfilePhotoState';
import styles from './AccountSettingsScreen.module.css';

// 디자인 1p 「프로필 사진」 모달 — 선택 → 업로드 → 크롭 3단계.
// 단계 판정과 타이머는 useProfilePhotoState가 갖고, 여기서는 그리기와 캔버스만 맡는다.

export function ProfilePhotoDialog({ photo, glyph }: { photo: ProfilePhotoState; glyph: string }) {
  const fileInput = useRef<HTMLInputElement>(null);
  const imgRef = useRef<HTMLImageElement>(null);
  const [transform, setTransform] = useState<CropTransform>(INITIAL_CROP);
  // 드래그 중에는 :hover가 서지 않는다 — 파일을 끌어오는 내내 브라우저가 hover를
  // 주지 않아 CSS만으로는 「여기 놓으면 된다」를 보여 줄 수 없다. 직접 든다.
  const [dragOver, setDragOver] = useState(false);

  // 새 그림이 오면 잘라내기를 처음부터 — 앞 사진의 위치·확대가 남아 있으면 엉뚱하게 잘린다.
  // 토스트의 「편집」으로 되돌아올 때는 imageSrc가 그대로라 직전 위치를 지킨다.
  useEffect(() => {
    setTransform(INITIAL_CROP);
  }, [photo.imageSrc]);

  // 단계가 바뀌면 표시를 걷는다 — 드롭 직후 dragleave가 안 오는 경우가 있어
  // 그대로 두면 업로드·크롭으로 넘어간 뒤에도 강조가 남는다.
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
    if (img === null || source === null) return;
    photo.apply(cropToDataUrl(img, transform, source));
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
    <Dialog open={photo.step !== 'idle'} onOpenChange={(next) => !next && photo.close()}>
      <Dialog.Content className={styles.photoDialog}>
        <div className={styles.photoHead}>
          <Dialog.Title className={styles.photoTitle}>프로필 사진</Dialog.Title>
          <span className={styles.photoStep}>{photo.stepLabel}</span>
          <IconButton
            variant="ghost"
            size="sm"
            aria-label="닫기"
            className={styles.photoClose}
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
          </div>
        )}

        {photo.step === 'error' && (
          <div className={styles.photoBody}>
            {/* 모달 전체가 아니라 드롭존만 흔든다 — 원인이 어디인지 짚어 주려고 (1p) */}
            <div
              role="alert"
              className={styles.errorZone}
              data-shaking={photo.shaking || undefined}
              {...dropTargetProps}
            >
              <AlertCircle aria-hidden className={styles.errorIcon} />
              <span className={styles.errorTitle}>{photo.errorTitle}</span>
              <span className={styles.errorHint}>
                5MB 이하로 줄여 다시 끌어다 놓거나 클릭해 선택해 주세요
              </span>
            </div>
            <div className={styles.presetRow}>
              <span className={styles.presetLabel}>잠시 후 선택 화면으로 돌아갑니다</span>
              {presets}
            </div>
          </div>
        )}

        {photo.step === 'uploading' && (
          <div className={styles.photoBody}>
            <div className={styles.uploadZone}>
              <Spinner size="md" label="업로드 중" />
              <span className={styles.uploadTitle}>사진을 올리는 중이에요</span>
              <span className={styles.uploadBar}>
                <Progress value={photo.progress} size="sm" label="업로드 진행률" />
              </span>
              <span className={styles.uploadFile}>{photo.fileLabel}</span>
            </div>
            <div className={styles.uploadActions}>
              <Button variant="ghost" size="sm" onClick={photo.cancelUpload}>
                취소
              </Button>
            </div>
          </div>
        )}

        {photo.step === 'crop' && photo.imageSrc !== null && (
          <>
            <div className={styles.cropBody}>
              <PhotoCropStage
                src={photo.imageSrc}
                transform={transform}
                onChange={setTransform}
                imgRef={imgRef}
              />
            </div>
            <div className={styles.cropActions}>
              <Button variant="outline" size="sm" onClick={photo.close}>
                취소
              </Button>
              <Button variant="solid" size="sm" onClick={applyCrop}>
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
