package com.pokeclip.clip.jumpcard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JumpCardRepository extends JpaRepository<JumpCard, Long> {

    /** 자연키 조회. {@code uq_jump_cards_window}와 같은 세 칸이다. */
    Optional<JumpCard> findByStreamIdAndSourceAndWindowStartMs(String streamId, JumpCardSource source, long windowStartMs);

    /** 연결 직후 스냅샷. 숨긴 카드도 포함한다 — 숨김은 표시 여부이지 삭제가 아니다. */
    List<JumpCard> findAllByStreamIdOrderByEventSeqAsc(String streamId);
}
