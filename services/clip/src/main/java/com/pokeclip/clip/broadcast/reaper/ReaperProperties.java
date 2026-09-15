package com.pokeclip.clip.broadcast.reaper;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 놓친 방송 치우기(POK-244) 설정 — 켜기·주기·유예 셋이다.
 *
 * <p><b>기본이 꺼짐이다.</b> 이 장치는 종료 편지가 정상이면 한 번도 안 도는 안전망이고,
 * 잘못 켜면(유예를 너무 짧게) 살아있는 방송을 닫는다 — 켜는 것은 운영자의 결정이어야 한다.
 *
 * <p><b>유예의 하한이 왜 있나.</b> 1번 Media는 송출이 끊겨도 슬레이트를 300초 이어 조각을 계속
 * 만들고(계약-세그먼트인덱스 6-5), 종료 편지는 그 뒤 정산 창 15분이 지나서야 발행한다(계약9 게이트 ④).
 * 즉 마지막 조각 뒤 <b>15분 안에는 정상 편지가 아직 오는 중</b>이다. 유예가 그보다 짧으면 이 장치가
 * 편지보다 먼저 닫아 편지가 정상인데도 매 방송 두 번 닫힌다(두 번째는 순서 규칙으로 무해하지만
 * ended_at이 이 장치의 추정값으로 먼저 찍혀 화면이 일찍 「끝남」을 본다).
 */
@ConfigurationProperties(prefix = "pokeclip.broadcast.reaper")
@Validated
public record ReaperProperties(
        boolean enabled,
        /** 한 회차와 다음 회차 사이. 유예보다 훨씬 짧아야 「N분 뒤」가 N분 근처가 된다. */
        @NotNull Duration interval,
        /** 마지막 살아있는 신호 뒤 이만큼 조용하면 닫는다. */
        @NotNull Duration grace
) {

    /** 계약9의 종료 정산 창. 유예는 이보다 길어야 정상 편지와 경주하지 않는다. */
    static final Duration MIN_GRACE = Duration.ofMinutes(15);

    @PostConstruct
    void validate() {
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalStateException(
                    "pokeclip.broadcast.reaper.interval은 양수여야 한다(0이면 쉬지 않고 돈다). 지금 값: " + interval);
        }
        if (grace.compareTo(MIN_GRACE) < 0) {
            throw new IllegalStateException(
                    "pokeclip.broadcast.reaper.grace는 " + MIN_GRACE.toMinutes() + "분 이상이어야 한다 — "
                    + "종료 편지는 마지막 조각 뒤 정산 창 15분이 지나야 오므로(계약9), 그보다 짧으면 "
                    + "편지가 정상인 방송까지 이 장치가 먼저 닫는다. 지금 값: " + grace);
        }
        if (interval.compareTo(grace) >= 0) {
            throw new IllegalStateException(
                    "pokeclip.broadcast.reaper.interval(" + interval + ")은 grace(" + grace + ")보다 짧아야 한다.");
        }
    }
}
