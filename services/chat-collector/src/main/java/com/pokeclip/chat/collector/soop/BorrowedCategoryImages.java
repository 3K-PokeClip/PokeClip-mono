package com.pokeclip.chat.collector.soop;

import com.pokeclip.chat.collector.liveinfo.ChzzkLiveInfoClient.CategoryHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * SOOP 카테고리 사진을 치지직에서 빌린다(POK-462 사용자 결정). SOOP 공식 API는 카테고리 사진을 주지 않는다.
 *
 * <p>치지직 공식 카테고리 검색({@code GET /open/v1/categories/search}, 앱 인증)에 SOOP 카테고리 이름을 넣고, <b>이름이 정확히 같은</b> 카테고리의
 * {@code posterImageUrl}을 쓴다(대소문자 · 공백만 무시). 검색은 「이름을 포함하는」 목록이라 비슷한 이름이 앞에 올 수 있고, 그것을 쓰면 다른 게임
 * 사진이 뜬다. 같은 이름이 없으면 null이다(웹은 프로필 사진을 보인다).
 *
 * <p>두 플랫폼 이름이 다른 카테고리는 {@link #ALIASES}의 짝으로 치지직 이름을 찾는다. 🔴 <b>두 공식 목록(SOOP 카테고리 리스트 · 치지직 카테고리
 * 검색)에서 직접 확인한 짝만 넣는다</b>(짝마다 확인 근거 주석). 추측 짝은 넣지 않는다 — 틀리면 엉뚱한 게임 사진이 뜬다. 표에 없는 이름은 그 이름 그대로 묻는다.
 *
 * <p>찾은 것도 못 찾은 것도 이름마다 {@link #REMEMBER} 기억한다(방송 여부 틱이 1분이라 안 기억하면 방송마다 1분에 한 번 묻는다). 검색이 실패하면
 * 기억하지 않고 {@link #BACKOFF} 동안 어떤 이름도 안 묻는다.
 *
 * <p>🔴 <b>검색은 따로 돈다</b>(로컬 리뷰 1라운드). 이것은 SOOP 방송 여부 확인 안에서 불리는데, 거기서 치지직 검색(시한 접속 1초 · 읽기 2초)을
 * 기다리면 새 카테고리를 처음 볼 때마다 그 방송의 채팅 붙이기가 늦는다. 그래서 모르는 이름은 null로 바로 답하고 검색은 맡긴 실행기에서 돈다
 * (다음 확인부터 사진). 같은 이름을 검색하는 중에는 또 맡기지 않는다.
 */
public class BorrowedCategoryImages {

    private static final Logger log = LoggerFactory.getLogger(BorrowedCategoryImages.class);

    static final Duration REMEMBER = Duration.ofHours(6);
    static final Duration BACKOFF = Duration.ofMinutes(10);
    /** 기억 상한. SOOP 카테고리는 수백 개라 거의 안 닿는다. 넘치면 통째로 비운다(오래된 것부터 고르는 장치보다 단순하다). */
    static final int MAX_REMEMBERED = 1_000;

    /**
     * SOOP 카테고리 이름 → 치지직 카테고리 이름. 열쇠는 대소문자 · 공백을 무시한다. 짝마다 두 공식 목록에서 확인한 날짜와 근거를 단다.
     */
    static final Map<String, String> ALIASES = Map.of();

    private record Remembered(String imageUrl, Instant until) {
    }

    private final Function<String, List<CategoryHit>> search;
    private final Map<String, String> aliases;
    private final Executor executor;
    private final Clock clock;
    private final Map<String, Remembered> remembered = new HashMap<>();
    /** 검색을 맡겨 둔 이름(열쇠). 같은 이름을 두 번 맡기지 않는다. */
    private final Set<String> searching = new HashSet<>();
    private Instant pausedUntil = Instant.EPOCH;

    /** 운영 별칭 표({@link #ALIASES})로. */
    public BorrowedCategoryImages(Function<String, List<CategoryHit>> search, Executor executor, Clock clock) {
        this(search, ALIASES, executor, clock);
    }

    /**
     * @param search   검색어 → 결과. 실패면 던진다({@code ChzzkLiveInfoClient#searchCategories})
     * @param aliases  SOOP 이름 → 치지직 이름(열쇠는 대소문자 · 공백 무시)
     * @param executor 검색을 돌릴 곳. 묻는 스레드를 막지 않는 것이어야 한다(시험만 {@code Runnable::run})
     */
    BorrowedCategoryImages(Function<String, List<CategoryHit>> search, Map<String, String> aliases, Executor executor, Clock clock) {
        this.search = search;
        this.aliases = new HashMap<>();
        aliases.forEach((soop, chzzk) -> this.aliases.put(key(soop), chzzk));
        this.executor = executor;
        this.clock = clock;
    }

    /**
     * @return 기억한 사진 주소. 이름이 없거나 같은 이름이 없거나 아직 검색 전 · 중이거나 검색이 실패하면 null. 기다리지도 던지지도 않는다
     */
    public String imageOf(String categoryName) {
        if (categoryName == null || categoryName.isBlank()) {
            return null;
        }
        String key = key(categoryName);
        synchronized (this) {
            Instant now = clock.instant();
            Remembered hit = remembered.get(key);
            if (hit != null && now.isBefore(hit.until())) {
                return hit.imageUrl();
            }
            if (now.isBefore(pausedUntil) || !searching.add(key)) {
                return null;
            }
        }
        String chzzkName = aliases.getOrDefault(key, categoryName.strip());
        try {
            executor.execute(() -> search(key, chzzkName));
        } catch (RuntimeException e) {
            // 실행기가 닫혔다(종료 중). 다음에 물을 때 다시 맡긴다
            synchronized (this) {
                searching.remove(key);
            }
        }
        synchronized (this) {
            Remembered hit = remembered.get(key);
            return hit == null ? null : hit.imageUrl();
        }
    }

    /**
     * 검색. 묻는 쪽과 다른 스레드에서 돈다. 검색하는 동안 잠금을 쥐지 않는다. 🔴 {@code finally}에서 「검색 중」을 내린다(로컬 리뷰 2라운드):
     * Error로 죽어도 표시가 남으면 그 이름은 다시 안 묻는다. Error는 삼키지 않는다.
     */
    private void search(String key, String chzzkName) {
        String wanted = key(chzzkName);
        String imageUrl = null;
        boolean searched = false;
        String failure = "Error";
        try {
            imageUrl = search.apply(chzzkName).stream()
                    .filter(candidate -> candidate.categoryValue() != null && key(candidate.categoryValue()).equals(wanted))
                    .map(CategoryHit::posterImageUrl)
                    .filter(url -> url != null && !url.isBlank())
                    .findFirst().orElse(null);
            searched = true;
        } catch (RuntimeException e) {
            failure = e.getClass().getSimpleName();
        } finally {
            synchronized (this) {
                searching.remove(key);
                Instant now = clock.instant();
                if (!searched) {
                    pausedUntil = now.plus(BACKOFF);
                    log.warn("soop.category_image.search_failed causeType={}", failure);
                } else {
                    if (remembered.size() >= MAX_REMEMBERED && !remembered.containsKey(key)) {
                        remembered.clear();
                    }
                    remembered.put(key, new Remembered(imageUrl, now.plus(REMEMBER)));
                }
            }
        }
    }

    /** 대소문자와 모든 공백을 무시한 비교 열쇠. */
    private static String key(String name) {
        return name.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
