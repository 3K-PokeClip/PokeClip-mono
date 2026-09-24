package com.pokeclip.clip.library;

import java.util.List;

/** 보관함 한 장. {@code nextCursor}는 불투명한 이어받기 표시({@code CursorCodec}), 다음 장이 없으면 {@code null}. */
public record LibraryPage(List<LibraryEntry> items, String nextCursor) {

    private static final LibraryPage EMPTY = new LibraryPage(List.of(), null);

    public static LibraryPage empty() {
        return EMPTY;
    }
}
