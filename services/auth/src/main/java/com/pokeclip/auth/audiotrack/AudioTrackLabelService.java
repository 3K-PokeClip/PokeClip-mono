package com.pokeclip.auth.audiotrack;

import com.pokeclip.auth.delegation.DelegationRelation;
import com.pokeclip.auth.delegation.DelegationService;
import com.pokeclip.auth.user.ActiveUserGuard;
import com.pokeclip.auth.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 오디오 트랙 이름(POK-240). 스트리머가 자기 것을 적고, 스트리머 본인과 위임 편집자가 읽는다.
 *
 * <p><b>왜 auth인가</b> — 채널 연동·편집자처럼 「스트리머 본인의 설정」이고, 읽을 자격(위임)이 이 서버의
 * 표에 있다. clip은 방송 단위라 여기 두면 방송마다 다시 적어야 한다.
 *
 * <p><b>이름 규칙은 표시 이름과 같다</b>({@link UserService#stripEdgeBlanks} — 전각 공백·NBSP·ZWSP를 공백으로
 * 본다 · {@link UserService#hasControlCharacter} — 가운데 제어문자 거절) — 길이만 32자다.
 * 두 자리가 다른 판정을 하면 화면이 같은 입력에 다른 답을 받는다.
 */
@Service
public class AudioTrackLabelService {

    private static final Logger log = LoggerFactory.getLogger(AudioTrackLabelService.class);

    /** 코드 포인트 기준. 표시 이름(30)보다 조금 길다 — "게임 사운드 (스피커)" 정도는 들어가야 한다. */
    static final int LABEL_MAX_CODE_POINTS = 32;

    private final AudioTrackLabelRepository labels;
    private final DelegationService delegation;
    private final ActiveUserGuard activeUserGuard;

    AudioTrackLabelService(AudioTrackLabelRepository labels, DelegationService delegation, ActiveUserGuard activeUserGuard) {
        this.labels = labels;
        this.delegation = delegation;
        this.activeUserGuard = activeUserGuard;
    }

    /** 본인 것. 회원 번호는 토큰에서 왔으므로 자격을 다시 묻지 않는다. */
    public List<String> mine(long userId) {
        return labels.find(userId);
    }

    /**
     * 남의 것 — 본인이거나 살아있는 위임의 편집자만. <b>「없는 회원」과 「자격 없음」을 가르지 않는다</b>
     * (clip의 방송 문과 같은 이유 — 갈리면 번호를 넣어 보는 것만으로 회원의 실재를 안다).
     * 없는 회원 번호는 {@code relationOf}가 NONE을 주므로 따로 조회하지 않는다.
     */
    public List<String> of(long requesterId, long streamerUserId) {
        if (delegation.relationOf(requesterId, streamerUserId) == DelegationRelation.NONE) {
            throw new AudioTrackException(AudioTrackFailure.STREAMER_NOT_FOUND, "볼 수 없다");
        }
        return labels.find(streamerUserId);
    }

    /**
     * 여섯 칸을 통째로 바꾼다. 앞뒤 공백을 자르고 비면 {@code null}(이름 없음)이다.
     *
     * <p>🔴 <b>탈퇴한 회원의 쓰기다</b> — 입구 필터가 막지만, 필터를 지난 뒤 탈퇴가 커밋되는 창이 있어
     * 쓰기 직전에 다시 본다({@code ActiveUserGuard}, POK-171의 「회원에게 무언가를 새로 만들어 주는 경로」).
     */
    @Transactional
    public List<String> update(long userId, List<String> raw, Instant now) {
        activeUserGuard.requireAlive(userId, "audio_track_labels");
        List<String> cleaned = normalize(raw);
        labels.replace(userId, cleaned, now);
        log.info("auth.audio_tracks.updated userId={} named={}", userId, cleaned.stream().filter(l -> l != null).count());
        return cleaned;
    }

    static List<String> normalize(List<String> raw) {
        if (raw == null || raw.size() != AudioTrackLabelRepository.TRACK_COUNT) {
            throw new AudioTrackException(AudioTrackFailure.LABELS_SIZE, "칸이 여섯이 아니다");
        }
        List<String> cleaned = new ArrayList<>(AudioTrackLabelRepository.TRACK_COUNT);
        for (String label : raw) {
            String trimmed = label == null ? "" : UserService.stripEdgeBlanks(label);
            if (trimmed.isEmpty()) {
                cleaned.add(null);
                continue;
            }
            if (trimmed.codePointCount(0, trimmed.length()) > LABEL_MAX_CODE_POINTS) {
                throw new AudioTrackException(AudioTrackFailure.LABEL_TOO_LONG, "이름이 너무 길다");
            }
            // 🔴 가운데 NUL은 트림·길이 검사를 다 지나 저장에서 터진다(500). 표시 이름이 겪은 자리다.
            if (UserService.hasControlCharacter(trimmed)) {
                throw new AudioTrackException(AudioTrackFailure.LABEL_INVALID, "이름에 제어문자가 있다");
            }
            cleaned.add(trimmed);
        }
        return cleaned;
    }
}
