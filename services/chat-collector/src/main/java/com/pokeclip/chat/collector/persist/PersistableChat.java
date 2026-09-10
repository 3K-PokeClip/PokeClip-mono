package com.pokeclip.chat.collector.persist;

/**
 * 표에 넣을 채팅 한 건. toString을 두지 않는다 — record 기본 toString이
 * content를 통째로 찍는데, 로그에 실수로 넘기면 평문이 나간다(ChatMessage와 같은 규칙).
 *
 * @param streamId 이 채팅이 어느 방송의 것인가. <b>null이 곧 「모른다」는 표시다</b> —
 *                 편지 없이 붙은 옛 경로(CHZZK_ENABLED)에는 방송 번호가 없다. 여기서
 *                 예외를 던지면 그 경로의 수집 자체가 죽으므로 비어 있는 것을 허용한다
 * @param nickname 수신 시점의 치지직 닉네임. <b>없으면 null</b>(옛 행·옛 경로).
 *                 바뀌는 값이라 지문 UNIQUE에는 안 들어간다
 * @param userRole 치지직 userRoleCode. 없으면 null
 */
public record PersistableChat(
        String streamId,
        String channelId,
        String senderChannelId,
        String content,
        long messageTimeMillis,
        long receivedAtMillis,
        String nickname,
        String userRole) {

    public PersistableChat {
        // PG TEXT가 거부하는 것은 사실상 NUL뿐 — 제거로 포이즌을 원천 소멸시키고
        // 채팅은 보존한다(2026-08-15 사용자 결정). 저장 직전(toRow)이 아니라 생성
        // 지점인 이유: 지문 해시와 저장이 <b>같은 정규화된 본문</b>을 써야 중복
        // 판정이 안 갈린다 — 여기 한 곳이면 그 불변식이 구조로 지켜진다.
        content = content.replace("\0", "");
        // 닉네임에도 같은 제거를 건다 — 안 걸면 닉네임 한 글자 때문에 그 배치가
        // 통째로 포이즌 격리(SQLSTATE 22021)로 넘어간다.
        nickname = nickname == null ? null : nickname.replace("\0", "");
        // 🔴 <b>역할도 지운다 — 이 부류가 네 번째다</b>(봇 claude). 후원 쪽에서 세 번
        // (닉네임·문구 → 종류 → 식별자 둘 → 방송 번호) 같은 실수를 고치면서 <b>채팅의
        // 이 칸을 안 봤다.</b> 디코더가 userRoleCode 를 검증 없이 그대로 넘긴다.
        // 지금은 치지직이 이 값을 안 보내 실무 영향이 0이지만, 보내기 시작하는 순간
        // NUL 한 글자가 22021 을 만들고 그 배치가 포이즌 격리로 넘어가 <b>채팅이 버려진다.</b>
        userRole = userRole == null ? null : userRole.replace("\0", "");
    }
}
