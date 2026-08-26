package com.pokeclip.clip.jumpcard;

import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.delegation.AccessErrors;
import com.pokeclip.clip.delegation.BroadcastAccessGuard;
import com.pokeclip.clip.jumpcard.JumpCardErrors.BroadcastNotFoundException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.ClaimedByOtherException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.JumpCardNotFoundException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.NotClaimOwnerException;
import com.pokeclip.clip.jumpcard.api.HighlightRequest;
import com.pokeclip.clip.jumpcard.stream.CardStreamRegistry;
import com.pokeclip.clip.paging.CursorCodec;
import com.pokeclip.clip.paging.ListLimit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.function.IntSupplier;

/** 카드를 쓰는 유일한 자리. 쓰기는 전부 네이티브 SQL이라 DB 시계와 순번 트리거를 탄다. */
@Service
public class JumpCardService {

    private static final Logger log = LoggerFactory.getLogger(JumpCardService.class);

    /** {@code created}가 거짓이면 같은 창의 카드가 이미 있었다는 뜻이다(200 대 201을 가른다). */
    public record RecordResult(boolean created, JumpCardSnapshot card) {
    }

    /**
     * 웹이 개수를 안 주면 이만큼. 방송(20)보다 큰 것은 한 방송에 1,200장이 쌓인 실측이 있어서다(PRD).
     *
     * <p><b>{@code private}인 것은 밖에서 읽는 곳이 없기 때문이다</b>(시험 포함, 2026-08-26 전수).
     * 값의 정본은 커밋되는 {@code services/README.md}다 — {@code BroadcastListService}와 같은 규칙.
     */
    private static final int DEFAULT_LIST_LIMIT = 50;

    /** 넘겨 달라고 해도 여기서 깎는다(PRD 결정). 판정은 {@link ListLimit}에 있다 — 목록 문 둘이 나눠 쓴다. */
    private static final int MAX_LIST_LIMIT = 200;

    private final JumpCardRepository cards;
    private final BroadcastRepository broadcasts;
    private final JumpCardProperties properties;
    private final ObjectMapper mapper;
    private final CardStreamRegistry registry;
    private final BroadcastAccessGuard guard;

    /**
     * 🔴 <b>{@code @Transactional} 대신 이것을 쓰는 자리가 넷 있다</b>(집기·놓기·숨기기·되돌리기).
     * 그 넷은 <b>자격 판정을 트랜잭션 밖에서</b> 끝내야 하는데, 자기 호출은 프록시를 안 타므로
     * 애너테이션으로는 그 경계를 만들 수 없다. 이유와 실측은 {@link #requireViewableCard} 주석에 있다.
     */
    private final TransactionTemplate transactions;

    JumpCardService(JumpCardRepository cards, BroadcastRepository broadcasts,
                    JumpCardProperties properties, ObjectMapper mapper, CardStreamRegistry registry,
                    BroadcastAccessGuard guard, TransactionTemplate transactions) {
        this.cards = cards;
        this.broadcasts = broadcasts;
        this.properties = properties;
        this.mapper = mapper;
        this.registry = registry;
        this.guard = guard;
        this.transactions = transactions;
    }

    @Transactional
    public RecordResult record(String streamId, HighlightRequest request) {
        // FK 위반으로 막는 대신 먼저 본다 — FK 위반은 "진짜 실패"로 분류돼 500이 되고,
        // 판별기는 404를 받아야 재시도 상한을 센다.
        broadcasts.findByStreamId(streamId).orElseThrow(() -> new BroadcastNotFoundException(streamId));

        JumpCardSource source = JumpCardSource.fromDbValue(request.source());
        String evidence = request.evidence() == null ? null : mapper.writeValueAsString(request.evidence());

        int inserted = cards.insertIfAbsent(streamId, source.dbValue(), request.eventId(),
                request.streamTimestampMs(), request.window().startMs(), request.window().endMs(),
                request.score(), evidence);

        JumpCard card = cards.findByStreamIdAndSourceAndWindowStartMs(streamId, source, request.window().startMs())
                .orElseThrow();
        JumpCardSnapshot snapshot = snapshot(card);

        if (inserted == 1) {
            publishAfterCommit(snapshot);
        } else {
            log.info("jumpcard.duplicate_skipped streamId={} source={} windowStartMs={} eventId={}",
                    streamId, source, request.window().startMs(), request.eventId());
        }
        return new RecordResult(inserted == 1, snapshot);
    }

    /**
     * 🔴 <b>자격 판정이 맨 앞이고, 트랜잭션은 그 뒤에 열린다.</b> 문 넷이 같은 모양이다 —
     * 근거는 {@link #requireViewableCard}.
     */
    public JumpCardSnapshot claim(long id, String userId) {
        requireViewableCard(id, userId);
        return transactions.execute(status -> claimInTx(id, userId));
    }

    private JumpCardSnapshot claimInTx(long id, String userId) {
        int updated = cards.claim(id, userId, properties.claimTtl().toSeconds());
        JumpCard card = cards.findById(id).orElseThrow(() -> new JumpCardNotFoundException(id));
        if (updated == 0) {
            // 행은 있는데 못 잡았다 = 남이 잡고 있다. 「없는 카드」는 위 orElseThrow가 이미 갈랐다.
            throw new ClaimedByOtherException(snapshot(card));
        }
        JumpCardSnapshot snapshot = snapshot(card);
        publishAfterCommit(snapshot);
        return snapshot;
    }

    public void release(long id, String userId) {
        requireViewableCard(id, userId);
        transactions.executeWithoutResult(status -> releaseInTx(id, userId));
    }

    private void releaseInTx(long id, String userId) {
        // 먼저 존재만 본다. 영향 행 0이 「없는 카드」인지 「남의 것」인지 갈라야 404와 403이 다르게 나간다.
        if (!cards.existsById(id)) {
            throw new JumpCardNotFoundException(id);
        }

        // 영향 행 0의 뜻이 둘이다 — 「남이 잡고 있다」와 「아무도 안 잡았다」.
        // 후자는 이미 목표 상태이므로 성공으로 본다(멱등). 전자만 403이다.
        //
        // 그 판정을 UPDATE <b>뒤에</b> 읽은 값으로 한다. 앞에서 읽은 값으로 가르면, 읽은 뒤
        // UPDATE 전에 남이 놓아 버린 경우 「지금은 아무도 안 잡았는데 403」이 나간다 —
        // 데이터는 맞고 응답 코드만 사실과 다른 상태다(로컬 리뷰 사소 ③).
        if (cards.release(id, userId) == 0) {
            JumpCard after = cards.findById(id).orElseThrow(() -> new JumpCardNotFoundException(id));
            if (after.getClaimedBy() != null) {
                throw new NotClaimOwnerException(id);
            }
        }
        publishAfterCommit(snapshot(cards.findById(id).orElseThrow()));
    }

    public JumpCardSnapshot hide(long id, String userId) {
        requireViewableCard(id, userId);
        return transactions.execute(status -> toggleHidden(id, () -> cards.hide(id, userId)));
    }

    public JumpCardSnapshot unhide(long id, String userId) {
        requireViewableCard(id, userId);
        return transactions.execute(status -> toggleHidden(id, () -> cards.unhide(id)));
    }

    /**
     * <b>이 방송을 볼 자격이 있나. 문 넷의 다른 모든 판정보다 앞이다.</b>
     *
     * <p><b>왜 맨 앞인가.</b> 뒤로 옮기면 남이 잡은 카드를 집으려는 남남에게 <b>409 본문</b>이
     * 나간다 — 그 본문은 현재 카드 스냅샷이라 <b>누가 잡고 있는지</b>가 실린다. 시각도 갈린다:
     * 자격을 먼저 물으면 거절이 카드를 읽기 전에 끝난다.
     *
     * <p>🔴 <b>거절을 {@code JumpCardNotFoundException}으로 접는다.</b> 그대로 두면
     * 「없는 카드」는 {@code jump_card_not_found}, 「자격 없는 카드」는 {@code broadcast_not_found}로
     * <b>본문이 갈려</b> 카드 번호를 훑는 것만으로 그 카드의 실재를 알 수 있다(번호가 bigserial이라
     * 연속이다). 방송 문에서 「없는 방송」과 「자격 없음」을 한 본문으로 합친 것과 같은 뿌리다.
     * 사유는 접기 전에 로그로 남긴다 — {@code AccessErrors} 쪽 조언을 안 지나기 때문이다.
     *
     * <p><b>안 덮이는 갈래 하나</b> — auth가 죽으면 없는 카드는 404, 실재하는 카드는 503이라
     * <b>상태 코드로</b> 갈린다. 시간이 아니라 코드라 바닥으로 못 덮고, {@code 503 → 404} 접기는
     * 일부러 안 골랐다(세그먼트 문과 같은 판단 — 화면이 「없다」고 단정하면 auth가 살아난 뒤에도
     * 다시 시도하지 않는다).
     *
     * <p>🔴 <b>{@code @Transactional}이 없는 자리에서 부른다.</b> auth 왕복이 최대 7초인데
     * 트랜잭션 안에서 돌면 그동안 커넥션을 쥔다 — {@code claim}·{@code hide}·{@code unhide}는
     * <b>읽기 전용도 아니라</b> 쓰기 트랜잭션이 7초 열린다. 커넥션은 첫 질의가 아니라
     * <b>트랜잭션이 열릴 때</b> 잡히므로 순서를 바꾸거나 {@code readOnly}를 빼는 것으로는 안 풀린다
     * (POK-174 실측). 그래서 문 넷이 애너테이션 대신 {@link #transactions}를 쓴다 —
     * 자기 호출은 프록시를 안 타기 때문이다.
     * {@code BroadcastListTransactionTest.카드를_집을_때도_auth_왕복_동안_커넥션을_안_쥔다}가 그 불변식을 잰다.
     *
     * <p><b>카드 조회가 한 번 더 도는 것을 감수한다.</b> 아래 갈래들이 각자 다시 읽는데, 여기서
     * 읽은 것을 넘기면 「읽은 뒤 ~ UPDATE 사이」의 값으로 판정하게 되고 {@code release}가 일부러
     * UPDATE <b>뒤에</b> 읽도록 고쳐 둔 이유가 무너진다.
     */
    private void requireViewableCard(long cardId, String requesterSubject) {
        JumpCard card = cards.findById(cardId).orElseThrow(() -> new JumpCardNotFoundException(cardId));
        try {
            guard.requireViewable(requesterSubject, card.getStreamId());
        } catch (AccessErrors.NotViewableException e) {
            // 값은 우리 코드가 정한 고정 문자열이다 — 외부 입력이 그대로 로그로 가지 않는다.
            log.info("jumpcard.access.not_viewable reason={} cardId={}", e.reason(), cardId);
            throw new JumpCardNotFoundException(cardId);
        }
    }

    /**
     * <b>이미 그 상태면 성공이다(멱등).</b> 404를 주면 웹이 새로고침 뒤 숨기기를 눌렀을 때 오류를
     * 본다 — {@code release}를 멱등으로 둔 것과 같은 이유다. 없는 카드만 404다.
     *
     * <p>안 바뀌었으면 <b>발행하지 않는다</b>. 안 그러면 아무것도 안 바뀐 카드가 통로로 나가
     * 화면이 헛돈다(감사가 {@code unhide}에서 지적한 자리).
     */
    private JumpCardSnapshot toggleHidden(long id, IntSupplier write) {
        int updated = write.getAsInt();
        JumpCard card = cards.findById(id).orElseThrow(() -> new JumpCardNotFoundException(id));
        JumpCardSnapshot snapshot = snapshot(card);
        if (updated == 1) {
            publishAfterCommit(snapshot);
        }
        return snapshot;
    }

    /**
     * 카드 목록 한 장.
     *
     * <p><b>자격 판정이 조회보다 앞이다.</b> 뒤로 옮기면 자격 없는 사람의 요청이 그 방송 카드를
     * 읽고 나서 거절된다 — 응답은 같지만 시간이 갈리고, 그것만으로 방송의 실재가 샌다.
     * 요청 칸(개수·표시)은 그보다도 앞이다: 뒤로 밀면 형식 오류 하나가 auth 왕복(최대 7초)을
     * 태우고, 그러고도 나가는 응답이 400이다({@code BroadcastListService}와 같은 순서).
     *
     * <p>🔴 <b>{@code @Transactional}을 붙이지 마라.</b> 붙이면 auth 왕복(최대 7초) 동안 DB 커넥션을
     * 쥔다 — 사람이 기다리는 요청 하나가 풀에서 자리를 그만큼 뺏는다. <b>{@code readOnly}를 빼도,
     * 판정을 먼저 하고 조회를 나중에 해도 마찬가지다</b>: 커넥션은 첫 질의가 아니라 <b>트랜잭션이
     * 열릴 때</b> 잡힌다(POK-174 실측, {@code BroadcastListService} 주석에 재현 기록이 있다).
     * 조회가 하나뿐이라 트랜잭션으로 얻는 것도 없다.
     *
     * <p><b>문 넷도 같은 이유로 애너테이션을 뗐다</b>(POK-174) — 그쪽은 쓰기라 트랜잭션이 필요하지만
     * 자격 판정만은 밖에서 끝내야 해서 {@link #transactions}로 경계를 손수 긋는다.
     * 「정리」로 여기에 붙이면 {@code BroadcastListTransactionTest.카드_목록도_auth_왕복_동안_커넥션을_안_쥔다}가
     * 빨간불이 된다 — 그 그물은 애너테이션이 아니라 <b>왕복 중 활성 커넥션 수</b>를 잰다.
     *
     * @param requesterSubject JWT {@code sub} — 우리가 발급·검증한 토큰의 회원 번호(문자열)
     * @param limit {@code null}이면 {@link #DEFAULT_LIST_LIMIT}
     * @param cursor {@code null}이면 첫 장
     * @throws com.pokeclip.clip.paging.InvalidListParamException 개수가 범위 밖이다 (400)
     * @throws com.pokeclip.clip.paging.InvalidCursorException 표시가 우리 모양이 아니다 (400)
     * @throws com.pokeclip.clip.delegation.AccessErrors.NotViewableException
     *         방송이 없거나 볼 자격이 없다 (404, 같은 본문)
     * @throws com.pokeclip.clip.delegation.AccessErrors.AuthUnavailableException
     *         자격을 물어보지 못했다 (503)
     */
    public JumpCardPage listOf(String requesterSubject, String streamId, boolean includeHidden,
                               Integer limit, String cursor) {
        int size = ListLimit.resolve(limit, DEFAULT_LIST_LIMIT, MAX_LIST_LIMIT);
        // 표시는 두 값을 함께 싣는다 — 하나만 오면 같은 방송 시간의 뒷줄이 조용히 빠진다
        // (CursorCodec.Kind.CARD의 칸 수 검사가 그것을 막는다. findPage 주석 참고).
        List<Long> after = cursor == null ? null : CursorCodec.decode(CursorCodec.Kind.CARD, cursor);

        guard.requireViewable(requesterSubject, streamId);

        // 상한 하나를 더 받아 「다음 장이 있나」를 본다. 개수를 따로 세면 질의가 하나 더 돈다.
        List<JumpCard> rows = cards.findPage(streamId, includeHidden,
                after == null ? null : after.get(0), after == null ? null : after.get(1), size + 1);
        boolean hasMore = rows.size() > size;
        List<JumpCard> page = hasMore ? rows.subList(0, size) : rows;
        JumpCard last = page.isEmpty() ? null : page.get(page.size() - 1);
        String next = hasMore
                ? CursorCodec.encode(CursorCodec.Kind.CARD, last.getStreamTimestampMs(), last.getId())
                : null;
        return new JumpCardPage(page.stream().map(this::snapshot).toList(), next);
    }

    /**
     * 그 방송 카드 전부, 순번 순.
     *
     * <p>🔴 <b>운영 코드에서 이것을 부르는 자리가 없다</b>(2026-08-26 전수 확인, {@code src/main}에
     * 선언 한 줄뿐). 통로가 연결 직후에 카드를 보내던 것이 유일한 호출자였고 POK-174가 그 전송을
     * 없앴다. 지금 쓰는 것은 <b>시험 아홉 자리 · 네 클래스</b>뿐이다.
     *
     * <p><b>그런데도 남긴다 — 지우면 카드를 읽는 법이 두 벌이 되기 때문이다.</b> 지금은 시험이
     * {@link #listOf}와 <b>같은 private {@code snapshot(...)}</b>을 지나 읽는다. 이것을 지우면 아홉
     * 자리가 리포지터리로 내려가 정렬(<b>{@code event_seq} 오름차순</b>)과 {@code claimTtl}·
     * {@code mapper} 배선을 <b>시험 쪽에 다시</b> 쓰게 된다. {@code JumpCardSnapshot.of}가 public이라
     * 매핑 함수 자체는 나눠 쓸 수 있지만 <b>그 인자를 어디서 가져오는지가 갈린다</b> — 운영이 TTL의
     * 출처를 바꿔도 시험은 옛 배선으로 <b>초록</b>이다. 가시성을 좁히는 절충도 막혀 있다:
     * 호출 아홉 중 <b>일곱이 {@code jumpcard.stream} 패키지</b>라 package-private으로는 안 컴파일된다
     * (2026-08-26 실측: 그 일곱 자리에서 정확히 오류 7건).
     *
     * <p><b>지울 수 있게 되는 조건</b> — 그 아홉 자리가 <b>운영과 같은 배선을 지나는</b> 다른 읽기
     * 수단으로 옮겨 갔을 때다(목록 문을 쓰거나, 운영 빈을 그대로 받는 공용 시험 도우미). 그때는
     * 이 메서드가 아무것도 안 지키므로 지운다.
     *
     * <p>「따라잡기」를 마진 방식으로 바꾸는 날(PRD) 되살아난다는 것은 <b>근거로 안 쓴다</b> —
     * 그 방식은 「받은 마지막 것 뒤로」를 읽으므로 <b>전부를 읽는 이 질의가 아닐 가능성이 높다.</b>
     * 재 보지 않았다.
     */
    @Transactional(readOnly = true)
    public List<JumpCardSnapshot> snapshotsOf(String streamId) {
        return cards.findAllByStreamIdOrderByEventSeqAsc(streamId).stream().map(this::snapshot).toList();
    }

    private JumpCardSnapshot snapshot(JumpCard card) {
        return JumpCardSnapshot.of(card, properties.claimTtl(), mapper);
    }

    /**
     * 카드가 생기거나 바뀌었다고 알리는 유일한 자리. <b>커밋 뒤에만</b> 보낸다 —
     * 커밋 전에 보내면 되감긴 카드가 화면에 뜨고, 지울 방법이 없다.
     *
     * <p>{@code afterCommit} 안에서는 <b>제출만</b> 한다. 전송은 {@code CardStreamExecutor}의
     * 전용 스레드가 하므로 요청 스레드가 느린 브라우저에 묶이지 않는다 — 커밋 뒤 처리를 요청
     * 스레드에 이어 붙였다가 커넥션 풀 데드락을 낸 자리가 POK-93이다(ChzzkCleanupExecutor와 같은 분리).
     *
     * <p>트랜잭션이 없으면 {@code IllegalStateException}이 난다. 그게 맞다 — 자기 호출로
     * {@code @Transactional} 프록시를 안 탄 것을 여기서 잡아 준다.
     */
    private void publishAfterCommit(JumpCardSnapshot snapshot) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                registry.publish(snapshot);
            }
        });
    }
}
