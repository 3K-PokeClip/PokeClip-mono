package com.pokeclip.clip.jumpcard;

import com.pokeclip.clip.broadcast.BroadcastRepository;
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

    /** 웹이 개수를 안 주면 이만큼. 방송(20)보다 큰 것은 한 방송에 1,200장이 쌓인 실측이 있어서다(PRD). */
    public static final int DEFAULT_LIST_LIMIT = 50;

    /** 넘겨 달라고 해도 여기서 깎는다(PRD 결정). 판정은 {@link ListLimit}에 있다 — 목록 문 둘이 나눠 쓴다. */
    public static final int MAX_LIST_LIMIT = 200;

    private final JumpCardRepository cards;
    private final BroadcastRepository broadcasts;
    private final JumpCardProperties properties;
    private final ObjectMapper mapper;
    private final CardStreamRegistry registry;
    private final BroadcastAccessGuard guard;

    JumpCardService(JumpCardRepository cards, BroadcastRepository broadcasts,
                    JumpCardProperties properties, ObjectMapper mapper, CardStreamRegistry registry,
                    BroadcastAccessGuard guard) {
        this.cards = cards;
        this.broadcasts = broadcasts;
        this.properties = properties;
        this.mapper = mapper;
        this.registry = registry;
        this.guard = guard;
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

    @Transactional
    public JumpCardSnapshot claim(long id, String userId) {
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

    @Transactional
    public void release(long id, String userId) {
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

    @Transactional
    public JumpCardSnapshot hide(long id, String userId) {
        return toggleHidden(id, () -> cards.hide(id, userId));
    }

    @Transactional
    public JumpCardSnapshot unhide(long id, String userId) {
        return toggleHidden(id, () -> cards.unhide(id));
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
     * <p><b>이 클래스의 다른 메서드에 {@code @Transactional}이 붙어 있는 것과 어긋나 보이지만
     * 아니다</b> — 그쪽들은 쓰기이고 커밋 뒤 발행({@code afterCommit})이 트랜잭션을 필요로 한다.
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
