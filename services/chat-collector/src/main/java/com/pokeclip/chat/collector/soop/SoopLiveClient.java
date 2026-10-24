package com.pokeclip.chat.collector.soop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * SOOP 방송 여부 체크({@code POST /validate/live/status}, POK-397). 스트리머 토큰 하나로 그 방송국이 지금 방송 중인지와 방송 정보를 받는다.
 *
 * <p>토큰은 <b>form 본문</b>({@code access_token})에만 싣는다. 주소(쿼리)는 프록시·접근 로그에 남는다(auth {@code SoopOAuthClient}와 같다).
 *
 * <p><b>거부와 일시를 auth와 같은 규칙으로 가른다.</b> 거부 = HTTP 200 + {@code result} 음수 · 400·401·403. 나머지 상태·못 닿음·못 읽는 본문은
 * 일시다. SOOP은 거부를 401이 아니라 200 + 음수로 주는 일이 있다.
 *
 * <p><b>응답 칸은 실방송으로 확정했다</b>(2026-10-24 진단 로그, 키 이름만). 방송 중 data의 키:
 * {@code broad_cate_no, broad_no, broad_start, broad_thumb, broad_title, current_view_cnt, language, link, paid_promotion, status, user_id},
 * status {@value #ON_AIR}(문서와 같다). 방송 아님은 {@code broad_no}·{@code status}뿐이고 status {@value #STREAM_END}.
 * 제목 {@value #TITLE_KEY}, 시청자 수 {@value #VIEWERS_KEY}(숫자 또는 숫자 글자). 카테고리는 {@value #CATEGORY_KEY}로 <b>번호만</b> 오고, 받은
 * 이름 표({@link SoopCategoryNames}, POK-462)로 이름으로 바꾼다. 표가 없거나 표에 없으면 null. 🔴 첫 판은 잠정 값 {@code onair}·{@code current_sum_viewer}를
 * 써서 방송 중을 하나도 못 알아봤다.
 */
public class SoopLiveClient {

    private static final Logger log = LoggerFactory.getLogger(SoopLiveClient.class);

    static final String PATH = "/validate/live/status";
    static final String ON_AIR = "streaming";
    static final String STREAM_END = "streamend";
    /** 모르는 status 값을 로그에 싣는 길이 상한(감사 2회차 n3). */
    static final int UNKNOWN_STATUS_MAX = 32;
    /** 경고를 남긴 모르는 status 값. 값마다 첫 번만 남긴다. 끝없이 늘지 않게 이만큼 차면 더 안 넣는다(그 뒤 새 값은 경고 없이 「방송 아님」). */
    private static final int UNKNOWN_STATUS_MEMO_MAX = 64;
    private final java.util.Set<String> warnedStatuses = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static final String TITLE_KEY = "broad_title";
    static final String VIEWERS_KEY = "current_view_cnt";
    static final String CATEGORY_KEY = "broad_cate_no";

    private final RestClient restClient;
    private final String apiBaseUri;
    private final boolean diagnose;
    private final Function<Object, String> categoryName;

    /** 카테고리 이름 표 없이(카테고리는 늘 null). */
    public SoopLiveClient(RestClient.Builder builder, String apiBaseUri, boolean diagnose) {
        this(builder, apiBaseUri, diagnose, number -> null);
    }

    /**
     * @param builder      시한이 걸린 빌더({@link SoopWatchConfiguration}). {@code RestClient.create()}를 쓰지 않는다
     * @param diagnose     켜면 data의 키 이름과 status 값만 INFO 한 줄로 남긴다. 값(아이디·제목·시청자 수)은 안 찍는다
     * @param categoryName {@value #CATEGORY_KEY} 값 그대로(숫자 또는 글자) → 이름, 모르면 null({@link SoopCategoryNames#nameOf})
     */
    public SoopLiveClient(RestClient.Builder builder, String apiBaseUri, boolean diagnose, Function<Object, String> categoryName) {
        this.restClient = builder.build();
        this.apiBaseUri = apiBaseUri;
        this.diagnose = diagnose;
        this.categoryName = categoryName;
    }

    /** 던지지 않는다. 예외는 원인 타입 이름만 남긴다(메시지에 응답 본문이 실린다). */
    public SoopLiveStatus check(String accessToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("access_token", accessToken);
        Map<?, ?> body;
        try {
            body = restClient.post()
                    .uri(apiBaseUri + PATH)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 400 || status == 401 || status == 403) {
                return SoopLiveStatus.of(SoopLiveStatus.Outcome.REJECTED);
            }
            log.warn("soop.live.unavailable status={}", status);
            return SoopLiveStatus.of(SoopLiveStatus.Outcome.UNAVAILABLE);
        } catch (RuntimeException e) {
            log.warn("soop.live.unavailable causeType={}", e.getClass().getSimpleName());
            return SoopLiveStatus.of(SoopLiveStatus.Outcome.UNAVAILABLE);
        }
        return read(body);
    }

    private SoopLiveStatus read(Map<?, ?> body) {
        if (body == null || !(body.get("result") instanceof Number result)) {
            log.warn("soop.live.malformed field=result");
            return SoopLiveStatus.of(SoopLiveStatus.Outcome.UNAVAILABLE);
        }
        if (result.intValue() < 0) {
            // msg는 옮기지 않는다(토큰이 되비칠 수 있다)
            return SoopLiveStatus.of(SoopLiveStatus.Outcome.REJECTED);
        }
        if (!(body.get("data") instanceof Map<?, ?> data)) {
            log.warn("soop.live.malformed field=data");
            return SoopLiveStatus.of(SoopLiveStatus.Outcome.UNAVAILABLE);
        }
        Object status = data.get("status");
        if (diagnose) {
            // 키 이름은 정렬해 한 줄로(실행마다 같은 글자가 되게), 값은 status 하나만. status는 방송 상태 낱말이라 개인 정보가 아니다.
            log.info("soop.live.diagnose keys={} status={}", new TreeSet<>(data.keySet().stream().map(String::valueOf).toList()),
                    status instanceof String s ? s : "type=" + (status == null ? "null" : status.getClass().getSimpleName()));
        }
        if (!ON_AIR.equals(status)) {
            if (!STREAM_END.equals(status)) {
                warnUnknownStatus(status);
            }
            return SoopLiveStatus.of(SoopLiveStatus.Outcome.OFF_AIR);
        }
        Object title = data.get(TITLE_KEY);
        return new SoopLiveStatus(SoopLiveStatus.Outcome.ON_AIR,
                title instanceof String s && !s.isBlank() ? s : null, category(data.get(CATEGORY_KEY)), viewers(data.get(VIEWERS_KEY)));
    }

    /** 이름 표가 던져도 방송 여부는 그대로 낸다(카테고리만 null). 표는 던지지 않게 짰지만 이 자리가 방송 감지를 막으면 안 된다. */
    private String category(Object rawNumber) {
        try {
            return categoryName.apply(rawNumber);
        } catch (RuntimeException e) {
            log.warn("soop.live.category_failed causeType={}", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 아는 값(streaming·streamend) 밖의 status는 「방송 아님」으로 두되 값마다 첫 번 WARN 한 줄을 남긴다(n3). 값 낱말이 다르면 SOOP 줄이 하나도 안 생기는데
     * 진단 스위치를 안 켜면 아무 데도 안 남는다. status는 방송 상태 낱말이라 값을 싣되 {@value #UNKNOWN_STATUS_MAX}자로 자른다. 글자가 아니면 타입만.
     */
    private void warnUnknownStatus(Object status) {
        String key = status instanceof String s ? s : "type=" + (status == null ? "null" : status.getClass().getSimpleName());
        if (warnedStatuses.size() >= UNKNOWN_STATUS_MEMO_MAX || !warnedStatuses.add(key)) {
            return;
        }
        if (status instanceof String s) {
            log.warn("soop.live.unknown_status value={} length={}", s.substring(0, Math.min(s.length(), UNKNOWN_STATUS_MAX)),
                    s.length());
        } else {
            log.warn("soop.live.unknown_status {}", key);
        }
    }

    /** 숫자 또는 숫자 글자. 그 밖은 null(0으로 접으면 「아무도 안 봤다」가 된다). */
    private static Integer viewers(Object raw) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
