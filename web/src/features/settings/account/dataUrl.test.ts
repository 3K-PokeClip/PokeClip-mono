import { describe, expect, it } from 'vitest';
import { dataUrlToBlob } from './dataUrl';

/** jsdom의 Blob은 Node Response가 못 읽는다(다른 클래스) — 브라우저 API인 FileReader로 되읽는다. */
function bytesOf(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error);
    reader.readAsText(blob);
  });
}

describe('dataUrlToBlob', () => {
  it('base64 data URL을 같은 바이트의 Blob으로 되돌리고 형식은 주소에서 읽는다', async () => {
    const out = dataUrlToBlob(`data:image/png;base64,${btoa('png-bytes')}`);

    expect(out).not.toBeNull();
    expect(out?.blob.type).toBe('image/png');
    expect(out?.blob.size).toBe('png-bytes'.length);
    expect(await bytesOf(out?.blob ?? new Blob())).toBe('png-bytes');
    expect(out?.filename).toBe('avatar.png');
  });

  it('JPEG·WebP 이름표도 주소를 따른다 — 폴백으로 원본이 그대로 왔을 때 PNG로 속이지 않는다', () => {
    expect(dataUrlToBlob(`data:image/jpeg;base64,${btoa('jpg')}`)?.filename).toBe('avatar.jpg');
    expect(dataUrlToBlob(`data:image/webp;base64,${btoa('webp')}`)?.filename).toBe('avatar.webp');
    expect(dataUrlToBlob(`data:image/gif;base64,${btoa('gif')}`)?.filename).toBe('avatar.bin');
  });

  it('base64가 아니거나 비어 있으면 null이다 — atob이 던지지 않는다', () => {
    expect(dataUrlToBlob('data:original')).toBeNull(); // 형식·payload가 없는 주소
    expect(dataUrlToBlob('data:image/png;base64,')).toBeNull();
    expect(dataUrlToBlob('data:image/png;base64,A')).toBeNull(); // 길이 4k+1
    expect(dataUrlToBlob('data:image/png,plain')).toBeNull();
    expect(dataUrlToBlob('https://example.test/a.png')).toBeNull();
  });
});
