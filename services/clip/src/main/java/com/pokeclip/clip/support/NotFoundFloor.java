package com.pokeclip.clip.support;

import jakarta.servlet.http.HttpServletRequest;

import java.time.Duration;

/**
 * 404를 <b>정해진 시각 전에는 내보내지 않는</b> 자리. <b>사람 문 일곱이 나눠 쓴다</b> —
 * 세그먼트 창 조회 · 카드 목록 · 통로(SSE) · 카드를 만지는 문 넷(집기·놓기·숨기기·되돌리기).
 *
 * <p><b>왜 있나.</b> 이 문들의 404는 두 갈래다 — 「없는 것」은 명부 조회 하나로 끝나고,
 * 「실재하지만 볼 자격이 없다」는 auth에 HTTP로 물은 뒤에야 끝난다. 본문은 바이트 단위로 같지만
 * <b>시간이 갈렸다</b>: 실기동 1,240회에서 중앙값 <b>1.488ms 대 4.422ms</b>였고, 한 번만 재도
 * 99.5%가 구분됐으며 <b>느린 쪽을 빠른 쪽으로 오독한 경우는 0건</b>이었다. 즉 「느리면 실재한다」가
 * 한 번도 안 틀렸다. 그러면 남의 방송 번호를 넣어 보는 것만으로 그 방송의 실재를 알 수 있고,
 * 404 둘을 한 본문으로 합쳐 둔 것이 통째로 무의미해진다.
 *
 * <p><b>더하는 것이 아니라 바닥이다.</b> 두 갈래에 같은 시간을 <i>더하면</i> 차이가 그대로 남는다
 * (1.5+D 대 4.4+D). 그래서 <b>기준 시각부터 {@link #FLOOR}가 찰 때까지 기다린다</b> —
 * 빠른 갈래가 느린 갈래를 기다리는 셈이라 둘 다 바닥에서 나간다.
 *
 * <p><b>{@link #FLOOR}를 25ms로 고른 근거.</b> 느린 갈래의 실측 분포가 p90 5.997ms ·
 * p99 10.242ms · <b>최대 21.160ms</b>였다(1,000회, load average 5인 머신이라 한가한 머신보다
 * 꼬리가 두껍다). 바닥이 그 최대보다 커야 평상시 변동이 전부 덮인다. 반대로 길게 잡을수록
 * 404 하나가 톰캣 스레드를 그만큼 쥔다 — 25ms면 스레드 하나가 초당 40건, 기본 200스레드로
 * <b>초당 8,000건</b>이라 설계 전제(동시 100명)의 몇 배다. 그 사이에서 고른 값이다.
 *
 * <p><b>왜 예외 핸들러에서 기다리나.</b> 응답 본문은 핸들러가 값을 <b>돌려준 뒤</b>에 쓰이므로,
 * 거기서 기다리면 응답 전체가 늦는다({@code SegmentControllerTest}의 바닥 갈래 둘이 그것을 잰다.
 * 기준 시각이 <b>갈리기 전</b>에 찍혔는지는 {@code auth가_느려도_두_404가_같은_시각에_나간다} 하나가
 * 잰다 — 바닥 갈래 둘은 {@link #mark}를 지워도 초록이다, 실측).
 * 필터에서 체인이 끝난 뒤에 기다리는 방법은 그 시점에 응답이 이미 커밋됐을 수 있어 고르지 않았다 —
 * <b>재 보지는 않았다.</b> 대신 기다리는 동안 톰캣 스레드를 쥐는 비용이 있고, 위 25ms 계산이 그 비용이다.
 *
 * <p>🔴 <b>완전한 방어가 아니다. 평상시만 막는다.</b>
 * <ul>
 *   <li><b>auth가 아프면 도로 갈린다.</b> 자격 창구의 시한이 connect 2초 + read 5초라, 그쪽이
 *       느려지면 느린 갈래가 바닥을 훌쩍 넘어 고정 지연으로 못 덮는다. 그 상황에서 실재는 다시 샌다</li>
 *   <li><b>밀리초 아래는 안 맞춘다.</b> 남은 시간을 밀리초로 <b>올림</b>해 자므로 두 갈래 모두
 *       바닥 위에서 깨어나지만, 깨어나는 시각 자체의 오차는 남는다. 그 오차는 갈래와 무관한
 *       잡음이고, 감추려는 신호가 3ms였다.
 *       <br>🔴 <b>그 올림을 재는 시험은 없다</b> — 내림으로 바꿔 봤더니 스물일곱 갈래가 전부
 *       초록이었다. 잠에서 깨는 오차(MockMvc 실측으로 바닥 25ms에 총 32ms)가 올림이 막는
 *       잔여(1ms 미만)보다 크기 때문이다. 올림을 남긴 것은 방향이 옳고 공짜여서지,
 *       그물이 있어서가 아니다</li>
 *   <li>🔴 <b>본문이 같아야 시간을 맞추는 뜻이 생긴다.</b> 이 장치는 <b>시간만</b> 맞춘다 —
 *       두 갈래가 다른 본문을 내면 읽는 쪽은 한 줄로 답을 얻고 바닥은 순수한 비용이 된다.
 *       카드 문 넷이 판정 실패를 {@code JumpCardNotFoundException}으로 접는 이유가 그것이다
 *       ({@code JumpCardService.requireViewableCard})</li>
 *   <li><b>이 문의 404만이다.</b> 410·503·400은 안 건드린다 — 410은 자격이 확인된 사람에게만 가고,
 *       503은 장애 신호이며, 400은 형식 오류라 방송의 존재를 말하지 않는다.
 *       🔴 <b>「{@code JumpCardExceptionHandler}의 404는 갈래가 하나뿐이라 맞출 짝이 없다」고
 *       적어 뒀던 것을 POK-174가 깼다</b> — 카드 목록 문에 자격 판정이 붙으면서 그쪽도 갈래가
 *       둘이 됐고(없는 방송은 DB 조회만, 자격 없음은 auth 왕복), 그래서 이 클래스가
 *       {@code segment.api}에서 여기로 옮겨 왔다. 지금 그 조언은 {@link #awaitFloorIfMarked}를 쓴다</li>
 * </ul>
 */
public final class NotFoundFloor {

    /** 이 문의 404는 기준 시각으로부터 이보다 빨리 나가지 않는다. 값의 근거는 클래스 주석. */
    public static final Duration FLOOR = Duration.ofMillis(25);

    private static final String START_NANOS = NotFoundFloor.class.getName() + ".startNanos";

    private NotFoundFloor() {
    }

    /** 기준 시각을 찍는다. 두 갈래가 갈리기 <b>전</b>이어야 하므로 컨트롤러가 서비스를 부르기 전에 부른다. */
    public static void mark(HttpServletRequest request) {
        request.setAttribute(START_NANOS, System.nanoTime());
    }

    /**
     * 기준 시각부터 {@link #FLOOR}가 찰 때까지 기다린다. 이미 찼으면 그냥 나간다.
     *
     * <p>기준이 없으면 <b>바닥 전체</b>를 기다린다 — 늦는 쪽이 안전한 방향이다(일찍 내보내는 것이
     * 곧 유출이다). 지금 코드에서 이 메서드는 {@code SegmentController}를 지나온 요청에서만 불리므로
     * 기준은 항상 찍혀 있지만, 그것을 근거로 갈래를 지우지 않는다.
     *
     * <p>기준이 없을 때 <b>즉시 나가야</b> 하는 자리는 {@link #awaitFloorIfMarked}다 — 그쪽에
     * 왜 반대 방향인지를 적었다.
     */
    public static void awaitFloor(HttpServletRequest request) {
        await(remainingNanos(request, FLOOR.toNanos()));
    }

    /**
     * 같은 바닥이되 <b>기준이 없으면 즉시 나간다</b>. {@link #awaitFloor}와 정확히 그 한 갈래가 다르다.
     *
     * <p><b>왜 반대 방향인가.</b> 이것을 쓰는 {@code JumpCardExceptionHandler}는 범위를 안 좁힌
     * <b>전역</b> 조언이라 사람 문뿐 아니라 <b>판별기가 부르는 내부 문</b>
     * ({@code POST /internal/broadcasts/&#123;streamId&#125;/highlights})의 404도 받는다. 그쪽에는
     * <b>감출 존재가 없고</b>(서버 간 토큰이라 방송 이름을 훑어 볼 상대가 아니다) 판별기는 404를
     * 재시도 상한으로 세므로, 바닥을 태우면 <b>순수한 비용</b>이다.
     *
     * <p>🔴 <b>대가는 「사람 문을 새로 내면서 {@link #mark}를 안 찍으면 조용히 안 늦는다」는 것이다.</b>
     * {@link #awaitFloor}는 그 실수를 늦는 쪽으로 접었는데 이쪽은 아니다. 그래서 <b>덜 안전한 쪽을
     * 고른 자리</b>이고, 그것을 잡는 것은 코드가 아니라 시험이다 —
     * {@code JumpCardListControllerTest.auth가_느려도_두_404가_같은_시각에_나간다}가 사람 문 쪽을,
     * {@code 내부_문의_404는_바닥에_안_묶인다}가 이 갈래 자체를 잰다(뒤엣것은 사람 문 404를 같이 재는
     * <b>대조</b>를 갖는다 — 안 그러면 바닥이 통째로 죽어도 초록이다).
     */
    public static void awaitFloorIfMarked(HttpServletRequest request) {
        await(remainingNanos(request, 0));
    }

    /** 기준이 없을 때 무엇을 기다릴지는 부르는 쪽이 정한다 — 그것이 두 갈래의 유일한 차이다. */
    private static long remainingNanos(HttpServletRequest request, long 기준이_없을_때) {
        return request.getAttribute(START_NANOS) instanceof Long startNanos
                ? FLOOR.toNanos() - (System.nanoTime() - startNanos)
                : 기준이_없을_때;
    }

    /**
     * 🔴 <b>인터럽트를 삼키지 않는다.</b> 삼키면 종료 중에 들어온 요청이 스레드 종료를 막는다.
     * 깃발을 되살리고 즉시 나가므로 <b>그 요청 하나는 바닥을 못 채운다</b> — 서버가 내려가는
     * 중에만 벌어지는 일이라 그대로 둔다.
     */
    private static void await(long remainingNanos) {
        if (remainingNanos <= 0) {
            return;
        }
        try {
            // 올림이다. 내림하면 갈래마다 다른 잔여(1.5ms · 4.4ms)가 응답 시각에 그대로 남는다.
            Thread.sleep((remainingNanos + 999_999) / 1_000_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
