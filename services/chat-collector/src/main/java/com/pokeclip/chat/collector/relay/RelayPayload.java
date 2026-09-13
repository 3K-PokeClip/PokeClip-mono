package com.pokeclip.chat.collector.relay;

import java.time.Instant;

/**
 * clip으로 중계할 한 건의 <b>내용</b>. 번호가 없다 — 번호는 {@link RelayBuffer}만 붙인다
 * (계획 검증 F9). 부르는 쪽이 번호를 들 수 없게 타입으로 막는다.
 *
 * <p><b>칸 이름과 시각 축이 {@code query.ChatWindowItem}과 같다</b>(main 결정 F3). 프론트는
 * 범위 창구로 메운 구간과 SSE가 겹칠 때 {@code (kind, time, senderChannelId, text)}로 거르는데,
 * 한쪽 값이 다르면 같은 채팅이 두 번 뜬다. 그래서:
 * <ul>
 *   <li>{@code time}은 <b>표에 저장되는 값</b>이다 — 채팅은 {@code message_time}(치지직 시각),
 *       후원은 {@code received_at}(우리가 받은 시각). 화면 축이 아니다</li>
 *   <li>문자열 칸은 표와 같게 정규화한다 — NUL 제거(PG가 거부해 표에는 NUL이 없다) ·
 *       후원의 null 접기({@code PersistableDonation}과 같다)</li>
 * </ul>
 *
 * <p>toString을 따로 두지 않는다 — record 기본 toString이 본문·닉네임을 통째로 찍으므로
 * 로그에 객체를 넘기지 마라({@code ChatMessage}·{@code PersistableChat}과 같은 규칙).
 *
 * <p><b>방송 정보(broadcast-info)는 여기 없다</b>(F8) — PR-C가 더한다. 그때 직렬화의
 * {@code switch}가 컴파일에서 멈춰 짚어 준다.
 */
public sealed interface RelayPayload permits RelayPayload.Chat, RelayPayload.Donation, RelayPayload.Info {

    /** SSE 이벤트 이름이자 창구 {@code kind}. */
    String kind();

    /** {@code message}(치지직 시계) 또는 {@code received}(우리 시계). 창구와 같은 낱말이다. */
    String timeBasis();

    Instant time();

    record Chat(Instant time, String nickname, String senderChannelId, String role, String text)
            implements RelayPayload {

        public Chat {
            nickname = stripNul(nickname);
            senderChannelId = stripNul(senderChannelId);
            role = stripNul(role);
            text = stripNul(text);
        }

        @Override
        public String kind() {
            return "chat";
        }

        @Override
        public String timeBasis() {
            return "message";
        }
    }

    /**
     * @param senderChannelId 후원자 채널({@code donatorChannelId}). 창구도 이 칸 이름으로 준다
     * @param amount          숫자로 못 읽었으면 null — 표와 같다
     */
    record Donation(Instant time, String nickname, String senderChannelId, Long amount,
                    String donationType, String text) implements RelayPayload {

        public Donation {
            nickname = stripNul(nickname);
            // 🔴 아래 셋은 null을 빈 문자열로 접는다 — PersistableDonation 생성자와 같은 규칙이다
            // (표가 NOT NULL). 여기만 null로 두면 창구는 ""를, SSE는 null을 줘 겹침 열쇠가 갈린다.
            senderChannelId = senderChannelId == null ? "" : stripNul(senderChannelId);
            donationType = donationType == null ? "" : stripNul(donationType);
            text = text == null ? "" : stripNul(text);
        }

        @Override
        public String kind() {
            return "donation";
        }

        @Override
        public String timeBasis() {
            return "received";
        }
    }

    /**
     * 방송 정보 한 시점(POK-234 PR-C). 시각은 <b>우리가 물어본 시각</b>이다 — 치지직이 관측 시각을 안 준다.
     * 창구 칸(nickname 등)은 비고 {@code title}·{@code tags}·{@code category}·{@code viewers}가 찬다.
     * {@code viewers}의 {@code null}은 「목록에서 못 찾았다」이지 0명이 아니다({@code BroadcastInfo} 주석).
     */
    record Info(Instant time, String title, java.util.List<String> tags, String category, Integer viewers)
            implements RelayPayload {

        public Info {
            title = stripNul(title);
            category = stripNul(category);
            tags = tags == null ? java.util.List.of()
                    : tags.stream().filter(java.util.Objects::nonNull).map(RelayPayload::stripNul).toList();
        }

        @Override
        public String kind() {
            return "broadcast-info";
        }

        @Override
        public String timeBasis() {
            return "observed";
        }
    }

    private static String stripNul(String value) {
        return value == null ? null : value.replace("\0", "");
    }
}
