package com.pokeclip.clip.delegation;

import java.util.List;

/**
 * 「이 사람이 볼 수 있는 스트리머 전부」에 대한 auth의 답.
 *
 * <p><b>{@code available}이 거짓인 것과 목록이 빈 것은 다르다.</b> 빈 목록은
 * 「볼 방송이 없다」는 참인 답이고, {@code available=false}는 <b>물어보지 못했다</b>는 뜻이다.
 * 둘을 합치면 auth가 죽은 동안 화면이 「방송이 없다」고 단정한다 — 편집자는 다시 시도하지 않는다.
 *
 * <p>{@link ResolveResult#UNAVAILABLE}을 값으로 쓰지 않는 이유 — 그것은 <b>한 쌍</b>의 판정이고
 * 여기는 목록이다. 목록 안의 한 줄이 UNAVAILABLE인 상황은 없다.
 */
public record AccessibleResult(boolean available, List<Entry> streamers) {

    /**
     * <b>{@code NONE}은 여기 안 나온다</b> — 목록에 없는 것이 곧 {@code NONE}이다.
     * 그래서 값의 범위는 {@code OWNER}·{@code EDITOR} 둘뿐이고, 그것을 넘어서는 값이 오면
     * 줄이 아니라 <b>목록 전체</b>가 거절된다({@code DelegationResolveClient.accessible}).
     */
    public record Entry(long streamerUserId, ResolveResult relation) {
    }

    private static final AccessibleResult UNAVAILABLE = new AccessibleResult(false, List.of());

    public static AccessibleResult unavailable() {
        return UNAVAILABLE;
    }

    public static AccessibleResult of(List<Entry> streamers) {
        return new AccessibleResult(true, List.copyOf(streamers));
    }

    /**
     * 한 스트리머로 좁힌다(채널 스위처, POK-390). {@code null}이면 그대로, 목록에 없으면 빈 목록이다:
     * 호출부가 그것을 404로 접는다.
     *
     * <p>🔴 <b>물어보지 못한 답은 그대로 돌려준다.</b> {@link #of}로 새로 만들면 {@code available}이 참이 되어,
     * 호출부가 이것을 {@code available()} 검사보다 먼저 부르는 날 auth 장애(503)가 「볼 수 없다」(404)로 둔갑한다.
     */
    public AccessibleResult narrowTo(Long streamerUserId) {
        if (streamerUserId == null || !available) {
            return this;
        }
        return of(streamers.stream().filter(e -> e.streamerUserId() == streamerUserId).toList());
    }
}
