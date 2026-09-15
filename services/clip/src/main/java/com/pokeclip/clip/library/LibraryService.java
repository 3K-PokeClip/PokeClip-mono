package com.pokeclip.clip.library;

import com.pokeclip.clip.delegation.AccessErrors;
import com.pokeclip.clip.delegation.AccessibleResult;
import com.pokeclip.clip.delegation.BroadcastAccessGuard;
import com.pokeclip.clip.delegation.DelegationResolveClient;
import com.pokeclip.clip.library.LibraryEntry.BroadcastSummary;
import com.pokeclip.clip.paging.CursorCodec;
import com.pokeclip.clip.paging.ListLimit;
import com.pokeclip.clip.recipe.Recipe;
import com.pokeclip.clip.recipe.RecipeDocument;
import com.pokeclip.clip.recipe.RecipeErrors.RecipeNotFoundException;
import com.pokeclip.clip.recipe.RecipeRepository;
import com.pokeclip.clip.render.Clip;
import com.pokeclip.clip.render.ClipRepository;
import com.pokeclip.clip.render.ClipSnapshot;
import com.pokeclip.clip.render.RenderJob;
import com.pokeclip.clip.render.RenderJobRepository;
import com.pokeclip.clip.render.RenderRequestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 보관함(POK-243) — 내가 볼 수 있는 방송들의 편집본을 상태별로 거른 목록과, 편집본 하나의 상세.
 *
 * <p><b>자격 판정이 목록과 상세에서 다르다.</b> 목록은 방송 목록과 같은 판정이다 — auth에 「볼 수 있는 스트리머」를 먼저 받아
 * 그 번호로만 조회하므로 남의 편집본은 <b>안 나온다</b>(200). 상세는 편집본의 방송으로 {@link BroadcastAccessGuard}에 묻고,
 * 거절이면 <b>{@code recipe_not_found}로 접는다</b> — {@code broadcast_not_found}로 나가면 「그 번호의 편집본이 있다」가 새고,
 * 카드 문이 같은 이유로 {@code jump_card_not_found}로 접는다({@code JumpCardService.requireViewableCard}).
 *
 * <p>auth 왕복은 트랜잭션 밖이고 표 조립만 읽기 트랜잭션이다({@code RecipeService}와 같은 모양). 조립을 한 트랜잭션에 두는
 * 이유 — 상태를 판 질의와 영상을 읽는 질의 사이에 일꾼의 보고가 들어오면 {@code status}와 {@code latestClip.status}가 갈린다.
 */
@Service
public class LibraryService {

    private static final Logger log = LoggerFactory.getLogger(LibraryService.class);

    /** 방송 목록과 같은 값(README가 정본). 한 줄이 영상 봉투까지 안고 있어 카드(50)보다 작다. */
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final LibraryQuery query;
    private final RecipeRepository recipes;
    private final ClipRepository clips;
    private final RenderJobRepository jobs;
    private final RenderRequestService render;
    private final DelegationResolveClient delegation;
    private final BroadcastAccessGuard guard;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    LibraryService(LibraryQuery query, RecipeRepository recipes, ClipRepository clips, RenderJobRepository jobs,
                   RenderRequestService render, DelegationResolveClient delegation, BroadcastAccessGuard guard,
                   TransactionTemplate transactions, ObjectMapper mapper) {
        this.query = query;
        this.recipes = recipes;
        this.clips = clips;
        this.jobs = jobs;
        this.render = render;
        this.delegation = delegation;
        this.guard = guard;
        this.transactions = transactions;
        this.mapper = mapper;
    }

    /**
     * @param status {@code null}이거나 빈 문자열이면 전부(빈 문자열을 「안 줬다」로 접는 것은 {@code cursor}와 같은 규칙)
     * @param limit {@code null}이면 {@link #DEFAULT_LIMIT}
     * @param cursor {@code null}이거나 빈 문자열이면 첫 장
     * @throws com.pokeclip.clip.paging.InvalidListParamException 상태가 모르는 값이거나 개수가 0 이하다 (400)
     * @throws com.pokeclip.clip.paging.InvalidCursorException 이어받기 표시가 우리 모양이 아니다 (400)
     * @throws AccessErrors.NotViewableException 토큰의 주체를 회원 번호로 못 읽는다 (404)
     * @throws AccessErrors.AuthUnavailableException 볼 수 있는 스트리머를 물어보지 못했다 (503)
     */
    public LibraryPage list(String requesterSubject, String status, Integer limit, String cursor) {
        // 형식 검사가 auth 왕복보다 먼저다 — 틀린 요청에 7초짜리 왕복을 태우지 않는다(방송 목록과 같은 순서).
        LibraryStatus wanted = status == null || status.isBlank() ? null : LibraryStatus.fromParam(status);
        int size = ListLimit.resolve(limit, DEFAULT_LIMIT, MAX_LIMIT);
        List<Long> after = CursorCodec.decodeOrFirstPage(CursorCodec.Kind.LIBRARY, cursor);
        Long afterId = after == null ? null : after.get(0);
        long userId = 요청자_번호(requesterSubject);

        AccessibleResult accessible = delegation.accessible(userId);
        if (!accessible.available()) {
            // 빈 목록으로 접지 않는다 — 화면이 「편집본이 없다」고 단정하면 auth가 살아난 뒤에도 다시 안 온다.
            throw new AccessErrors.AuthUnavailableException();
        }
        List<String> streamerIds = accessible.streamers().stream()
                .map(entry -> String.valueOf(entry.streamerUserId()))
                .toList();
        if (streamerIds.isEmpty()) {
            return LibraryPage.empty();
        }

        return transactions.execute(tx -> {
            // 상한 하나를 더 받아 「다음 장이 있나」를 본다(방송 목록과 같은 수법).
            List<LibraryRow> rows = query.findPage(streamerIds, wanted, afterId, size + 1);
            boolean hasMore = rows.size() > size;
            List<LibraryRow> page = hasMore ? rows.subList(0, size) : rows;
            String next = hasMore
                    ? CursorCodec.encode(CursorCodec.Kind.LIBRARY, page.get(page.size() - 1).recipeId())
                    : null;
            return new LibraryPage(assemble(page), next);
        });
    }

    /**
     * @throws RecipeNotFoundException 그 번호가 없거나 <b>볼 자격이 없다</b> — 둘이 같은 본문·같은 바닥 시간이다 (404)
     * @throws AccessErrors.AuthUnavailableException 자격을 물어보지 못했다 (503)
     */
    public LibraryDetail get(String requesterSubject, long recipeId) {
        LibraryRow row = query.findOne(recipeId).orElseThrow(() -> new RecipeNotFoundException(recipeId));
        try {
            guard.requireViewable(requesterSubject, row.streamId());
        } catch (AccessErrors.NotViewableException e) {
            // 거절을 「없는 편집본」으로 접는다 — 이유는 클래스 주석. 판정 불가(503)는 그대로 올린다.
            log.info("clip.library.not_viewable recipeId={} reason={}", recipeId, e.reason());
            throw new RecipeNotFoundException(recipeId);
        }
        return transactions.execute(tx -> {
            // 자격 판정 사이에 지워질 표가 없다(편집본은 영구 보존) — 그래도 다시 읽어 상태를 판정 뒤 시점으로 맞춘다.
            LibraryRow fresh = query.findOne(recipeId).orElseThrow(() -> new RecipeNotFoundException(recipeId));
            LibraryEntry entry = assemble(List.of(fresh)).get(0);
            Recipe recipe = recipes.findById(recipeId).orElseThrow(() -> new RecipeNotFoundException(recipeId));
            return LibraryDetail.of(entry, RecipeDocument.fromStored(mapper, recipe));
        });
    }

    /** 줄들을 응답 모양으로. 편집본·영상·주문을 <b>각각 한 번씩</b> 읽는다 — 줄마다 묻지 않는다. 순서는 줄 순서 그대로. */
    private List<LibraryEntry> assemble(List<LibraryRow> rows) {
        Map<Long, Recipe> recipeById = recipes.findAllById(rows.stream().map(LibraryRow::recipeId).toList()).stream()
                .collect(Collectors.toMap(Recipe::getId, Function.identity()));
        List<Long> clipIds = rows.stream().map(LibraryRow::clipId).filter(id -> id != null).toList();
        Map<Long, Clip> clipById = clips.findAllById(clipIds).stream()
                .collect(Collectors.toMap(Clip::getId, Function.identity()));
        Map<Long, RenderJob> jobByClipId = clipIds.isEmpty() ? Map.of() : jobs.findByClipIdIn(clipIds).stream()
                .collect(Collectors.toMap(RenderJob::getClipId, Function.identity()));

        List<LibraryEntry> entries = new ArrayList<>(rows.size());
        for (LibraryRow row : rows) {
            // 질의가 준 번호는 같은 트랜잭션 안에서 읽었으니 반드시 있다 — 없으면 우리 버그라 500이 맞다.
            Recipe recipe = recipeById.get(row.recipeId());
            ClipSnapshot latest = row.clipId() == null ? null
                    : render.snapshot(clipById.get(row.clipId()), Optional.ofNullable(jobByClipId.get(row.clipId())));
            RecipeDocument.Cut cut = recipe.getCutInAtMs() == null ? null
                    : new RecipeDocument.Cut(recipe.getCutInAtMs(), recipe.getCutOutAtMs());
            entries.add(new LibraryEntry(recipe.getId(), recipe.getStreamId(), recipe.getCreatorId(),
                    recipe.getRecipeVersion(), cut, row.status(),
                    new BroadcastSummary(row.broadcastStatus(), row.startedAt(), row.endedAt(), row.vodExpiresAt()),
                    latest, recipe.getCreatedAt(), recipe.getUpdatedAt()));
        }
        return entries;
    }

    /**
     * {@code BroadcastListService.요청자_번호}와 같은 판정, 다른 로그 이름({@code clip.library.*}) — 자리가 갈려야 어느 문이
     * 아픈지가 보인다. 값 자체는 안 찍는다(개행이 섞이면 로그 한 줄이 여러 줄로 쪼개진다).
     */
    private static long 요청자_번호(String requesterSubject) {
        try {
            return Long.parseLong(requesterSubject);
        } catch (NumberFormatException e) {
            log.error("clip.library.identity_not_numeric reason=subject_not_numeric");
            throw new AccessErrors.NotViewableException("subject_not_numeric");
        }
    }
}
