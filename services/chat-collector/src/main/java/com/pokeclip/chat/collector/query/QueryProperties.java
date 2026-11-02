package com.pokeclip.chat.collector.query;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 범위 창구 둘(목록·차트)의 상한. 전부 <b>운영자가 만질 값이 아니라 계약값</b>이라
 * 환경변수를 안 뺐다 — 늘리면 clip·프론트가 한 번에 받아야 하는 양이 같이 늘어난다.
 *
 * @param windowMax       한 번에 물을 수 있는 시각 범위. 넘으면 400 {@code too_wide}
 * @param pageDefault     {@code limit}을 안 주면 쓰는 값
 * @param pageMax         {@code limit}의 상한. 넘으면 <b>400이 아니라 잘라 준다</b> —
 *                        많이 달라는 것은 오류가 아니고, 400으로 만들면 부르는 쪽이
 *                        상한을 알아야만 창구를 쓸 수 있다
 * @param chartMaxBuckets 차트 점 수 상한. 넘으면 400 {@code too_many_buckets}
 */
@ConfigurationProperties(prefix = "pokeclip.query")
public record QueryProperties(Duration windowMax, int pageDefault, int pageMax, int chartMaxBuckets) {

    public QueryProperties {
        // 값이 비면 창구가 「상한 없음」으로 조용히 열린다 — 설정 오타는 부팅에서 죽는다.
        if (windowMax == null || windowMax.isZero() || windowMax.isNegative()) {
            throw new IllegalArgumentException("pokeclip.query.window-max는 양수 기간이어야 한다");
        }
        requirePositive(pageDefault, "page-default");
        requirePositive(pageMax, "page-max");
        requirePositive(chartMaxBuckets, "chart-max-buckets");
        if (pageDefault > pageMax) {
            throw new IllegalArgumentException(
                    "pokeclip.query.page-default(" + pageDefault + ")가 page-max(" + pageMax + ")보다 크다");
        }
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException("pokeclip.query." + name + "=" + value + "은 양수여야 한다");
        }
    }
}
