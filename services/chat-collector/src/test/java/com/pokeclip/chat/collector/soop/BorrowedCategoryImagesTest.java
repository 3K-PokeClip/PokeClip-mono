package com.pokeclip.chat.collector.soop;

import com.pokeclip.chat.collector.liveinfo.ChzzkLiveInfoClient.CategoryHit;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** SOOP 카테고리 사진을 치지직 카테고리 검색에서 빌린다(POK-462 사용자 결정). 검색은 가짜로, 시계는 손으로 돌린다. */
class BorrowedCategoryImagesTest {

    private static final Instant T = Instant.parse("2026-10-25T00:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(T);
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final List<String> asked = new CopyOnWriteArrayList<>();
    private volatile boolean down;
    private final Function<String, List<CategoryHit>> search = query -> {
        asked.add(query);
        if (down) {
            throw new IllegalStateException("chzzk down");
        }
        return List.of(new CategoryHit("League of Legends 2", "https://img.example/lol2.jpg"),
                new CategoryHit("League of Legends", "https://img.example/lol.jpg"),
                new CategoryHit("배틀그라운드", null));
    };

    private BorrowedCategoryImages images() {
        return new BorrowedCategoryImages(search, java.util.Map.of(), Runnable::run, clock);
    }

    /**
     * 두 플랫폼 이름이 다른 카테고리는 별칭 표의 짝으로 치지직 이름을 찾는다(관리 세션 지시: 두 공식 목록에서 확인한 짝만). 표 열쇠도 대소문자 ·
     * 공백을 무시한다. 짝이 있으면 치지직 이름으로 묻고 그 이름과 정확히 같은 것만 쓴다.
     */
    @Test
    void 별칭_짝이_있으면_치지직_이름으로_묻는다() {
        BorrowedCategoryImages images = new BorrowedCategoryImages(search, java.util.Map.of("LOL", "League of Legends"), Runnable::run, clock);

        assertThat(images.imageOf("lol")).isEqualTo("https://img.example/lol.jpg");
        assertThat(asked).containsExactly("League of Legends");
        assertThat(images.imageOf("배틀그라운드")).as("표에 없는 이름은 그대로 묻는다").isNull();
        assertThat(asked).containsExactly("League of Legends", "배틀그라운드");
    }

    /** 이름이 정확히 같은 것만 쓴다(검색은 「포함」이라 비슷한 이름이 앞에 올 수 있다). 대소문자 · 공백은 무시한다. */
    @Test
    void 이름이_같은_카테고리의_사진만_쓰고_대소문자와_공백은_무시한다() {
        assertThat(images().imageOf("league of  legends")).isEqualTo("https://img.example/lol.jpg");
        assertThat(asked).as("검색어는 SOOP 이름 그대로(앞뒤 공백만 깎는다)").containsExactly("league of  legends");
    }

    @Test
    void 같은_이름이_없거나_사진이_없으면_null이다() {
        BorrowedCategoryImages images = images();
        assertThat(images.imageOf("LOL")).isNull();
        assertThat(images.imageOf("배틀그라운드")).isNull();
    }

    /** 찾은 것도 못 찾은 것도 기억해 같은 이름을 매번 묻지 않는다. 기억 기간이 지나면 다시 묻는다. */
    @Test
    void 찾은_것도_못_찾은_것도_기억하고_기간이_지나면_다시_묻는다() {
        BorrowedCategoryImages images = images();
        images.imageOf("League of Legends");
        images.imageOf("LOL");
        now.set(T.plus(BorrowedCategoryImages.REMEMBER).minusSeconds(1));
        assertThat(images.imageOf("league of legends")).as("정규화한 이름으로 기억한다").isEqualTo("https://img.example/lol.jpg");
        assertThat(images.imageOf("LOL")).isNull();
        assertThat(asked).hasSize(2);

        now.set(T.plus(BorrowedCategoryImages.REMEMBER));
        images.imageOf("LOL");
        assertThat(asked).hasSize(3);
    }

    /** 검색이 실패하면 null이고 기억하지 않는다. 쉬는 동안(모든 이름)은 다시 안 묻는다 — 1분 틱마다 치지직을 두드리지 않게. */
    @Test
    void 검색이_실패하면_null이고_쉬는_동안은_아무_이름도_안_묻는다() {
        BorrowedCategoryImages images = images();
        down = true;
        assertThat(images.imageOf("League of Legends")).isNull();
        assertThat(images.imageOf("배틀그라운드")).isNull();
        assertThat(asked).hasSize(1);

        down = false;
        now.set(T.plus(BorrowedCategoryImages.BACKOFF));
        assertThat(images.imageOf("League of Legends")).as("실패는 기억하지 않았다").isEqualTo("https://img.example/lol.jpg");
    }

    /**
     * 검색은 방송 여부 확인을 막지 않는다(POK-462 로컬 리뷰 1라운드). 모르는 이름은 null로 바로 답하고 검색은 따로 돈다. 같은 이름을 검색하는
     * 중에 또 물어도 한 번만 맡긴다. 다른 이름은 따로 맡긴다.
     */
    @Test
    void 검색은_따로_돌고_그동안은_null로_답한다() {
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        BorrowedCategoryImages images = new BorrowedCategoryImages(search, java.util.Map.of(), queued::add, clock);

        assertThat(images.imageOf("League of Legends")).as("기다리지 않는다").isNull();
        assertThat(images.imageOf("league of legends")).isNull();
        assertThat(images.imageOf("배틀그라운드")).isNull();
        assertThat(queued).as("같은 이름은 한 번, 다른 이름은 따로").hasSize(2);
        assertThat(asked).isEmpty();

        queued.forEach(Runnable::run);

        assertThat(images.imageOf("League of Legends")).isEqualTo("https://img.example/lol.jpg");
        assertThat(asked).hasSize(2);
    }

    /** 검색이 Error로 죽어도 그 이름의 「검색 중」 표시가 남지 않는다(로컬 리뷰 2라운드). 남으면 그 이름은 다시 안 묻는다. */
    @Test
    void 검색이_Error로_죽어도_그_이름을_다시_묻는다() {
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        BorrowedCategoryImages images = new BorrowedCategoryImages(query -> {
            throw new StackOverflowError();
        }, java.util.Map.of(), queued::add, clock);

        images.imageOf("LOL");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> queued.removeFirst().run()).isInstanceOf(StackOverflowError.class);

        now.set(T.plus(BorrowedCategoryImages.BACKOFF));
        images.imageOf("LOL");
        assertThat(queued).as("다시 맡겼다").hasSize(1);
    }

    @Test
    void 이름이_없으면_안_묻는다() {
        BorrowedCategoryImages images = images();
        assertThat(images.imageOf(null)).isNull();
        assertThat(images.imageOf("  ")).isNull();
        assertThat(asked).isEmpty();
    }

    /** 기억은 상한이 있다. 넘치면 통째로 비운다(카테고리는 수백 개라 거의 안 닿는다). */
    @Test
    void 기억이_상한을_넘으면_비운다() {
        BorrowedCategoryImages images = images();
        for (int i = 0; i < BorrowedCategoryImages.MAX_REMEMBERED; i++) {
            images.imageOf("c" + i);
        }
        images.imageOf("c0");
        assertThat(asked).as("상한까지는 기억한다").hasSize(BorrowedCategoryImages.MAX_REMEMBERED);

        images.imageOf("넘침");
        images.imageOf("c0");
        assertThat(asked).as("넘친 뒤 비워서 다시 묻는다").hasSize(BorrowedCategoryImages.MAX_REMEMBERED + 2);
    }
}
