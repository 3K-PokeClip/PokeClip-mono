package com.pokeclip.clip.recipe.api;

import com.pokeclip.clip.recipe.RecipeSnapshot;

import java.util.List;

/** 목록 봉투. {@code JumpCardListResponse}처럼 배열을 그대로 내지 않고 이름을 붙인다 — 나중에 칸을 더할 자리. */
public record RecipeListResponse(List<RecipeSnapshot> recipes) {
}
