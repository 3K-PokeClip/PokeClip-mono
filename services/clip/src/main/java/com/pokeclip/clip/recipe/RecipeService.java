package com.pokeclip.clip.recipe;

import com.pokeclip.clip.delegation.BroadcastAccessGuard;
import com.pokeclip.clip.recipe.RecipeDocument.Audio;
import com.pokeclip.clip.recipe.RecipeDocument.Cut;
import com.pokeclip.clip.recipe.RecipeDocument.Output;
import com.pokeclip.clip.recipe.RecipeDocument.Subtitles;
import com.pokeclip.clip.recipe.RecipeErrors.RecipeNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 레시피를 읽고 쓰는 유일한 자리. <b>자격 판정이 여기 있다</b>(컨트롤러가 아니라) — {@code JumpCardService}와
 * 같은 규칙으로, 이 서비스를 직접 부르는 소비자가 생겨도 그 경로가 무방비가 되지 않게 한다.
 *
 * <p>🔴 <b>자격 판정이 맨 앞이고 트랜잭션은 그 뒤에 연다.</b> auth 왕복이 최대 7초인데 트랜잭션 안에서 돌면
 * 그동안 커넥션을 쥔다. 자기 호출은 프록시를 안 타므로 {@code @Transactional}로는 그 경계를 못 만들고
 * {@link TransactionTemplate}을 쓴다({@code JumpCardService.claim}과 같은 모양).
 *
 * <p>본문 검증({@link RecipeValidator})도 자격 판정 <b>뒤</b>다 — 앞에 두면 없는 방송·남의 방송에 대고
 * 본문을 고쳐 가며 400과 404를 갈라 볼 수 있다. 404 두 갈래가 같은 본문·같은 바닥 시간인 것을 지키려면
 * 400이 그 앞에서 새면 안 된다.
 */
@Service
public class RecipeService {

    private static final Logger log = LoggerFactory.getLogger(RecipeService.class);

    private final RecipeRepository recipes;
    private final RecipeValidator validator;
    private final BroadcastAccessGuard guard;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    RecipeService(RecipeRepository recipes, RecipeValidator validator, BroadcastAccessGuard guard,
                  TransactionTemplate transactions, ObjectMapper mapper) {
        this.recipes = recipes;
        this.validator = validator;
        this.guard = guard;
        this.transactions = transactions;
        this.mapper = mapper;
    }

    /**
     * @throws com.pokeclip.clip.delegation.AccessErrors.NotViewableException 방송이 없거나 볼 자격이 없다 (404, 같은 본문)
     * @throws com.pokeclip.clip.delegation.AccessErrors.AuthUnavailableException 자격을 물어보지 못했다 (503)
     * @throws RecipeErrors.InvalidRecipeException 계약6 규칙에 어긋난다 (400)
     */
    public RecipeSnapshot create(String requesterSubject, String streamId, RecipeDocument document) {
        guard.requireViewable(requesterSubject, streamId);
        validator.validate(document, streamId);
        Recipe saved = transactions.execute(status -> recipes.save(Recipe.create(
                streamId, requesterSubject, document.schemaVersion(),
                inAt(document.cut()), outAt(document.cut()),
                json(document.outputs()), json(document.audio()), json(document.subtitles()))));
        log.info("clip.recipe.created streamId={} recipeId={} creator={}", streamId, saved.getId(), requesterSubject);
        return snapshot(saved);
    }

    public List<RecipeSnapshot> listOf(String requesterSubject, String streamId) {
        guard.requireViewable(requesterSubject, streamId);
        return recipes.findAllByStreamIdOrderByIdAsc(streamId).stream().map(this::snapshot).toList();
    }

    /** @throws RecipeNotFoundException 그 방송에 그 번호가 없다 — 다른 방송의 번호여도 같다 (404) */
    public RecipeSnapshot get(String requesterSubject, String streamId, long id) {
        guard.requireViewable(requesterSubject, streamId);
        return snapshot(recipes.findByIdAndStreamId(id, streamId).orElseThrow(() -> new RecipeNotFoundException(id)));
    }

    /**
     * 통째로 갈아 끼우고 판을 +1. 락을 잡고 고치므로 같은 레시피를 동시에 고치면 <b>줄을 서고 판이 둘 다 오른다</b>
     * — 뒤에 커밋한 쪽이 이긴다(내용), 판 번호는 안 겹친다({@link RecipeRepository#findByIdAndStreamIdForUpdate}).
     */
    public RecipeSnapshot replace(String requesterSubject, String streamId, long id, RecipeDocument document) {
        guard.requireViewable(requesterSubject, streamId);
        validator.validate(document, streamId);
        Recipe updated = transactions.execute(status -> {
            Recipe recipe = recipes.findByIdAndStreamIdForUpdate(id, streamId)
                    .orElseThrow(() -> new RecipeNotFoundException(id));
            recipe.replace(document.schemaVersion(), inAt(document.cut()), outAt(document.cut()),
                    json(document.outputs()), json(document.audio()), json(document.subtitles()));
            return recipe;
        });
        log.info("clip.recipe.replaced streamId={} recipeId={} recipeVersion={} by={}",
                streamId, id, updated.getRecipeVersion(), requesterSubject);
        return snapshot(updated);
    }

    private static Long inAt(Cut cut) {
        return cut == null ? null : cut.inAtMs();
    }

    private static Long outAt(Cut cut) {
        return cut == null ? null : cut.outAtMs();
    }

    private String json(Object value) {
        return value == null ? null : mapper.writeValueAsString(value);
    }

    /** 표의 칸을 계약6 모양으로 되돌린다 — 편집기는 보낸 것과 같은 모양을 받는다. */
    private RecipeSnapshot snapshot(Recipe recipe) {
        Cut cut = recipe.getCutInAtMs() == null ? null : new Cut(recipe.getCutInAtMs(), recipe.getCutOutAtMs());
        List<Output> outputs = mapper.readValue(recipe.getOutputs(),
                mapper.getTypeFactory().constructCollectionType(List.class, Output.class));
        Audio audio = mapper.readValue(recipe.getAudio(), Audio.class);
        Subtitles subtitles = recipe.getSubtitles() == null ? null : mapper.readValue(recipe.getSubtitles(), Subtitles.class);
        RecipeDocument document = new RecipeDocument(recipe.getSchemaVersion(), recipe.getStreamId(),
                cut, outputs, audio, subtitles);
        return new RecipeSnapshot(recipe.getId(), recipe.getStreamId(), recipe.getCreatorId(),
                recipe.getRecipeVersion(), document, recipe.getCreatedAt(), recipe.getUpdatedAt());
    }
}
