package com.pokeclip.clip.broadcast;

import com.pokeclip.clip.delegation.AccessErrors;
import com.pokeclip.clip.delegation.AccessibleResult;
import com.pokeclip.clip.delegation.DelegationResolveClient;
import com.pokeclip.clip.delegation.ResolveResult;
import com.pokeclip.clip.paging.CursorCodec;
import com.pokeclip.clip.paging.InvalidListParamException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「내가 볼 수 있는 스트리머의 방송」 한 장을 만든다.
 *
 * <p><b>순서가 계약이다</b> — 요청 칸을 먼저 보고, 볼 수 있는 스트리머를 받고, 그 번호로만
 * 조회한다. 조회를 먼저 하고 거르면 남의 방송이 메모리에 올라오고, 거르는 조건이 한 줄
 * 틀리면 그대로 나간다.
 *
 * <p>요청 칸 검증이 <b>맨 앞</b>인 것도 같은 이유의 다른 면이다 — 뒤로 밀면 형식 오류 하나가
 * auth 왕복(최대 7초)을 태우고, 그러고도 나가는 응답이 400이다.
 *
 * <p><b>개수 규칙이 컨트롤러가 아니라 여기 있다.</b> 이 서비스의 소비자가 웹 문 하나뿐인
 * 지금은 두 자리가 같아 보이지만, 소비자가 늘면 컨트롤러를 안 거치는 쪽에 상한이 없어진다 —
 * {@code SegmentQueryService.MAX_RANGE_MS}가 같은 이유로 서비스에 있다.
 *
 * <p>🔴 <b>{@code @Transactional}을 붙이지 마라.</b> 붙이면 auth 왕복(최대 7초) 동안
 * DB 커넥션을 쥔다 — 사람이 기다리는 요청 하나가 풀에서 자리를 그만큼 뺏는다.
 * <b>{@code readOnly}를 빼도 마찬가지다</b>(실측: 둘 다 왕복 중 {@code active=1}).
 * 조회가 하나뿐이라 트랜잭션으로 얻는 것도 없다.
 *
 * <p><b>순서를 바꾸는 것으로는 피할 수 없다.</b> 이 메서드는 auth를 <b>먼저</b> 부르고
 * 조회를 나중에 하는데도 쥔다 — 즉 커넥션은 첫 질의가 아니라 <b>트랜잭션이 열릴 때</b>
 * 잡힌다. 🔴 <b>효과만 쟀다. 왜 그렇게 잡히는지(하이버네이트 획득 모드가 무엇을 하는지)는
 * 안 쟀다.</b>
 *
 * <p>🔴 <b>이 문단은 한때 정반대를 말했다.</b> 「지금 순서는 <b>붙여도 안 쥐지만</b> 조회를
 * 앞으로 옮기면 위험하다」였다. 구현자와 감사자가 <b>같은 논증</b>(지연 획득)으로 그렇게 믿었고
 * <b>둘 다 틀렸다</b> — 갈라 준 것은 합의가 아니라 측정이다. 그 문장은 틀린 사실이면서 동시에
 * <b>허가</b>였다: 「이 순서에선 무해하다」는 읽는 사람에게 붙여도 된다는 신호였다.
 * 그래서 사실만이 아니라 <b>방향까지</b> 뒤집어 다시 적었다.
 *
 * <p>진짜 트랜잭션이 필요해지는 날의 길은 {@code readOnly}를 빼는 것이 아니라
 * <b>판정을 트랜잭션 밖에 두는 것</b>이다(계획 검증 M5의 처방) — 트랜잭션 없는 공개
 * 메서드가 auth를 먼저 부르고, 그 뒤 트랜잭션인 <b>다른 빈</b>의 메서드를 부른다.
 *
 * <p>{@code BroadcastListTransactionTest}가 이 불변식을 지킨다. 그 시험은 애너테이션이
 * 아니라 <b>「auth 왕복 동안 커넥션을 쥐나」</b>를 재므로, 애너테이션·순서·획득 모드 중
 * 무엇이 바뀌어도 빨간불이 된다.
 */
@Service
public class BroadcastListService {

    private static final Logger log = LoggerFactory.getLogger(BroadcastListService.class);

    /** 웹이 개수를 안 주면 이만큼. 카드(50)보다 작은 것은 한 줄이 훨씬 굵어서다. */
    public static final int DEFAULT_LIMIT = 20;

    /** 넘겨 달라고 해도 여기서 깎는다(PRD 결정). */
    public static final int MAX_LIMIT = 100;

    private final BroadcastRepository broadcasts;
    private final DelegationResolveClient delegation;

    BroadcastListService(BroadcastRepository broadcasts, DelegationResolveClient delegation) {
        this.broadcasts = broadcasts;
        this.delegation = delegation;
    }

    /**
     * @param requesterSubject JWT {@code sub} — 우리가 발급·검증한 토큰의 회원 번호(문자열)
     * @param limit {@code null}이면 {@link #DEFAULT_LIMIT}
     * @param cursor {@code null}이면 첫 장
     * @throws InvalidListParamException 개수가 범위 밖이다 (400)
     * @throws com.pokeclip.clip.paging.InvalidCursorException 이어받기 표시가 우리 모양이 아니다 (400)
     * @throws AccessErrors.NotViewableException 토큰의 주체를 회원 번호로 못 읽는다 (404)
     * @throws AccessErrors.AuthUnavailableException 볼 수 있는 스트리머를 물어보지 못했다 (503)
     */
    public BroadcastPage list(String requesterSubject, BroadcastState state, Integer limit, String cursor) {
        int size = 개수를_정한다(limit);
        Long afterId = cursor == null ? null
                : CursorCodec.decode(CursorCodec.Kind.BROADCAST, cursor).get(0);
        long userId = 요청자_번호(requesterSubject);

        AccessibleResult accessible = delegation.accessible(userId);
        if (!accessible.available()) {
            // 빈 목록으로 접지 않는다 — 화면이 「방송이 없다」고 단정하면 auth가 살아난 뒤에도
            // 편집자는 다시 시도하지 않는다(PRD 결정).
            throw new AccessErrors.AuthUnavailableException();
        }

        // 🔴 숫자를 문자열로 되돌린다. 그 변환이 관대하지 않다는 것(선행 0)은 findPage 주석에 있다.
        List<String> streamerIds = accessible.streamers().stream()
                .map(entry -> String.valueOf(entry.streamerUserId()))
                .toList();
        if (streamerIds.isEmpty()) {
            // 빈 목록도 예외 없이 0행이라 이 갈래가 없어도 죽지는 않는다(계획 검증 실측).
            // 그래도 두는 것은 ① 질의 한 번을 아끼고 ② 「빈 목록이 온다」는 가정을 코드에
            // 드러내려는 것이다 — 지금 그 사실은 auth 쪽 코드에만 적혀 있다.
            //
            // 🔴 이 방어가 막는 것은 「죽지 않는 것」까지다. auth가 본인을 안 넣게 되면
            //    위임 없는 스트리머의 목록이 0행을 200으로 내보낸다 — 자기 방송이 자기 홈에서
            //    사라지는데 오류가 아니다. 그것은 계약이라 clip이 못 막는다(README에 적었다).
            return BroadcastPage.empty();
        }

        Map<String, ResolveResult> relations = 관계를_번호로_모은다(accessible);

        // 상한 하나를 더 받아 「다음 장이 있나」를 본다. 개수를 따로 세면 질의가 하나 더 돈다.
        List<Broadcast> rows = broadcasts.findPage(streamerIds, state.dbValues(), afterId, size + 1);
        boolean hasMore = rows.size() > size;
        List<Broadcast> page = hasMore ? rows.subList(0, size) : rows;
        String next = hasMore
                ? CursorCodec.encode(CursorCodec.Kind.BROADCAST, page.get(page.size() - 1).getId())
                : null;
        return new BroadcastPage(page, relations, next);
    }

    /**
     * 안 주면 기본, 넘치면 깎고, <b>0 이하는 거절한다</b>.
     *
     * <p>0을 「기본으로 봐 주는」 길도 있었지만 안 골랐다 — 웹이 계산 실수로 0을 보낸 것과
     * 일부러 0장을 요구한 것이 구분이 안 되고, 조용히 20장을 주면 그 실수가 안 드러난다.
     */
    private static int 개수를_정한다(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit <= 0) {
            throw new InvalidListParamException("limit");
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * 🔴 <b>{@code toMap}이 아니라 직접 모은다.</b> 같은 번호가 두 번 오면 {@code toMap}은
     * {@code IllegalStateException}으로 죽고 그것은 500이다. 오늘은 auth의 유일 제약이
     * 막아 주지만 <b>clip은 그 불변식을 볼 수 없다</b> — 남의 서버 표의 제약에 우리 문의
     * 생사를 걸지 않는다. 겹치면 <b>먼저 온 줄</b>을 남긴다(둘 중 무엇이든 관계는 같은 값이다).
     */
    private static Map<String, ResolveResult> 관계를_번호로_모은다(AccessibleResult accessible) {
        Map<String, ResolveResult> relations = new LinkedHashMap<>();
        for (AccessibleResult.Entry entry : accessible.streamers()) {
            relations.putIfAbsent(String.valueOf(entry.streamerUserId()), entry.relation());
        }
        return relations;
    }

    /**
     * 우리 토큰인데 {@code sub}가 숫자가 아니면 auth에 물어볼 수가 없다. <b>ERROR를 남기고</b>
     * 「볼 수 없다」로 접는다 — {@code BroadcastAccessGuard.parseNumeric}·
     * {@code SegmentQueryService.parseNumeric}과 <b>같은 판정이다</b>(셋이 같은 모양이라야
     * 한 문만 다르게 답하는 일이 없다). 다른 것은 로그 이름뿐이다({@code clip.list.*}) —
     * 자리가 갈려야 어느 문이 아픈지가 보인다.
     *
     * <p>ERROR인 이유는 이것이 <b>조용한 장애</b>이기 때문이다. 응답으로는 영영 구분이 안 된다.
     * <b>값 자체는 안 찍는다</b> — 개행이 섞이면 로그 한 줄이 여러 줄로 쪼개져 없던 기록을
     * 위조할 수 있다. 저 셋과 달리 여기엔 실을 {@code streamId}가 없다(문이 목록이다).
     */
    private static long 요청자_번호(String requesterSubject) {
        try {
            return Long.parseLong(requesterSubject);
        } catch (NumberFormatException e) {
            log.error("clip.list.identity_not_numeric reason=subject_not_numeric");
            throw new AccessErrors.NotViewableException("subject_not_numeric");
        }
    }
}
