package com.pokeclip.chat.collector.soop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * SOOP 카테고리 번호 → 이름 표(POK-462). 「방송 여부」 응답이 카테고리를 번호({@code broad_cate_no})로만 주므로 카테고리 리스트로 바꾼다.
 *
 * <p>표는 메모리에 두고 {@link #REFRESH}마다 새로 받는다. 카테고리는 SOOP이 가끔 더하는 정도라 자주 받을 이유가 없다. 받기가 실패하면 앞 표를 지키고
 * {@link #RETRY} 뒤에 다시 묻는다(방송 여부 틱이 10초라 실패마다 다시 물으면 SOOP을 두드린다). 표가 없거나 표에 없는 번호면 null이다:
 * 이름을 지어내지 않는다.
 *
 * <p>🔴 <b>받기는 따로 돈다</b>(로컬 리뷰 1라운드). 이 표는 SOOP 방송 여부 확인 안에서 불리는데, 거기서 받기(시한 접속 1초 · 읽기 2초)를 기다리면
 * 그 방송의 「방송 중」 처리와 채팅 붙이기가 그만큼 늦어 방송 시작 직후 채팅을 잃는다(POK-456이 줄인 지연). 그래서 묻는 쪽에는 지금 표로 바로 답하고
 * (처음엔 표가 없어 null, 다음 확인부터 이름), 받기는 맡긴 실행기에서 돈다. 받는 중에는 또 맡기지 않는다.
 */
public class SoopCategoryNames {

    private static final Logger log = LoggerFactory.getLogger(SoopCategoryNames.class);

    static final Duration REFRESH = Duration.ofHours(6);
    static final Duration RETRY = Duration.ofMinutes(10);
    /** 카테고리 리스트의 번호 모양. 「방송 여부」는 숫자({@code 40075})로 주고 리스트는 8자리 글자({@code "00040075"})로 준다(문서 예시). */
    private static final int WIDTH = 8;

    private final Supplier<Map<String, String>> fetch;
    private final Executor executor;
    private final Clock clock;
    private Map<String, String> names = Map.of();
    private Instant nextFetch = Instant.EPOCH;
    private boolean fetching;

    /**
     * @param fetch    번호 → 이름. 실패면 던진다({@link SoopCategoryClient#fetch})
     * @param executor 받기를 돌릴 곳. 묻는 스레드를 막지 않는 것이어야 한다(시험만 {@code Runnable::run})
     */
    public SoopCategoryNames(Supplier<Map<String, String>> fetch, Executor executor, Clock clock) {
        this.fetch = fetch;
        this.executor = executor;
        this.clock = clock;
    }

    /** @return 지금 표의 이름. 번호를 못 읽거나 표가 없거나 표에 없으면 null. 기다리지도 던지지도 않는다 */
    public String nameOf(Object rawNumber) {
        String number = normalize(rawNumber);
        if (number == null) {
            return null;
        }
        prefetch();
        synchronized (this) {
            return names.get(number);
        }
    }

    /**
     * 받을 때가 됐고 받는 중이 아니면 받기를 맡긴다. 부팅 때 한 번 부른다(로컬 리뷰 2라운드): 안 그러면 첫 물음이 받기를 맡기고 null로 답해
     * 부팅 뒤 첫 SOOP 관측은 늘 카테고리가 비고 구간이 쪼개진다.
     */
    public void prefetch() {
        boolean start;
        synchronized (this) {
            start = !fetching && !clock.instant().isBefore(nextFetch);
            fetching |= start;
        }
        if (start) {
            submit();
        }
    }

    private void submit() {
        try {
            executor.execute(this::refresh);
        } catch (RuntimeException e) {
            // 실행기가 닫혔다(종료 중). 다음에 물을 때 다시 맡긴다
            synchronized (this) {
                fetching = false;
            }
        }
    }

    /**
     * 받기. 묻는 쪽과 다른 스레드에서 돈다. 받는 동안 잠금을 쥐지 않는다. 🔴 {@code finally}에서 「받는 중」을 내린다(로컬 리뷰 2라운드):
     * Error로 죽어도 표시가 남으면 그 프로세스가 끝날 때까지 다시 안 받는다. Error는 삼키지 않는다.
     */
    private void refresh() {
        Map<String, String> fetched = null;
        String failure = "Error";
        try {
            fetched = Map.copyOf(fetch.get());
        } catch (RuntimeException e) {
            failure = e.getClass().getSimpleName();
        } finally {
            synchronized (this) {
                Instant now = clock.instant();
                if (fetched != null) {
                    names = fetched;
                    nextFetch = now.plus(REFRESH);
                } else {
                    nextFetch = now.plus(RETRY);
                    log.warn("soop.category.fetch_failed causeType={} kept={}", failure, names.size());
                }
                fetching = false;
            }
        }
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
