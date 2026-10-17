package com.pokeclip.clip.thumbnail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 목록 응답에 실을 사진 주소를 만든다(POK-277). 한 장의 대상 전부를 한 번에 읽는다(줄마다 물으면 한 장에 최대 100번 왕복이다).
 *
 * <p>사진은 <b>부가 칸</b>이다. 꺼져 있거나 표를 못 읽으면 빈 맵을 주고 목록은 그대로 나간다: 여기서 던지면 홈·라이브·보관함이
 * 전부 500이 된다({@code BroadcastListService.originsOrEmpty}와 같은 규칙). 화면은 주소가 없으면 자리표시를 그린다.
 */
@Component
public class ThumbnailUrls {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailUrls.class);

    /**
     * 지난 방송마다 점수가 가장 높은 카드 중 사진이 있는 것. 점수가 없는 카드는 뒤로, 같으면 먼저 생긴 카드. 숨긴 카드는 대표로 안 쓴다.
     */
    static final String TOP_CARD_KEYS = """
            SELECT DISTINCT ON (c.stream_id) c.stream_id, t.s3_key
              FROM jump_cards c
              JOIN thumbnails t ON t.kind = 'card' AND t.target_id = c.id::text AND t.s3_key IS NOT NULL
             WHERE c.stream_id = ANY(?) AND c.hidden_at IS NULL
             ORDER BY c.stream_id, c.score DESC NULLS LAST, c.id""";

    private final ObjectProvider<ThumbnailSigner> signer;
    private final ThumbnailRepository thumbnails;
    private final JdbcTemplate jdbc;

    ThumbnailUrls(ObjectProvider<ThumbnailSigner> signer, ThumbnailRepository thumbnails, JdbcTemplate jdbc) {
        this.signer = signer;
        this.thumbnails = thumbnails;
        this.jdbc = jdbc;
    }

    /** 카드 번호 → 사진 주소. */
    public Map<Long, String> ofCards(Collection<Long> cardIds) {
        return signedByLong(ThumbnailKind.CARD, cardIds);
    }

    /** 완성 영상 번호 → 사진 주소. */
    public Map<Long, String> ofClips(Collection<Long> clipIds) {
        return signedByLong(ThumbnailKind.CLIP, clipIds);
    }

    /**
     * 방송 번호 → 사진 주소. 방송 중이면 최신 화면, 끝난 방송이면 최고 점수 카드 사진이고 없으면 마지막 라이브 사진이다.
     *
     * @param live 방송 중인 방송 번호. 나머지는 끝난 방송으로 본다
     */
    public Map<String, String> ofBroadcasts(Collection<String> live, Collection<String> ended) {
        ThumbnailSigner s = signer.getIfAvailable();
        if (s == null || (live.isEmpty() && ended.isEmpty())) {
            return Map.of();
        }
        return orEmpty("broadcast", () -> {
            Map<String, String> keys = new HashMap<>();
            List<String> all = new ArrayList<>(live);
            all.addAll(ended);
            keys.putAll(thumbnails.keysOf(ThumbnailKind.LIVE, all));
            if (!ended.isEmpty()) {
                keys.putAll(topCardKeys(ended));
            }
            Map<String, String> urls = new HashMap<>();
            keys.forEach((id, key) -> urls.put(id, s.sign(key)));
            return urls;
        });
    }

    private Map<String, String> topCardKeys(Collection<String> streamIds) {
        Map<String, String> keys = new HashMap<>();
        jdbc.query(con -> {
            var ps = con.prepareStatement(TOP_CARD_KEYS);
            ps.setArray(1, con.createArrayOf("text", streamIds.toArray(String[]::new)));
            return ps;
        }, rs -> {
            keys.put(rs.getString("stream_id"), rs.getString("s3_key"));
        });
        return keys;
    }

    private Map<Long, String> signedByLong(ThumbnailKind kind, Collection<Long> ids) {
        ThumbnailSigner s = signer.getIfAvailable();
        if (s == null || ids.isEmpty()) {
            return Map.of();
        }
        return orEmpty(kind.value(), () -> {
            Map<Long, String> urls = new HashMap<>();
            thumbnails.keysOf(kind, ids.stream().map(String::valueOf).toList())
                    .forEach((id, key) -> urls.put(Long.parseLong(id), s.sign(key)));
            return urls;
        });
    }

    private static <K> Map<K, String> orEmpty(String kind, Supplier<Map<K, String>> read) {
        try {
            return read.get();
        } catch (DataAccessException e) {
            log.warn("thumbnail.urls_failed kind={} reason={}", kind, e.getClass().getSimpleName());
            return Map.of();
        }
    }
}
