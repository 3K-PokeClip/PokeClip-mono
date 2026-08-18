package com.pokeclip.clip.broadcast;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BroadcastEventRepository extends JpaRepository<BroadcastEvent, Long> {

    boolean existsByEventId(String eventId);
}
