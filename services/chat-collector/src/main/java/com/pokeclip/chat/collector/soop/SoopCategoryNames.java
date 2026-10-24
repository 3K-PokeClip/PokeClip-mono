package com.pokeclip.chat.collector.soop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

/**
 * SOOP 카테고리 번호 → 이름 표(POK-462). 「방송 여부」 응답이 카테고리를 번호({@code broad_cate_no})로만 주므로 카테고리 리스트로 바꾼다.
 *
 * <p>표는 메모리에 두고 {@link #REFRESH}마다 새로 받는다. 카테고리는 SOOP이 가끔 더하는 정도라 자주 받을 이유가 없다. 받기가 실패하면 앞 표를 지키고
 * {@link #RETRY} 뒤에 다시 묻는다(방송 여부 틱이 10초라 실패마다 다시 물으면 SOOP을 두드린다). 표가 없거나 표에 없는 번호면 null이다:
 * 이름을 지어내지 않는다.
 *
 * <p>지켜보기 틱(스레드 하나)에서 불린다. 받기가 틱 안에서 돌아 그 회차가 시한(접속 1초 · 읽기 2초)만큼 늦어질 수 있다. 6시간에 한 번이다.
 */
public class SoopCategoryNames {

    private static final Logger log = LoggerFactory.getLogger(SoopCategoryNames.class);

    static final Duration REFRESH = Duration.ofHours(6);
    static final Duration RETRY = Duration.ofMinutes(10);
    /** 카테고리 리스트의 번호 모양. 「방송 여부」는 숫자({@code 40075})로 주고 리스트는 8자리 글자({@code "00040075"})로 준다(문서 예시). */
    private static final int WIDTH = 8;

    private final Supplier<Map<String, String>> fetch;
    private final Clock clock;
    private Map<String, String> names = Map.of();
    private Instant nextFetch = Instant.EPOCH;

    /** @param fetch 번호 → 이름. 실패면 던진다({@link SoopCategoryClient#fetch}) */
    public SoopCategoryNames(Supplier<Map<String, String>> fetch, Clock clock) {
        this.fetch = fetch;
        this.clock = clock;
    }

    /** @return 이름. 번호를 못 읽거나 표가 없거나 표에 없으면 null. 던지지 않는다 */
    public synchronized String nameOf(Object rawNumber) {
        String number = normalize(rawNumber);
        if (number == null) {
            return null;
        }
        Instant now = clock.instant();
        if (!now.isBefore(nextFetch)) {
            try {
                names = Map.copyOf(fetch.get());
                nextFetch = now.plus(REFRESH);
            } catch (RuntimeException e) {
                nextFetch = now.plus(RETRY);
                log.warn("soop.category.fetch_failed causeType={} kept={}", e.getClass().getSimpleName(), names.size());
            }
        }
        return names.get(number);
    }

    /** 숫자 또는 숫자 글자를 8자리 글자로. 음수 · 소수 · 8자리를 넘거나 숫자가 아니면 null. */
    static String normalize(Object raw) {
        String digits;
        if (raw instanceof Integer || raw instanceof Long) {
            digits = String.valueOf(((Number) raw).longValue());
        } else if (raw instanceof String s) {
            digits = s.strip();
        } else {
            return null;
        }
        if (digits.isEmpty() || digits.length() > WIDTH || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return null;
        }
        return "0".repeat(WIDTH - digits.length()) + digits;
    }
}
