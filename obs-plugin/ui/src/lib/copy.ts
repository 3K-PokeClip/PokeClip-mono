import type { AudioKind, MarkStats } from './types';

// 사용자 문구 — 플러그인 data/locale/ko-KR.ini 의 Reason.* 과 같은 뜻을 유지한다.
export const REASON: Record<string, string> = {
  invalid_format: '8자리 코드(XXXX-XXXX)를 입력하세요.',
  not_found: '코드를 찾을 수 없어요. 확인하거나 새로 발급하세요.',
  expired: '코드가 만료됐어요. 새로 발급하세요.',
  already_used: '이미 사용한 코드예요. 새로 발급하세요.',
  rate_limited: '시도가 너무 많아요. 1분 뒤 다시 시도하세요.',
  streaming: '방송을 멈춘 뒤 바꿀 수 있어요.',
  network: 'PokeClip에 연결할 수 없어요. 네트워크를 확인하세요.',
  server_error: 'PokeClip 서버 오류예요. 잠시 뒤 다시 시도하세요.',
  bad_response: '서버 응답이 예상과 달라요.',
  save_failed: '설정을 저장하지 못했어요.',
  server_changed: '연결하는 사이 서버가 바뀌었어요. 바뀐 서버에서 새 코드를 발급하세요.',
  no_key: '연결되지 않아 이 방송은 PokeClip으로 전송되지 않아요.',
  encoder_active: '키프레임 2초를 적용하지 못했어요. 녹화를 멈추거나 본 방송을 다시 시작하세요.',
  multitrack_video: '멀티트랙 비디오는 지원하지 않아요. 설정 › 방송에서 끄세요.',
  no_stream_output: 'OBS 방송 출력이 준비되지 않았어요.',
  no_video_encoder: '방송 비디오 인코더가 없어요.',
  no_audio_encoder: '방송 오디오 인코더가 없어요.',
  keyint_not_applied: '키프레임 간격 2초를 적용하지 못했어요.',
  invalid_key: '저장된 키가 올바르지 않아요. 연결을 해제한 뒤 새 코드를 입력하세요.',
  no_shared_encoder: '방송 인코더를 공유하지 못했어요.',
  output_create_failed: 'SRT 출력을 만들지 못했어요.',
  service_create_failed: 'SRT 서비스를 만들지 못했어요.',
  start_failed: 'SRT 출력을 시작하지 못했어요.',
  main_stream_failed: '본방이 시작되지 않아 PokeClip 전송도 멈췄어요.',
  // SRT 정지 코드 — 실제 원인대로 적는다(obs-ffmpeg-srt.h). bad_path는 주소(DNS)를 못 찾은 것이고, 서버 무응답과
  // 거절(키 폐기·암호 오류·경로 없음)은 모두 connect_failed라 플러그인에서 가를 수 없다.
  bad_path: '수신 서버 주소를 찾지 못했어요. 인터넷 연결을 확인하세요.',
  connect_failed: '수신 서버에 연결하지 못했어요.',
  timeout: 'PokeClip 수신 서버가 응답하지 않아요.',
  disconnected: '수신 서버와의 연결이 끊겼어요.',
  invalid_stream: '스트림이 올바르지 않아요.',
  output_error: '출력 오류가 났어요.',
  encode_error: '인코더 오류가 났어요.',
  invalid_json: '요청 형식이 올바르지 않아요.',
  invalid_api_base: 'API 주소는 http:// 또는 https:// 로 시작해야 해요.',
  invalid_ingest_host: '수신 호스트 형식이 올바르지 않아요.',
  invalid_ingest_port: '포트는 1–65535 사이여야 해요.',
  invalid_latency: '지연은 20–8000ms 사이여야 해요.',
  invalid_server_env: '알 수 없는 서버 환경이에요.',
  unauthorized: '독 페이지 인증이 만료됐어요. OBS를 다시 시작하세요.',
  output_no_multitrack: 'OBS 출력이 다중 오디오 트랙을 받지 않아요.',
  audio_encoder_failed: '오디오 트랙 인코더를 만들지 못했어요.',
  audio_track_attach_failed: '오디오 트랙을 출력에 붙이지 못했어요.',
  mark_not_live: 'PokeClip으로 전송 중일 때만 표시할 수 있어요.',
  mark_too_soon: '방금 표시했어요. 2초 뒤에 다시 누를 수 있어요.',
  // POK-494·495
  busy: 'OBS가 바빠요. 잠시 뒤 다시 해 주세요.',
  hotkey_needs_modifier: '글자·숫자 키는 Ctrl이나 Alt(맥은 ⌃·⌥·⌘)와 함께 눌러 주세요. 혼자 쓰면 다른 앱에서 칠 때마다 표시돼요.',
  hotkey_bad_key: '이 키는 단축키로 쓸 수 없어요. 글자·숫자·F 키 중에서 골라 주세요.',
  hotkey_unavailable: 'OBS 단축키를 아직 쓸 수 없어요. OBS를 다시 켠 뒤 해 주세요.',
  no_code_in_clipboard: '복사한 내용에 연결 코드가 없어요. PokeClip 설정 › 플러그인에서 코드를 다시 복사하세요.',
  mark_unsupported: 'PokeClip 서버가 아직 순간 표시를 받지 않아요.',
  mark_unauthorized: '서버가 스트림 키를 거절해 표시하지 못했어요. 연결을 해제한 뒤 새 코드를 입력하세요.',
  mark_rejected: '서버가 표시 요청을 거절했어요.',
  mark_no_broadcast: 'PokeClip이 아직 이 방송을 찾지 못했어요.',
  mark_not_ready: '녹화가 막 시작돼 아직 표시할 자리가 없어요.',
  mark_expired: '10분 동안 보내지 못해 표시를 버렸어요.',
  mark_rate_limited: 'PokeClip 서버가 잠시 표시를 받지 않아요.',
  mark_insecure: 'API 주소가 https가 아니라 표시를 보내지 않았어요. 설정 파일의 api_base를 https로 바꾸세요.',
  // 독 전용 — 브리지 요청 자체가 실패했다(플러그인이 멈췄거나 연결이 끊겼다)
  bridge_unreachable: '플러그인과 연결이 끊겼어요. OBS를 다시 시작하세요.',
  // 독 전용 — 새 단축키를 받는 동안 지금 키를 막아 달라는 요청을 플러그인이 받지 않았다
  hotkey_capture_failed: '새 단축키를 받을 준비를 하지 못했어요. 잠시 뒤 다시 해 주세요.',
  // 독 전용 — 「다시 연결」·「재시도 중지」를 플러그인이 받지 않았다(누르는 사이 상태가 바뀌었다)
  main_not_live: '본 방송이 나가는 동안에만 보낼 수 있어요.',
  send_unavailable: '지금은 전송을 시작할 수 없어요.',
  not_retrying: '지금은 재시도 중이 아니에요.',
  // 독 전용 — 손 배정(POK-266)을 플러그인이 받지 않았다
  audio_off: '자동 배정이 적용 중일 때만 트랙을 고를 수 있어요.',
  unknown_source: '그 소스를 지금 찾을 수 없어요.',
  main_stream_track: '본방 트랙에는 넣을 수 없어요. OBS에서 직접 고르세요.',
  bad_track: '트랙은 2~6 사이여야 해요.',
};

// 빠른 거절이 이어질 때 — 키 폐기·암호 오류·경로 없음은 플러그인에서 가를 수 없고, auth 장애·운영자 차단도 같은
// 거절로 온다(POK-287). 그래서 확정이 아니라 가능성으로 쓰고 확인을 권한다.
export const KEY_SUSPECT_HINT = '수신 서버가 계속 거절해요. 스트림 키 문제일 수 있어요. 웹에서 확인하세요.';

// 키 확인 안내를 남긴 채 멈췄을 때(재시도 중지·포기) 설명줄 앞에 붙인다 — 재페어링해도 안 풀리는 거절(auth 장애·
// 운영자 차단)도 있어서, 키를 바로잡았는데도 그대로면 키 말고 다른 곳을 보게 한다. 폴백 locale Retry.KeyHintNext와 같다.
export const KEY_SUSPECT_NEXT = '키가 맞는데도 계속 거절되면 PokeClip 쪽 문제일 수 있어요.';

// 송출 점검의 해상도 경고에 붙는 안내 — 1080p는 권장값이라(ADR-020) 어긋나도 막지 않는다. 점검값은 다음 본방 시작까지
// 남아 전송이 멈춘 뒤에도 보이므로 「지금 전송 중」을 단정하지 않는 말로 쓴다. 폴백 패널(locale Check.ResAdvisory)과 같은 문구.
export const RES_ADVISORY = '1080p를 권장해요. 다른 해상도여도 전송을 막지 않아요.';

// 연결 없이 본방을 시작했을 때의 토스트. 동기화가 켜져 있으면 플러그인이 방송 중 연결에 대비해 본방 키프레임 간격을
// 미리 2초로 맞추는데, 점검 카드는 연결된 뒤에만 보이므로 바꾼 사실을 여기서 같이 알린다.
export function noKeyToast(gopApplied: boolean): string {
  return gopApplied ? `${REASON.no_key} 키프레임 간격은 2초로 맞춰 뒀어요.` : REASON.no_key;
}

export type ToastTone = 'success' | 'warning';

// 마크 결과(state.marks.result) → 토스트. 결과가 없으면 null.
export function markToast(marks: MarkStats): { tone: ToastTone; message: string } | null {
  switch (marks.result) {
    case 'sent':
      return { tone: 'success', message: '지금 이 순간을 표시했어요.' };
    case 'retrying':
      return { tone: 'warning', message: `${reasonText(marks.reason)} 자동으로 다시 보내요.` };
    case 'failed':
    case 'rejected':
      return { tone: 'warning', message: reasonText(marks.reason) };
    default:
      return null;
  }
}

// 오디오 트랙 목록의 소스 종류 표기 (src/audio-assign.cpp AudioKindName 과 같은 키).
export const AUDIO_KIND_LABEL: Record<AudioKind, string> = {
  mic: '마이크',
  desktop: '데스크탑',
  app: '앱',
  media: '미디어',
  browser: '브라우저',
  other: '기타',
};

export function reasonText(code: string): string {
  if (!code) return '';
  return REASON[code] ?? `문제가 생겼어요 (${code})`;
}

export const PHASE_LABEL: Record<string, string> = {
  idle: '대기',
  starting: '연결 중',
  live: '전송 중',
  reconnecting: '재연결 중',
  stopping: '정지 중',
  error: '오류',
};
