package com.pokeclip.clip.broadcast;

import java.time.Instant;

/**
 * 「지금 방송 중인 줄」 한 줄. 수집기가 재시작한 뒤 <b>어디에 다시 붙을지</b>를 묻는 창구가
 * 이것을 준다(POK-218).
 *
 * <p><b>엔티티가 아니라 인터페이스 프로젝션이다.</b> {@link Broadcast}를 그대로 돌려주면
 * {@code track_manifest}(jsonb)를 상한만큼 — 최대 500줄 — 같이 읽는다. 그 칸은 이 창구가
 * 한 번도 안 쓰는 값이고, 크기가 1번(Media)이 넣는 트랙 수에 따라 커진다.
 *
 * <p>도는 것을 확인했다(계획 검증, 2026-08-31) — Spring Data가 {@code jdk.proxy}로 구현을
 * 만들고 {@code Instant} 매핑 · 선행 0 보존({@code "007"}) · {@code null} 시각까지 그대로
 * 넘어온다. 「안 되면 엔티티로 내린다」는 대비책은 <b>실행하지 않았다</b>.
 *
 * <p>칸 이름은 <b>쿼리의 별칭과 맞물려 있다</b>({@code AS streamId} 등). 별칭을 고치면 여기도
 * 같이 고쳐야 하고, 안 고치면 프록시가 값을 못 찾는다.
 */
public interface LiveBroadcastRow {

    String getStreamId();

    String getStreamerId();

    /**
     * 🔴 <b>{@code null}일 수 있다 — 다만 운영 경로로는 도달 불가다.</b> 시각이 빈 {@code live}
     * 줄이 어떻게 막히는지는 {@code LiveStartedAtNeverNullTest}가 사슬로 재고, 그 사슬의 방어는
     * 러너의 봉투 검증 <b>한 줄뿐</b>이다. 그래서 쿼리가 {@code NULLS LAST}로 한 번 더 받친다.
     */
    Instant getStartedAt();
}
