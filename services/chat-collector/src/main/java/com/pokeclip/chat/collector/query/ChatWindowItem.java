package com.pokeclip.chat.collector.query;

import java.time.Instant;

/**
 * 목록 한 줄. 채팅과 후원이 <b>한 목록에</b> 섞여 나가므로 칸이 합집합이다.
 *
 * @param kind       {@code chat} 또는 {@code donation}
 * @param id         <b>그 표의 PK다</b>(채팅과 후원이 각자 1부터 센다) — 종류와 짝지어야
 *                   유일하다. 중계·SSE가 쓰는 {@code seq}와 <b>다른 축</b>이니
 *                   프론트가 두 창구의 값을 같은 번호로 짝지으면 안 된다(문항 9)
 * @param time       <b>표에 찍힌 원본 시각</b>이다. 화면 위치는
 *                   {@code time − appliedOffsetMs}로 얻는다({@link ChatWindowQuery} 축 표)
 * @param timeBasis  {@code message}(치지직이 찍은 시각) 또는 {@code received}(우리 기계가 받은 시각).
 *                   <b>후원은 치지직이 시각을 안 줘서 {@code received}뿐이다.</b> 두 시계의 차는
 *                   전달 지연(175ms)만이 아니라 <b>기계 시계 오프셋까지 포함</b>한다 —
 *                   POK-92 실측에서 이 기계가 4초 느렸다. 칸으로 밝히지 않으면 후원이 채팅 사이
 *                   엉뚱한 자리에 끼어드는 것을 아무도 못 본다
 * @param amount     후원 금액(원). 채팅이거나 숫자로 못 읽은 금액이면 {@code null}
 * @param donationType 후원 종류. 채팅이면 {@code null}
 */
public record ChatWindowItem(String kind, long id, Instant time, String timeBasis,
                             String nickname, String senderChannelId, String role,
                             String text, Long amount, String donationType) {
}
