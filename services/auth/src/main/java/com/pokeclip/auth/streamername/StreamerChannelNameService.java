package com.pokeclip.auth.streamername;

import com.pokeclip.auth.chzzk.ChzzkChannelLinkRepository;
import com.pokeclip.auth.soop.SoopChannelLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 스트리머 번호 여럿의 플랫폼 채널 이름(POK-430). 살아 있는 연동만 본다: 해제 · 갱신 거절 · 탈퇴로 닫힌 행은
 * 이름이 남아 있어도 안 준다.
 */
@Service
@RequiredArgsConstructor
public class StreamerChannelNameService {

    private final ChzzkChannelLinkRepository chzzkLinks;
    private final SoopChannelLinkRepository soopLinks;

    /** 요청한 번호마다 한 항목. 표당 한 번만 묻는다(번호마다 묻지 않는다). */
    @Transactional(readOnly = true)
    public Map<Long, StreamerChannelNames> namesOf(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = new LinkedHashSet<>(userIds);
        // toMap은 같은 키가 두 번 오면 던진다. 회원당 살아 있는 행은 부분 유일 색인이 하나로 막으므로(V107 · V116)
        // 던진다면 조회 조건이 깨진 것이다: 아무 이름이나 고르지 않고 드러나게 둔다.
        Map<Long, String> chzzk = chzzkLinks.findAliveNames(ids).stream()
                .collect(Collectors.toMap(ChzzkChannelLinkRepository.AliveName::getUserId,
                        ChzzkChannelLinkRepository.AliveName::getName));
        Map<Long, String> soop = soopLinks.findAliveNames(ids).stream()
                .collect(Collectors.toMap(SoopChannelLinkRepository.AliveName::getUserId,
                        SoopChannelLinkRepository.AliveName::getName));
        Map<Long, StreamerChannelNames> out = new LinkedHashMap<>();
        for (Long id : ids) {
            out.put(id, new StreamerChannelNames(chzzk.get(id), soop.get(id)));
        }
        return out;
    }
}
