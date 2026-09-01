// 크롭 결과(data URL)를 서버로 보낼 Blob으로 바꾼다 (POK-208).
//
// `fetch(dataUrl).blob()`을 쓰지 않는 이유: 테스트에서 fetch가 스텁이라 데이터 URL을 못 읽고,
// 실브라우저에서도 메모리 안의 문자열을 굳이 네트워크 층에 태울 이유가 없다. atob로 직접 푼다.
// cropImage.ts에 두지 않는다 — 그 파일은 좌표 계산만 갖고, Blob은 전송의 관심사다.

const DATA_URL = /^data:([\w.+-]+\/[\w.+-]+);base64,([A-Za-z0-9+/]*={0,2})$/;

/** 서버가 받는 셋의 확장자 — 그 밖의 형식은 서버가 415로 거절하므로 이름표만 bin으로 둔다. */
const EXTENSION: Record<string, string> = {
  'image/png': 'png',
  'image/jpeg': 'jpg',
  'image/webp': 'webp',
};

export interface DataUrlBlob {
  blob: Blob;
  /** multipart 파일명 — 서버는 안 보지만(내용으로 판정) 요청 로그가 읽기 좋다. */
  filename: string;
}

/**
 * base64 data URL만 받는다. **형식(mime)은 주소에서 읽는다** — 크롭 결과가 언제나 PNG라고
 * 단정하면 다른 형식이 흘러들 때 거짓 이름표를 붙여 보내게 된다(서버는 앞머리 바이트로
 * 판정하니 속지는 않지만, 우리 요청 로그가 거짓말을 한다).
 *
 * 형식이 안 맞거나 비어 있으면 `null` — atob이 던지게 두지 않는다. 호출부는 「이 사진은
 * 올릴 수 없어요」로 끊는다. 자르지 못한 경우는 여기까지 오지 않는다 — `cropToDataUrl`이
 * 그 자리에서 `null`을 준다.
 */
export function dataUrlToBlob(dataUrl: string): DataUrlBlob | null {
  const match = DATA_URL.exec(dataUrl);
  const mime = match?.[1];
  const payload = match?.[2];
  if (mime === undefined || payload === undefined || payload === '') return null;
  let binary: string;
  try {
    binary = atob(payload);
  } catch {
    return null; // 길이가 4의 배수에서 1 남는 등 base64가 아니다
  }
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return {
    blob: new Blob([bytes], { type: mime }),
    filename: `avatar.${EXTENSION[mime] ?? 'bin'}`,
  };
}
