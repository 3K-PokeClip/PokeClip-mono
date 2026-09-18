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
  no_key: '연결되지 않아 이 방송은 PokeClip으로 전송되지 않아요.',
  encoder_active: '녹화가 방송 인코더를 쓰고 있어요. 녹화를 멈추거나 녹화 인코더를 분리하세요.',
  multitrack_video: '멀티트랙 비디오는 지원하지 않아요. 설정 › 방송에서 끄세요.',
  no_stream_output: 'OBS 방송 출력이 준비되지 않았어요.',
  no_video_encoder: '방송 비디오 인코더가 없어요.',
  no_audio_encoder: '방송 오디오 인코더가 없어요.',
  keyint_not_applied: '키프레임 간격 2초를 적용하지 못했어요.',
  invalid_key: '저장된 키가 올바르지 않아요. 다시 연결하세요.',
  no_shared_encoder: '방송 인코더를 공유하지 못했어요.',
  output_create_failed: 'SRT 출력을 만들지 못했어요.',
  service_create_failed: 'SRT 서비스를 만들지 못했어요.',
  start_failed: 'SRT 출력을 시작하지 못했어요.',
  main_stream_failed: '본방이 시작되지 않아 PokeClip 전송도 멈췄어요.',
  bad_path: '수신 서버가 암호 또는 주소를 거절했어요.',
  connect_failed: '수신 서버가 연결을 거절했어요.',
  timeout: 'PokeClip 수신 서버가 응답하지 않아요.',
  disconnected: '연결이 끊겼어요. 재시도를 모두 실패했어요.',
  invalid_stream: '스트림이 올바르지 않아요.',
  output_error: '출력 오류가 났어요.',
  encode_error: '인코더 오류가 났어요.',
  invalid_json: '요청 형식이 올바르지 않아요.',
  invalid_api_base: 'API 주소는 http:// 또는 https:// 로 시작해야 해요.',
  invalid_ingest_host: '수신 호스트 형식이 올바르지 않아요.',
  invalid_ingest_port: '포트는 1–65535 사이여야 해요.',
  invalid_latency: '지연은 20–8000ms 사이여야 해요.',
  unauthorized: '독 페이지 인증이 만료됐어요. OBS를 다시 시작하세요.',
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
