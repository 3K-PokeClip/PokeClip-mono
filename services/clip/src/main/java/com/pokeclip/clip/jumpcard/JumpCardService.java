package com.pokeclip.clip.jumpcard;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.delegation.AccessErrors;
import com.pokeclip.clip.delegation.BroadcastAccessGuard;
import com.pokeclip.clip.jumpcard.JumpCardErrors.BroadcastNotFoundException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.HotkeyBroadcastGoneException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.HotkeyEventOwnedByOtherException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.JumpCardNotFoundException;
import com.pokeclip.clip.jumpcard.api.HighlightRequest;
import com.pokeclip.clip.jumpcard.stream.CardStreamRegistry;
import com.pokeclip.clip.paging.CursorCodec;
import com.pokeclip.clip.paging.ListLimit;
import com.pokeclip.clip.thumbnail.ThumbnailUrls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** 수동 카드 구간: 누른 시각 앞 40초 · 뒤 20초(PRD, 사용자 2026-10-22). */
    private static final long HOTKEY_BEFORE_MS = 40_000L;
    private static final long HOTKEY_AFTER_MS = 20_000L;

    /** 같은 방송의 앞 수동 카드와 이만큼 안이면 새로 안 만든다. 양 끝 제외(19,999ms는 무시 · 20,000ms는 새 카드). */
    private static final long HOTKEY_SUPPRESS_MS = 20_000L;

    private final JumpCardRepository cards;
    private final BroadcastRepository broadcasts;
    private final ObjectMapper mapper;
    private final CardStreamRegistry registry;
    private final BroadcastAccessGuard guard;
    private final ThumbnailUrls thumbnails;
    private final ApplicationEventPublisher events;

    /**
     * 🔴 <b>열기·가리기·되살리기 문이 {@code @Transactional} 대신 이것을 쓴다.</b> 자격 판정을 트랜잭션 밖에서
     * 끝내야 하는데, 자기 호출은 프록시를 안 타므로 애너테이션으로는 그 경계를 만들 수 없다. 이유와 실측은 {@link #requireViewableCard} 주석에 있다.
     */
    private final TransactionTemplate transactions;

    JumpCardService(JumpCardRepository cards, BroadcastRepository broadcasts,
                    ObjectMapper mapper, CardStreamRegistry registry,
                    BroadcastAccessGuard guard, TransactionTemplate transactions, ThumbnailUrls thumbnails,
                    ApplicationEventPublisher events) {
        this.thumbnails = thumbnails;
        this.events = events;
        this.cards = cards;
        this.broadcasts = broadcasts;
        this.mapper = mapper;
        this.registry = registry;
        this.guard = guard;
        this.transactions = transactions;
    }

    /**
     * 자동 카드(2A). <b>자동만 받는다</b>(POK-391): 수동 카드는 {@link #recordHotkey}로만 만든다.
     * 컨트롤러가 {@code hotkey}를 400으로 먼저 거절하므로 여기 오는 다른 출처는 우리 코드의 버그다.
     */
    @Transactional
    public RecordResult record(String streamId, HighlightRequest request) {
        if (JumpCardSource.fromDbValue(request.source()) != JumpCardSource.AUTO) {
            throw new IllegalArgumentException("2A는 자동 카드만 받는다");
        }
        // FK 위반으로 막는 대신 먼저 본다 — FK 위반은 "진짜 실패"로 분류돼 500이 되고,
        // 판별기는 404를 받아야 재시도 상한을 센다.
        broadcasts.findByStreamId(streamId).orElseThrow(() -> new BroadcastNotFoundException(streamId));

        String evidence = request.evidence() == null ? null : mapper.writeValueAsString(request.evidence());

        int inserted = cards.insertAutoIfAbsent(streamId, request.eventId(),
                request.streamTimestampMs(), request.window().startMs(), request.window().endMs(),
                request.score(), evidence);

        JumpCard card = cards.findAutoByStreamIdAndWindowStartMs(streamId, request.window().startMs()).orElseThrow();
        JumpCardSnapshot snapshot = snapshot(card);

        if (inserted == 1) {
            publishAfterCommit(snapshot);
            events.publishEvent(new JumpCardCreated(card.getId()));
        } else {
            log.info("jumpcard.duplicate_skipped streamId={} source={} windowStartMs={} eventId={}",
                    streamId, JumpCardSource.AUTO, request.window().startMs(), request.eventId());
        }
        return new RecordResult(inserted == 1, snapshot);
    }

    /**
     * 수동 카드 한 장(POK-391, 계약4 4B). 순서가 규칙이다: 방송 행 잠금 → eventId → 20초 → 저장 → 커밋 뒤 발행.
     *
     * <ul>
     *   <li>같은 eventId의 수동 카드가 있으면 그것({@code created=false}). 다른 방송의 카드여도 그것이다
     *       (계약4: eventId는 전역 유일). <b>그 카드의 방송이 {@code userId}의 것이 아니면</b>
     *       {@link HotkeyEventOwnedByOtherException}(계획 검증 E4)</li>
     *   <li>같은 방송에 누른 시각이 {@code |T − 앞| < 20초}인 수동 카드가 있으면 가장 가까운 것({@code created=false})</li>
     *   <li>아니면 새 카드. 구간은 {@code [max(0, T − 40초), T + 20초]}, {@code streamTimestampMs = T}</li>
     * </ul>
     *
     * <p>🔴 <b>잠금이 20초 판정을 지킨다.</b> 다른 eventId 둘이 20초 안에 동시에 오면 유일 색인은 못 막는다 :
     * 둘 다 「앞 카드 없음」을 보고 둘 다 넣는다. 그래서 같은 방송의 마킹을 방송 행으로 줄 세운다.
     * {@code findByStreamIdForUpdate}는 PostgreSQL에서 {@code FOR NO KEY UPDATE}다(계획 검증 E8): 같은 잠금끼리는
     * 줄을 서지만, 2A 저장의 FK 검사가 거는 {@code FOR KEY SHARE}와는 안 부딪혀 자동 카드 수신을 막지 않는다.
     * 잠금을 기다린 뒤의 조회는 앞 트랜잭션이 커밋한 카드를 본다(READ COMMITTED는 문장마다 새로 읽는다).
     *
     * <p>넘겨받은 방송의 주인이 {@code userId}가 아니면 {@link HotkeyBroadcastGoneException}이다(404 갈래: 남의 방송의
     * 존재를 안 흘린다). eventId로 찾은 카드는 그 카드 방송의 주인을 따로 본다.
     *
     * @param pressedMs 누른 시각을 영상 위치(방송 기준 ms)로 바꾼 값. 0 이상
     * @throws HotkeyBroadcastGoneException 방송이 잠그려는 사이 사라졌거나 이 회원의 방송이 아니다(404 갈래)
     * @throws HotkeyEventOwnedByOtherException eventId가 남의 방송 카드다(400 갈래)
     * @throws IllegalArgumentException {@code pressedMs}가 음수다: 구간 CHECK({@code start ≤ ts})가 깨진다
     */
    public RecordResult recordHotkey(String streamId, String userId, String eventId, long pressedMs) {
        if (pressedMs < 0) {
            throw new IllegalArgumentException("누른 시각이 방송 시작 앞이다");
        }
        long start = Math.max(0, pressedMs - HOTKEY_BEFORE_MS);
        long end = Math.addExact(pressedMs, HOTKEY_AFTER_MS);
        return transactions.execute(status -> {
            Broadcast locked = broadcasts.findByStreamIdForUpdate(streamId)
                    .orElseThrow(HotkeyBroadcastGoneException::new);
            // 넘겨받은 방송이 이 회원의 것이 아니면 「없다」로 접는다: 남의 방송의 존재를 안 흘린다.
            // 지금 부르는 쪽(마킹 서비스)은 회원으로 방송을 고르지만, 다른 길로 방송을 넘기는 호출자가
            // 생겨도 남의 방송에 카드가 안 붙게 여기서 본다(감사 1회차 A1).
            if (!userId.equals(locked.getStreamerId())) {
                throw new HotkeyBroadcastGoneException();
            }

            Optional<JumpCard> sameEvent = cards.findHotkeyByEventId(eventId);
            if (sameEvent.isPresent()) {
                return existing(sameEvent.get(), userId);
            }

            Optional<JumpCard> near = cards.findHotkeyNear(streamId, pressedMs, HOTKEY_SUPPRESS_MS);
            if (near.isPresent()) {
                JumpCard card = near.get();
                log.info("jumpcard.hotkey_suppressed nearCardId={} deltaMs={}",
                        card.getId(), pressedMs - card.getStreamTimestampMs());
                return new RecordResult(false, snapshot(card));
            }

            int inserted = cards.insertHotkey(streamId, eventId, pressedMs, start, end);
            JumpCard card = cards.findHotkeyByEventId(eventId).orElseThrow();
            if (inserted == 0) {
                // 잠금 밖에서 같은 eventId가 다른 방송으로 먼저 들어갔다. 색인이 막았고 그 카드를 다시 읽었다.
                return existing(card, userId);
            }
            JumpCardSnapshot snapshot = snapshot(card);
            publishAfterCommit(snapshot);
            events.publishEvent(new JumpCardCreated(card.getId()));
            log.info("jumpcard.hotkey_created cardId={} by={}", card.getId(), userId);
            return new RecordResult(true, snapshot);
        });
    }

    /**
     * 같은 eventId의 수동 카드가 이미 있으면 그것(마킹 문의 선조회, 방송 고르기 앞). 방송이 끝난 뒤 온 재전송도
     * 같은 카드 200을 받게 하려고 방송을 고르기 전에 본다. 그 카드가 이 회원의 방송 카드가 아니면 거절한다.
     *
     * @throws HotkeyEventOwnedByOtherException eventId가 남의 방송 카드다(400 갈래)
     */
    public Optional<RecordResult> existingHotkey(String eventId, String userId) {
        return cards.findHotkeyByEventId(eventId).map(card -> existing(card, userId));
    }

    /** eventId로 찾은 카드를 돌려준다. 그 카드의 방송 주인이 이 회원이 아니면 거절한다(계획 검증 E4). */
    private RecordResult existing(JumpCard card, String userId) {
        String owner = broadcasts.findStreamerIdByStreamId(card.getStreamId()).orElse(null);
        if (!userId.equals(owner)) {
            log.info("jumpcard.hotkey_event_owned_by_other cardId={} by={}", card.getId(), userId);
            throw new HotkeyEventOwnedByOtherException();
        }
        return new RecordResult(false, snapshot(card));
    }

    /**
     * 편집기가 카드를 열었다고 행동 기록에 남긴다(POK-393). 카드가 안 바뀌므로 발행하지 않는다.
     *
     * <p>자격 판정은 트랜잭션 밖이다(근거는 {@link #requireViewableCard}). 판정 뒤 카드가 지워졌으면
     * {@code recordOpen}이 0을 주고 404로 접는다. 탈퇴 정리와 동시인 경우는 {@code recordOpen} 주석.
     */
    public void open(long id, String userId) {
        requireViewableCard(id, userId);
        transactions.executeWithoutResult(status -> {
            if (cards.recordOpen(id, userId) == 0) {
                throw new JumpCardNotFoundException(id);
            }
        });
        log.info("jumpcard.opened cardId={} by={}", id, userId);
    }

    /**
     * 카드를 가린다(POK-463). 화면의 「숨김」 칸으로 가고 목록 기본값에서 빠진다. 지우지 않는다: 되살릴 수 있다.
     * 편집자와 스트리머가 같은 권한이다(2026-10-22 결정): 볼 수 있으면 가린다.
     *
     * <p>이미 가린 카드면 바꾸지 않고 지금 카드를 돌려준다(200). 기록·발행도 바뀐 때만이다: 단추를 두 번 누른 것이
     * 「두 번 가렸다」로 세지거나 화면이 헛돌지 않게.
     */
    public JumpCardSnapshot hide(long id, String userId) {
        requireViewableCard(id, userId);
        return transactions.execute(status -> toggleHidden(id, "hide", userId, () -> cards.hide(id, userId)));
    }

    /** 되살린다(POK-463). 누구나 한다({@link JumpCardRepository#unhide} 주석). 안 가린 카드면 지금 카드를 돌려준다. */
    public JumpCardSnapshot unhide(long id, String userId) {
        requireViewableCard(id, userId);
        return transactions.execute(status -> toggleHidden(id, "unhide", userId, () -> cards.unhide(id)));
    }

    /** 가리기·되살리기의 공통 꼬리: 쓰고, 다시 읽고, 바뀌었을 때만 기록하고 커밋 뒤에 발행한다. */
    private JumpCardSnapshot toggleHidden(long id, String action, String userId, IntSupplier write) {
        int updated = write.getAsInt();
        // 판정 뒤 카드가 지워졌으면(탈퇴 정리) 404로 접는다. 다시 읽는 것은 UPDATE 뒤라 지금 값이다.
        JumpCard card = cards.findById(id).orElseThrow(() -> new JumpCardNotFoundException(id));
        JumpCardSnapshot snapshot = snapshot(card);
        if (updated == 1) {
            cards.recordAction(id, action, userId);
            log.info("jumpcard.{} cardId={} by={}", action, id, userId);
            publishAfterCommit(snapshot);
        }
        return snapshot;
    }

    /**
     * <b>이 방송을 볼 자격이 있나. 열기·가리기·되살리기 문의 쓰기보다 앞이다.</b> 뒤에 두면 자격 없는 사람의
     * 요청이 카드를 바꾸거나 기록에 남는다.
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
     * 트랜잭션 안에서 돌면 그동안 커넥션을 쥔다. 세 문 다 <b>쓰기라</b> 쓰기 트랜잭션이 7초 열린다. 커넥션은 첫 질의가 아니라
     * <b>트랜잭션이 열릴 때</b> 잡히므로 순서를 바꾸거나 {@code readOnly}를 빼는 것으로는 안 풀린다
     * (POK-174 실측). 그래서 세 문이 애너테이션 대신 {@link #transactions}를 쓴다 :
     * 자기 호출은 프록시를 안 타기 때문이다.
     * {@code BroadcastListTransactionTest.카드를_열_때도_auth_왕복_동안_커넥션을_안_쥔다}가 그 불변식을 잰다.
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
     * <p><b>열기 문도 같은 이유로 애너테이션을 안 붙였다.</b> 그쪽은 쓰기라 트랜잭션이 필요하지만
     * 자격 판정만은 밖에서 끝내야 해서 {@link #transactions}로 경계를 손수 긋는다.
     * 「정리」로 여기에 붙이면 {@code BroadcastListTransactionTest.카드_목록도_auth_왕복_동안_커넥션을_안_쥔다}가
     * 빨간불이 된다 — 그 그물은 애너테이션이 아니라 <b>왕복 중 활성 커넥션 수</b>를 잰다.
     *
     * @param requesterSubject JWT {@code sub} — 우리가 발급·검증한 토큰의 회원 번호(문자열)
     * @param includeHidden 가린 카드도 싣나(POK-463). 화면의 「숨김」 칸과 편집기가 켠다
     * @param limit {@code null}이면 {@link #DEFAULT_LIST_LIMIT}
     * @param cursor {@code null}<b>이거나 빈 문자열이면</b> 첫 장 ({@code CursorCodec.decodeOrFirstPage})
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
        List<Long> after = CursorCodec.decodeOrFirstPage(CursorCodec.Kind.CARD, cursor);

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
        Map<Long, String> pictures = thumbnails.ofCards(page.stream().map(JumpCard::getId).toList());
        return new JumpCardPage(page.stream()
                .map(card -> snapshot(card).withThumbnailUrl(pictures.get(card.getId())))
                .toList(), next);
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
     * 자리가 리포지터리로 내려가 정렬(<b>{@code event_seq} 오름차순</b>)과
     * {@code mapper} 배선을 <b>시험 쪽에 다시</b> 쓰게 된다. {@code JumpCardSnapshot.of}가 public이라
     * 매핑 함수 자체는 나눠 쓸 수 있지만 <b>그 인자를 어디서 가져오는지가 갈린다</b>: 운영이 매퍼의
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
        return JumpCardSnapshot.of(card, mapper);
    }

    /**
     * 카드가 생기거나 바뀌었다고 <b>화면에</b> 알리는 유일한 자리. <b>커밋 뒤에만</b> 보낸다 —
     * 커밋 전에 보내면 되감긴 카드가 화면에 뜨고, 지울 방법이 없다.
     *
     * <p>🔴 <b>새로 만든 카드면 {@link JumpCardCreated}도 같이 낸다</b>(POK-475, 썸네일 순회기가 2초 안에 사진을 주문한다). 새 카드를
     * 만드는 길을 하나 더 내면 둘 다 부른다: 이것만 부르면 그 카드 사진이 1분 순회를 기다린다.
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
