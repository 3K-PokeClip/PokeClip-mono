package com.pokeclip.clip.library.api;

import com.pokeclip.clip.library.LibraryEntry;
import com.pokeclip.clip.library.LibraryPage;

import java.util.List;

/** 보관함 목록 봉투. {@code nextCursor}는 불투명하다 — 웹은 받은 것을 그대로 돌려준다({@code JumpCardListResponse}와 같은 규칙). */
public record LibraryListResponse(List<LibraryEntry> items, String nextCursor) {

    static LibraryListResponse from(LibraryPage page) {
        return new LibraryListResponse(page.items(), page.nextCursor());
    }
}
