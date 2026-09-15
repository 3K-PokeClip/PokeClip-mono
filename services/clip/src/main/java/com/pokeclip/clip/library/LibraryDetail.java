package com.pokeclip.clip.library;

import com.pokeclip.clip.library.LibraryEntry.BroadcastSummary;
import com.pokeclip.clip.recipe.RecipeDocument;
import com.pokeclip.clip.recipe.RecipeDocument.Cut;
import com.pokeclip.clip.render.ClipSnapshot;

import java.time.Instant;

/**
 * 보관함 상세 — {@link LibraryEntry}의 칸 전부 + 편집본 본문({@code recipe}, 계약6 JSON 그대로).
 * 칸을 겹쳐 적는 것은 화면이 목록 줄과 상세를 같은 모양으로 읽게 하려는 것이다({@code LibraryShapeTest}가 둘을 맞댄다).
 */
public record LibraryDetail(long recipeId,
                            String streamId,
                            String creatorId,
                            int recipeVersion,
                            Cut cut,
                            String status,
                            BroadcastSummary broadcast,
                            ClipSnapshot latestClip,
                            Instant createdAt,
                            Instant updatedAt,
                            RecipeDocument recipe) {

    static LibraryDetail of(LibraryEntry entry, RecipeDocument recipe) {
        return new LibraryDetail(entry.recipeId(), entry.streamId(), entry.creatorId(), entry.recipeVersion(), entry.cut(),
                entry.status(), entry.broadcast(), entry.latestClip(), entry.createdAt(), entry.updatedAt(), recipe);
    }
}
