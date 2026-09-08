package com.pokeclip.chat.collector.chzzk;

/**
 * 채팅 한 건. <b>시각은 messageTime(epoch ms)만 쓴다</b> —
 * eventSentAt은 오프셋 없는 KST라 UTC로 파싱하면 9시간 어긋나고 오류도 안 난다.
 *
 * <p>{@code raw}는 치지직이 보낸 안쪽 JSON 문자열 <b>그대로</b>다(이중 인코딩의 안쪽,
 * 이스케이프가 풀린 텍스트). S3 아카이브(POK-116)가 이것을 손대지 않고 쌓는다 —
 * 닉네임·이모티콘·배지처럼 여기서 안 뽑는 필드가 기준값 산출에 쓰인다.
 * <b>로그에 넘기지 마라</b> — content와 같은 규칙이다.
 *
 * <p>🔴 <b>{@code userRole}은 실물에 안 온다 — 「문서에 있으니 온다」가 거짓이었다</b>
 * (2026-09-08 실기동). 실방송 채팅 <b>70건 전부</b> {@code user_role}이 NULL이라
 * 원문 프레임을 직접 받아 확인했다. CHAT 봉투의 칸은 여덟이고
 * ({@code channelId} · {@code chatChannelId} · {@code senderChannelId} ·
 * {@code profile}(nickname · verifiedMark · badges) · {@code content} · {@code emojis} ·
 * {@code messageTime} · {@code eventSentAt}) <b>{@code userRoleCode}가 그 안에 없다.</b>
 * 반대로 {@code eventSentAt}은 문서 표에 없는데 온다.
 *
 * <p><b>코드는 옳다 — 고치지 마라.</b> 없으면 null로 두게 이미 짜여 있고 영향도 없다
 * (화면이 역할을 안 쓴다). <b>틀린 것은 전제였다.</b> 치지직이 나중에 보내기 시작하면
 * 이 칸은 <b>저절로</b> 채워진다 — 그때를 위해 뽑는 코드를 지우지 않는다.
 * <b>「70건 전부 NULL」을 결함으로 읽고 디코더를 파헤치지 마라</b>(그것이 이 문단의 목적이다).
 *
 * <p><b>안 고친 자리 하나</b>: {@code V306}의 {@code COMMENT ON COLUMN}이 아직 문서에서 옮겨 온
 * 역할 코드 넷을 그대로 싣고 있다. 고치려면 그 마이그레이션의 Flyway 체크섬이 바뀌어
 * <b>V306을 이미 돌린 로컬 DB가 부팅을 거부하고</b>(실기동이 그 DB에 이미 돌렸다),
 * 새 마이그레이션을 얹으면 이 커밋이 DDL 변경이 된다. <b>그래서 안 골랐다.</b>
 * 표 코멘트를 믿는 사람이 생기면 그때 별도 마이그레이션으로 고친다.
 *
 * <p>toString을 따로 두지 않는다. record 기본 toString이 content·raw·nickname을 통째로
 * 찍는데, 로그에 실수로 객체를 넘기면 그대로 평문이 나간다. ChatLogLeakTest가 못박는다.
 * <b>닉네임도 개인식별값이라 content와 같은 규칙이다.</b>
 *
 * @param nickname 치지직 profile.nickname. <b>없으면 null</b> — 빈 문자열로 접으면
 *                 「이름이 빈 사람」과 못 가른다
 * @param userRole 치지직 userRoleCode(streamer·common_user·streaming_channel_manager·
 *                 streaming_chat_manager). 없으면 null.
 *                 <b>🔴 공식 문서 표에는 있으나 2026-09-08 실측에서 실물 프레임에 없었다</b> —
 *                 위 클래스 주석 참고. 그래서 이 칸은 사실상 늘 null이다
 */
public record ChatMessage(String channelId, String senderChannelId,
                          String content, long messageTimeMillis, String raw,
                          String nickname, String userRole) { }
