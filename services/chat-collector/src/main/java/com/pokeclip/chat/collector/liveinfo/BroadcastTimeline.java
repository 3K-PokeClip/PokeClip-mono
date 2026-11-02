package com.pokeclip.chat.collector.liveinfo;

import com.pokeclip.chat.collector.Platform;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 방송의 제목·카테고리·태그가 언제 무엇이었나(POK-462). 1분 관측 줄을 「값이 그대로였던 구간」으로 접는다.
 *
 * <p>화면은 「시각 t의 값 = 같은 플랫폼에서 {@code from ≤ t < until}인 구간」 한 줄로 찾는다. 관측 줄을 그대로 주면 12시간 방송이
 * 플랫폼당 720줄이고 부르는 쪽마다 같은 압축을 짠다.
 *
 * <p>규칙 넷:
 * <ul>
 *   <li><b>플랫폼마다 따로 접는다.</b> 두 플랫폼에 같이 내보내면 줄이 번갈아 와서, 섞어 접으면 1분마다 「바뀜」이 된다</li>
 *   <li><b>열쇠는 (제목 · 카테고리 · 태그)다.</b> 태그 순서도 스트리머가 고친 것이라 본다. 제목 · 카테고리는 앞뒤 공백을 깎고, 공백뿐이면 null</li>
 *   <li><b>사진은 열쇠가 아니다.</b> 치지직 설정 조회가 거부된 회차는 사진이 null이라 넣으면 같은 카테고리가 쪼개진다. 구간의 사진은
 *       그 구간에서 마지막으로 본 null 아닌 값이다</li>
 *   <li><b>until = 같은 플랫폼 다음 구간의 from</b>, 마지막 구간은 null. 수집이 멈췄던 사이도 구간은 이어진다(다른 값을 볼 때까지 앞 값).
 *       멈춤을 알고 싶으면 {@code lastObservedAt}을 본다</li>
 * </ul>
 */
public record BroadcastTimeline(List<Segment> segments, boolean truncated) {

    /**
     * @param from           그 값을 처음 본 관측 시각(우리 시계)
     * @param until          같은 플랫폼 다음 구간의 {@code from}. 마지막 구간이면 null(지금도 그 값)
     * @param lastObservedAt 그 값을 마지막으로 본 관측 시각
     */
    public record Segment(Platform platform, Instant from, Instant until, Instant lastObservedAt,
                          String title, String category, String categoryImageUrl, List<String> tags) {
    }

    /**
     * @param rows        한 방송의 관측 줄. <b>시각 오름차순</b>이어야 한다(같은 시각이면 들어온 순)
     * @param maxSegments 이것보다 많으면 <b>오래된 쪽</b>을 버리고 {@code truncated}다. 라이브 화면에는 최근 값이 필요하다
     */
    public static BroadcastTimeline compress(List<BroadcastInfo> rows, int maxSegments) {
        List<Segment> closed = new ArrayList<>();
        Map<Platform, Run> open = new EnumMap<>(Platform.class);
        for (BroadcastInfo row : rows) {
            String title = clean(row.title());
            String category = clean(row.category());
            List<String> tags = row.tags() == null ? List.of() : List.copyOf(row.tags());
            Run run = open.get(row.platform());
            if (run != null && run.sameValue(title, category, tags)) {
                run.lastObservedAt = row.observedAt();
                if (row.categoryImageUrl() != null) {
                    run.image = row.categoryImageUrl();
                }
                continue;
            }
            if (run != null) {
                closed.add(run.close(row.observedAt()));
            }
            open.put(row.platform(), new Run(row.platform(), row.observedAt(), title, category, tags, row.categoryImageUrl()));
        }
        open.values().forEach(run -> closed.add(run.close(null)));
        closed.sort(Comparator.comparing(Segment::from).thenComparing((Segment segment) -> segment.platform().name()));
        boolean truncated = closed.size() > maxSegments;
        List<Segment> kept = truncated ? closed.subList(closed.size() - maxSegments, closed.size()) : closed;
        return new BroadcastTimeline(List.copyOf(kept), truncated);
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /** 접는 중인 구간 하나. */
    private static final class Run {
        private final Platform platform;
        private final Instant from;
        private final String title;
        private final String category;
        private final List<String> tags;
        private Instant lastObservedAt;
        private String image;

        Run(Platform platform, Instant from, String title, String category, List<String> tags, String image) {
            this.platform = platform;
            this.from = from;
            this.title = title;
            this.category = category;
            this.tags = tags;
            this.lastObservedAt = from;
            this.image = image;
        }

        boolean sameValue(String otherTitle, String otherCategory, List<String> otherTags) {
            return Objects.equals(title, otherTitle) && Objects.equals(category, otherCategory) && tags.equals(otherTags);
        }

        Segment close(Instant until) {
            return new Segment(platform, from, until, lastObservedAt, title, category, image, tags);
        }
    }
}
