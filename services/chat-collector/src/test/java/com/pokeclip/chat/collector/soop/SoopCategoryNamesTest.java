package com.pokeclip.chat.collector.soop;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** SOOP 카테고리 번호 → 이름 표(POK-462). 받기는 가짜로, 시계는 손으로 돌린다. */
class SoopCategoryNamesTest {

    private static final Instant T = Instant.parse("2026-10-25T00:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(T);
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final AtomicInteger fetches = new AtomicInteger();
    /** 받기마다 하나씩 꺼낸다. null이면 던진다(실패). 마지막 것은 계속 쓴다. */
    private final Deque<Map<String, String>> answers = new java.util.LinkedList<>();
    private final Supplier<Map<String, String>> fetch = () -> {
        fetches.incrementAndGet();
        Map<String, String> answer = answers.size() > 1 ? answers.pollFirst() : answers.peekFirst();
        if (answer == null) {
            throw new IllegalStateException("soop down");
        }
        return answer;
    };

    @Test
    void 번호는_숫자든_글자든_8자리로_맞추고_그_밖은_null이다() {
        assertThat(SoopCategoryNames.normalize(40075)).isEqualTo("00040075");
        assertThat(SoopCategoryNames.normalize(40075L)).isEqualTo("00040075");
        assertThat(SoopCategoryNames.normalize("00040019")).isEqualTo("00040019");
        assertThat(SoopCategoryNames.normalize(" 40019 ")).isEqualTo("00040019");
        assertThat(SoopCategoryNames.normalize(null)).isNull();
        assertThat(SoopCategoryNames.normalize("")).isNull();
        assertThat(SoopCategoryNames.normalize("abc")).isNull();
        assertThat(SoopCategoryNames.normalize("123456789")).as("9자리는 표에 없는 모양").isNull();
        assertThat(SoopCategoryNames.normalize(-1)).isNull();
        assertThat(SoopCategoryNames.normalize(1.5)).isNull();
    }

    @Test
    void 처음_물을_때_한_번_받고_갱신_주기_안에서는_다시_안_받는다() {
        answers.add(Map.of("00040019", "LOL"));
        SoopCategoryNames names = new SoopCategoryNames(fetch, Runnable::run, clock);

        assertThat(names.nameOf(40019)).isEqualTo("LOL");
        now.set(T.plus(SoopCategoryNames.REFRESH).minusSeconds(1));
        assertThat(names.nameOf("00040019")).isEqualTo("LOL");
        assertThat(names.nameOf(99999)).as("표에 없는 번호").isNull();
        assertThat(fetches).hasValue(1);
    }

    @Test
    void 갱신_주기가_지나면_새로_받는다() {
        answers.add(Map.of("00040019", "LOL"));
        answers.add(Map.of("00040019", "리그 오브 레전드"));
        SoopCategoryNames names = new SoopCategoryNames(fetch, Runnable::run, clock);
        names.nameOf(40019);

        now.set(T.plus(SoopCategoryNames.REFRESH));

        assertThat(names.nameOf(40019)).isEqualTo("리그 오브 레전드");
        assertThat(fetches).hasValue(2);
    }

    /** 받기가 실패하면 앞 표를 지키고(없으면 null) 다시 묻기 간격 전에는 안 묻는다. 방송 여부 틱마다 SOOP을 두드리지 않게. */
    @Test
    void 받기가_실패하면_앞_표를_지키고_재시도_간격_뒤에_다시_묻는다() {
        SoopCategoryNames names = new SoopCategoryNames(fetch, Runnable::run, clock);
        answers.add(null);

        assertThat(names.nameOf(40019)).as("표가 없다").isNull();
        assertThat(names.nameOf(40019)).isNull();
        assertThat(fetches).as("재시도 간격 안에서는 한 번").hasValue(1);

        answers.clear();
        answers.add(Map.of("00040019", "LOL"));
        now.set(T.plus(SoopCategoryNames.RETRY));
        assertThat(names.nameOf(40019)).isEqualTo("LOL");

        answers.clear();
        answers.add(null);
        now.set(now.get().plus(SoopCategoryNames.REFRESH));
        assertThat(names.nameOf(40019)).as("갱신이 실패해도 앞 표").isEqualTo("LOL");
        assertThat(fetches).hasValue(3);
    }

    /**
     * 받기는 방송 여부 확인을 막지 않는다(POK-462 로컬 리뷰 1라운드). 받는 동안은 앞 표(처음엔 없음)로 답하고, 받기는 따로 돈다. 확인 안에서
     * 기다리면 SOOP이 느릴 때 그 방송의 채팅 붙이기가 그만큼 늦는다. 받는 중에 또 물어도 한 번만 받는다.
     */
    @Test
    void 받기는_따로_돌고_그동안은_앞_표로_답한다() {
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        answers.add(Map.of("00040019", "LOL"));
        SoopCategoryNames names = new SoopCategoryNames(fetch, queued::add, clock);

        assertThat(names.nameOf(40019)).as("아직 표가 없다 — 기다리지 않는다").isNull();
        assertThat(names.nameOf(40019)).isNull();
        assertThat(queued).as("받는 중에는 또 안 맡긴다").hasSize(1);
        assertThat(fetches).hasValue(0);

        queued.removeFirst().run();

        assertThat(names.nameOf(40019)).isEqualTo("LOL");
        assertThat(queued).isEmpty();
    }

    /** 부팅 때 미리 받기를 맡긴다(로컬 리뷰 2라운드). 안 그러면 부팅 뒤 첫 SOOP 관측은 늘 카테고리가 비어 구간이 쪼개진다. */
    @Test
    void 미리_받기를_맡기면_첫_물음부터_이름이다() {
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        answers.add(Map.of("00040019", "LOL"));
        SoopCategoryNames names = new SoopCategoryNames(fetch, queued::add, clock);

        names.prefetch();
        names.prefetch();
        assertThat(queued).as("받는 중에는 또 안 맡긴다").hasSize(1);
        queued.removeFirst().run();

        assertThat(names.nameOf(40019)).isEqualTo("LOL");
        assertThat(queued).isEmpty();
    }

    /** 받기가 Error로 죽어도 「받는 중」 표시가 남지 않는다(로컬 리뷰 2라운드). 남으면 그 프로세스가 끝날 때까지 다시 안 받는다. */
    @Test
    void 받기가_Error로_죽어도_재시도_간격_뒤에_다시_맡긴다() {
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        SoopCategoryNames names = new SoopCategoryNames(() -> {
            fetches.incrementAndGet();
            throw new StackOverflowError();
        }, queued::add, clock);

        names.nameOf(40019);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> queued.removeFirst().run()).isInstanceOf(StackOverflowError.class);

        now.set(T.plus(SoopCategoryNames.RETRY));
        names.nameOf(40019);
        assertThat(queued).as("다시 맡겼다").hasSize(1);
    }

    @Test
    void 번호가_못_읽히면_받지도_않는다() {
        answers.add(Map.of("00040019", "LOL"));
        SoopCategoryNames names = new SoopCategoryNames(fetch, Runnable::run, clock);

        assertThat(names.nameOf(null)).isNull();
        assertThat(names.nameOf("x")).isNull();
        assertThat(fetches).hasValue(0);
    }
}
