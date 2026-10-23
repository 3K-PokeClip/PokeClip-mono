package com.pokeclip.clip.broadcast;

import com.pokeclip.clip.collector.BroadcastLatest;
import com.pokeclip.clip.collector.BroadcastTitle;
import com.pokeclip.clip.collector.BroadcastTitleClient;
import com.pokeclip.clip.delegation.AccessErrors;
import com.pokeclip.clip.delegation.AccessibleResult;
import com.pokeclip.clip.delegation.DelegationResolveClient;
import com.pokeclip.clip.delegation.ResolveResult;
import com.pokeclip.clip.paging.CursorCodec;
import com.pokeclip.clip.paging.InvalidListParamException;
import com.pokeclip.clip.paging.ListLimit;
import com.pokeclip.clip.paging.ListParams;
import com.pokeclip.clip.segment.TimelineOriginReader;
import com.pokeclip.clip.thumbnail.ThumbnailUrls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 「내가 볼 수 있는 스트리머의 방송」 한 장을 만든다.
 *
 * <p><b>순서가 계약이다</b> — 요청 칸을 먼저 보고, 볼 수 있는 스트리머를 받고, 그 번호로만
 * 조회한다. 조회를 먼저 하고 거르면 남의 방송이 메모리에 올라오고, 거르는 조건이 한 줄
 * 틀리면 그대로 나간다.
 *
 * <p>요청 칸 검증이 <b>맨 앞</b>인 것도 같은 이유의 다른 면이다 — 뒤로 밀면 형식 오류 하나가
 * auth 왕복(최대 7초)을 태우고, 그러고도 나가는 응답이 400이다.
 *
 * <p><b>개수 규칙이 컨트롤러가 아니라 여기 있다.</b> 이 서비스의 소비자가 웹 문 하나뿐인
 * 지금은 두 자리가 같아 보이지만, 소비자가 늘면 컨트롤러를 안 거치는 쪽에 상한이 없어진다 —
 * {@code SegmentQueryService.MAX_RANGE_MS}가 같은 이유로 서비스에 있다.
 *
 * <p>🔴 <b>{@code @Transactional}을 붙이지 마라.</b> 붙이면 auth 왕복(최대 7초) 동안
 * DB 커넥션을 쥔다 — 사람이 기다리는 요청 하나가 풀에서 자리를 그만큼 뺏는다.
 * <b>{@code readOnly}를 빼도 마찬가지다</b>(실측: 둘 다 왕복 중 {@code active=1}).
 * 조회가 하나뿐이라 트랜잭션으로 얻는 것도 없다.
 *
 * <p><b>순서를 바꾸는 것으로는 피할 수 없다.</b> 이 메서드는 auth를 <b>먼저</b> 부르고
 * 조회를 나중에 하는데도 쥔다 — 즉 커넥션은 첫 질의가 아니라 <b>트랜잭션이 열릴 때</b>
 * 잡힌다. 🔴 <b>효과만 쟀다. 왜 그렇게 잡히는지(하이버네이트 획득 모드가 무엇을 하는지)는
 * 안 쟀다.</b>
 *
 * <p>🔴 <b>이 문단은 한때 정반대를 말했다.</b> 「지금 순서는 <b>붙여도 안 쥐지만</b> 조회를
 * 앞으로 옮기면 위험하다」였다. 구현자와 감사자가 <b>같은 논증</b>(지연 획득)으로 그렇게 믿었고
 * <b>둘 다 틀렸다</b> — 갈라 준 것은 합의가 아니라 측정이다. 그 문장은 틀린 사실이면서 동시에
 * <b>허가</b>였다: 「이 순서에선 무해하다」는 읽는 사람에게 붙여도 된다는 신호였다.
 * 그래서 사실만이 아니라 <b>방향까지</b> 뒤집어 다시 적었다.
 *
 * <p>진짜 트랜잭션이 필요해지는 날의 길은 {@code readOnly}를 빼는 것이 아니라
 * <b>판정을 트랜잭션 밖에 두는 것</b>이다(계획 검증 M5의 처방) — 트랜잭션 없는 공개
 * 메서드가 auth를 먼저 부르고, 그 뒤 트랜잭션인 <b>다른 빈</b>의 메서드를 부른다.
 *
 * <p>{@code BroadcastListTransactionTest}가 이 불변식을 지킨다. 그 시험은 애너테이션이
 * 아니라 <b>「auth 왕복 동안 커넥션을 쥐나」</b>를 재므로, 애너테이션·순서·획득 모드 중
 * 무엇이 바뀌어도 빨간불이 된다.
 */
@Service
public class BroadcastListService {

    private static final Logger log = LoggerFactory.getLogger(BroadcastListService.class);

    /**
     * 웹이 개수를 안 주면 이만큼. 카드(50)보다 작은 것은 한 줄이 훨씬 굵어서다.
     *
     * <p><b>값만 여기 있고 판정은 {@link ListLimit}에 있다</b> — 카드 목록 문이 같은 계약을
     * 웹에 약속하므로, 판정을 복사해 두면 한쪽을 고칠 때 다른 쪽이 조용히 갈린다(POK-174).
     *
     * <p><b>{@code private}인 것은 밖에서 읽는 곳이 없기 때문이다</b>(시험 포함, 2026-08-26 전수).
     * <b>값의 정본은 커밋되는 {@code services/README.md}</b>이고 웹은 거기서 읽는다 —
     * 코드에서 이 값을 참조해야 할 소비자가 생기는 날 넓힌다.
     */
    private static final int DEFAULT_LIMIT = 20;

    /** 넘겨 달라고 해도 여기서 깎는다(PRD 결정). */
    private static final int MAX_LIMIT = 100;

    private final BroadcastRepository broadcasts;
    private final DelegationResolveClient delegation;
    private final TimelineOriginReader origins;
    private final ThumbnailUrls thumbnails;
    private final BroadcastTitleClient titles;
    private final ViewerPresence viewers;
    private final BroadcastPlatformStore bundles;

    BroadcastListService(BroadcastRepository broadcasts, DelegationResolveClient delegation,
                         TimelineOriginReader origins, ThumbnailUrls thumbnails, BroadcastTitleClient titles,
                         ViewerPresence viewers, BroadcastPlatformStore bundles) {
        this.broadcasts = broadcasts;
        this.delegation = delegation;
        this.origins = origins;
        this.thumbnails = thumbnails;
        this.titles = titles;
        this.viewers = viewers;
        this.bundles = bundles;
    }

    /** 플랫폼 순서(POK-397): 치지직 → SOOP → 그 밖 이름순. 화면이 같은 방송을 열 때마다 같은 순서로 그리게 한다. */
    private static final Comparator<String> PLATFORM_ORDER = Comparator
            .comparingInt((String platform) -> switch (platform) {
                case "CHZZK" -> 0;
                case "SOOP" -> 1;
                default -> 2;
            })
            .thenComparing(Comparator.naturalOrder());

    /** 제목 후보의 「켜짐」 폭(POK-397). 웹이 플랫폼을 꺼진 것으로 보는 「관측 3분」과 같다. 수집기 관측 주기(1분)의 세 배다. */
    private static final Duration ON_AIR_WINDOW = Duration.ofMinutes(3);

    /**
     * @param requesterSubject JWT {@code sub} — 우리가 발급·검증한 토큰의 회원 번호(문자열)
     * @param limit {@code null}이면 {@link #DEFAULT_LIMIT}
     * @param cursor {@code null}<b>이거나 빈 문자열이면</b> 첫 장 ({@code CursorCodec.decodeOrFirstPage})
     * @param streamerId 화면이 고른 채널(POK-390). {@code null}·빈 값이면 볼 수 있는 스트리머 전부를 섞는다
     * @throws InvalidListParamException 개수가 범위 밖이거나 {@code streamerId}가 숫자가 아니다 (400)
     * @throws com.pokeclip.clip.paging.InvalidCursorException 이어받기 표시가 우리 모양이 아니다 (400)
     * @throws AccessErrors.NotViewableException 토큰의 주체를 회원 번호로 못 읽거나, 고른 채널을 볼 수 없다 (404)
     * @throws AccessErrors.AuthUnavailableException 볼 수 있는 스트리머를 물어보지 못했다 (503)
     */
    public BroadcastPage list(String requesterSubject, BroadcastState state, Integer limit, String cursor,
                              String streamerId) {
        int size = ListLimit.resolve(limit, DEFAULT_LIMIT, MAX_LIMIT);
        List<Long> after = CursorCodec.decodeOrFirstPage(CursorCodec.Kind.BROADCAST, cursor);
        Long afterId = after == null ? null : after.get(0);
        Long chosen = ListParams.streamerIdOrNull(streamerId);
        long userId = 요청자_번호(requesterSubject);

        AccessibleResult accessible = delegation.accessible(userId);
        if (!accessible.available()) {
            // 빈 목록으로 접지 않는다 — 화면이 「방송이 없다」고 단정하면 auth가 살아난 뒤에도
            // 편집자는 다시 시도하지 않는다(PRD 결정).
            throw new AccessErrors.AuthUnavailableException();
        }
        accessible = accessible.narrowTo(chosen);
        if (chosen != null && accessible.streamers().isEmpty()) {
            // 200 빈 목록으로 접지 않는다: 위임이 끊긴 편집자 화면이 「방송이 없다」로 남아 내 채널로 못 돌아간다.
            // 「없는 번호」와 「권한 없음」을 안 가른다(같은 예외, 사유는 로그로만).
            throw new AccessErrors.NotViewableException("streamer_not_accessible");
        }

        // 🔴 숫자를 문자열로 되돌린다. 그 변환이 관대하지 않다는 것(선행 0)은 findPage 주석에 있다.
        List<String> streamerIds = accessible.streamers().stream()
                .map(entry -> String.valueOf(entry.streamerUserId()))
                .toList();
        if (streamerIds.isEmpty()) {
            // 빈 목록도 예외 없이 0행이라 이 갈래가 없어도 죽지는 않는다(계획 검증 실측).
            // 그래도 두는 것은 ① 질의 한 번을 아끼고 ② 「빈 목록이 온다」는 가정을 코드에
            // 드러내려는 것이다 — 지금 그 사실은 auth 쪽 코드에만 적혀 있다.
            //
            // 🔴 이 방어가 막는 것은 「죽지 않는 것」까지다. auth가 본인을 안 넣게 되면
            //    위임 없는 스트리머의 목록이 0행을 200으로 내보낸다 — 자기 방송이 자기 홈에서
            //    사라지는데 오류가 아니다. 그것은 계약이라 clip이 못 막는다(README에 적었다).
            return BroadcastPage.empty();
        }

        Map<String, ResolveResult> relations = 관계를_번호로_모은다(accessible);

        // 상한 하나를 더 받아 「다음 장이 있나」를 본다. 개수를 따로 세면 질의가 하나 더 돈다.
        List<Broadcast> rows = broadcasts.findPage(streamerIds, state.dbValues(), afterId, size + 1);
        boolean hasMore = rows.size() > size;
        List<Broadcast> page = hasMore ? rows.subList(0, size) : rows;
        String next = hasMore
                ? CursorCodec.encode(CursorCodec.Kind.BROADCAST, page.get(page.size() - 1).getId())
                : null;
        // 시각 기준점은 잘라 낸 한 장만 잰다(POK-255) — 「다음 장이 있나」를 보려고 더 받은 한 줄은 안 나간다
        List<String> streamIds = page.stream().map(Broadcast::getStreamId).toList();
        BroadcastLatest latest = titles.latestOf(streamIds);
        Map<String, List<String>> bundled = bundlesOrEmpty(streamIds);
        Map<String, BroadcastTitle> chosenTitles = new HashMap<>();
        Map<String, List<PlatformViewers>> platforms = new HashMap<>();
        Map<String, String> leads = new HashMap<>();
        for (Broadcast row : page) {
            List<BroadcastLatest.PlatformLatest> observed =
                    latest.platforms().getOrDefault(row.getStreamId(), List.of());
            List<PlatformViewers> listed = platformsOf(row, bundled.getOrDefault(row.getStreamId(), List.of()), observed);
            platforms.put(row.getStreamId(), listed);
            List<BroadcastLatest.PlatformLatest> onAir = onAirByViewers(listed, observed);
            if (!onAir.isEmpty()) {
                leads.put(row.getStreamId(), onAir.get(0).platform());
            }
            BroadcastTitle title = latest.platformAware()
                    ? titleOf(onAir)
                    : latest.titles().get(row.getStreamId());
            if (title != null) {
                chosenTitles.put(row.getStreamId(), title);
            }
        }
        return new BroadcastPage(page, relations, next, originsOrEmpty(page), thumbnailsOf(page),
                chosenTitles, platforms, leads, editorsOnlineOf(page, userId));
    }

    /**
     * 켜진 플랫폼 = 묶음 표 + 편지 플랫폼(POK-397). 편지 플랫폼은 ① 묶음이 비었거나 ② 묶음에 있거나 ③ 수집기가 그 플랫폼 값을 봤을 때만 넣는다.
     *
     * <p>🔴 무조건 넣지 않는 이유: Media는 지금 편지에 {@code platform}을 안 싣는다. 그래서 {@code broadcasts.platform}은 늘 CHZZK이고, 무조건 넣으면
     * SOOP만 하는 방송에도 시청자 수 없는 치지직이 붙는다(로컬 리뷰 1라운드). ③이 계획 검증 M6을 지킨다: 치지직 목록 훑기가 실패한 분에는 수집기가
     * 묶음에 CHZZK를 못 알리지만 방송 정보 줄은 남아 여기 보인다. 옛 칸 {@code platform}은 이 규칙과 무관하게 편지 값이다.
     * 시청자 수는 수집기의 그 플랫폼 최신 값이고 없으면 비운다.
     */
    private static List<PlatformViewers> platformsOf(Broadcast row, List<String> bundled,
                                                     List<BroadcastLatest.PlatformLatest> observed) {
        Set<String> names = new TreeSet<>(PLATFORM_ORDER);
        names.addAll(bundled);
        String letter = row.getPlatform();
        if (bundled.isEmpty() || observed.stream().anyMatch(each -> letter.equals(each.platform()))) {
            names.add(letter);
        }
        List<PlatformViewers> out = new ArrayList<>();
        for (String name : names) {
            BroadcastLatest.PlatformLatest seen = observed.stream()
                    .filter(each -> name.equals(each.platform())).findFirst().orElse(null);
            out.add(new PlatformViewers(name, seen == null ? null : seen.viewers(), seen == null ? null : seen.observedAt()));
        }
        return out;
    }

    /**
     * <b>목록에 실린 플랫폼 중 켜진 것</b>을 시청자가 많은 순, 같으면 위 순서(CHZZK → SOOP)로 줄 세운다(POK-397). 첫째가 그 방송의 대표 플랫폼이고
     * 제목({@link #titleOf})과 옛 칸 {@code platform}(PR3b 전 웹 홈 태그)이 같이 쓴다. 둘이 따로 고르면 SOOP 제목에 치지직 태그가 붙는다(로컬 리뷰 4라운드).
     * 웹이 띠·머리에 고르는 규칙과 같다.
     *
     * <p>켜짐 = 그 방송의 가장 최근 관측에서 {@link #ON_AIR_WINDOW} 안에 관측된 플랫폼. 웹의 「관측 3분 안」과 같은 폭이되 지금 시각이 아니라 마지막 관측에
     * 잰다: 끝난 방송도 마지막에 켜져 있던 플랫폼을 고른다. 관측이 하나도 없으면(옛 수집기·수집기가 아픔) 빈다.
     *
     * <p>🔴 꺼진 플랫폼은 시청자가 많았어도 안 고른다(웹 PR3b 로컬 리뷰). 치지직을 잠깐 켰다 끄고 SOOP으로만 방송하면 묶음에 CHZZK가 남는데, 전에는
     * 편지 플랫폼(Media가 플랫폼을 안 실어 늘 CHZZK)이나 묶음 순서 첫째를 골라 꺼진 치지직 제목·태그가 떴다.
     */
    private static List<BroadcastLatest.PlatformLatest> onAirByViewers(List<PlatformViewers> listed,
                                                                       List<BroadcastLatest.PlatformLatest> observed) {
        Set<String> listedNames = new HashSet<>();
        listed.forEach(each -> listedNames.add(each.platform()));
        List<BroadcastLatest.PlatformLatest> seen = observed.stream()
                .filter(each -> listedNames.contains(each.platform()) && each.observedAt() != null)
                .toList();
        Instant last = seen.stream().map(BroadcastLatest.PlatformLatest::observedAt).max(Comparator.naturalOrder()).orElse(null);
        if (last == null) {
            return List.of();
        }
        Instant onAirFrom = last.minus(ON_AIR_WINDOW);
        List<BroadcastLatest.PlatformLatest> onAir = new ArrayList<>(seen.stream()
                .filter(each -> !each.observedAt().isBefore(onAirFrom))
                .toList());
        onAir.sort(Comparator.comparing(BroadcastLatest.PlatformLatest::viewers,
                        Comparator.nullsLast(Comparator.<Integer>reverseOrder()))
                .thenComparing(BroadcastLatest.PlatformLatest::platform, PLATFORM_ORDER));
        return onAir;
    }

    /**
     * 제목 규칙(POK-397): {@link #onAirByViewers} 순서로 제목 있는 첫 플랫폼이다. 대표 플랫폼에 제목이 없으면 다음 켜진 플랫폼을 본다. 카테고리는 고른 제목과
     * 같은 플랫폼 값이다. 켜진 플랫폼에 제목이 없으면 옛 칸(플랫폼과 무관한 제목 있는 마지막 관측)도 <b>안 쓴다</b>(재시험 결함, 옛 칸은 꺼진 플랫폼
     * 제목일 수 있다). 옛 수집기(플랫폼 값 없음)일 때만 부르는 쪽이 옛 칸을 쓴다.
     */
    private static BroadcastTitle titleOf(List<BroadcastLatest.PlatformLatest> onAir) {
        for (BroadcastLatest.PlatformLatest each : onAir) {
            if (each.title() != null) {
                return new BroadcastTitle(each.title(), each.category());
            }
        }
        return null;
    }

    /**
     * 묶음은 부가 칸이다. 못 읽으면 칸을 비우고(편지 플랫폼 하나로 접힌다) 목록은 낸다. 여기서 던지면 홈·라이브·다시보기가 전부
     * 500이다({@link #originsOrEmpty}와 같은 이유).
     */
    private Map<String, List<String>> bundlesOrEmpty(List<String> streamIds) {
        try {
            return bundles.platformsOf(streamIds);
        } catch (DataAccessException e) {
            log.warn("broadcast.platforms_failed rows={} reason={}", streamIds.size(), e.getClass().getSimpleName());
            return Map.of();
        }
    }

    /**
     * 방송 중 줄마다 「대시보드를 연 편집자 수」(POK-398). 끝난 줄은 안 넣는다(응답에서 null): 끝난 방송은 연결을 닫으므로 셀 것이 없다.
     *
     * <p>🔴 <b>clip 한 대 전제다.</b> 연결은 그 연결을 받은 서버의 메모리에만 있다. 여러 대로 늘리면 각 서버가 자기 몫만 세어
     * 적게 나온다. 그때는 연결 목록을 모으는 곳(Redis 등)이 필요하다.
     */
    private Map<String, Integer> editorsOnlineOf(List<Broadcast> page, long requester) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Broadcast row : page) {
            if (row.getStatus() == BroadcastStatus.LIVE) {
                counts.put(row.getStreamId(),
                        countEditors(viewers.viewerIdsOf(row.getStreamId()), row.getStreamerId(), requester));
            }
        }
        return counts;
    }

    /**
     * 연결 번호에서 스트리머 본인과 묻는 사람을 빼고 사람 수를 센다. <b>숫자로 읽어 비교한다</b>: 연결 번호는 JWT {@code sub} 원문인데
     * 자격 판정이 {@code parseLong}으로 받아 {@code "007"}도 연결이 된다. 문자열로 비교하면 같은 사람이 둘로 세어지고 스트리머가 안 빠진다.
     * 숫자가 아닌 번호는 셈에서 뺀다(자격 판정을 못 지나 연결이 될 수 없지만, 들어와도 목록 문을 죽이지 않는다).
     * 줄의 스트리머 번호가 숫자가 아니면 뺄 사람이 없는 것으로 본다.
     */
    static int countEditors(List<String> viewerIds, String streamerId, long requester) {
        Set<Long> people = new HashSet<>();
        for (String id : viewerIds) {
            Long number = numberOrNull(id);
            if (number != null) {
                people.add(number);
            }
        }
        Long streamer = numberOrNull(streamerId);
        if (streamer != null) {
            people.remove(streamer);
        }
        people.remove(requester);
        return people.size();
    }

    private static Long numberOrNull(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 사진 주소(POK-277). 방송 중이면 최신 화면, 끝났으면 최고 점수 카드 장면. 부가 칸이라 못 읽으면 비운다({@link ThumbnailUrls}). */
    private Map<String, String> thumbnailsOf(List<Broadcast> page) {
        List<String> live = page.stream().filter(b -> b.getStatus() == BroadcastStatus.LIVE).map(Broadcast::getStreamId).toList();
        List<String> ended = page.stream().filter(b -> b.getStatus() != BroadcastStatus.LIVE).map(Broadcast::getStreamId).toList();
        return thumbnails.ofBroadcasts(live, ended);
    }

    /**
     * 🔴 <b>{@code toMap}이 아니라 직접 모은다.</b> 같은 번호가 두 번 오면 {@code toMap}은
     * {@code IllegalStateException}으로 죽고 그것은 500이다. 오늘은 auth의 유일 제약이
     * 막아 주지만 <b>clip은 그 불변식을 볼 수 없다</b> — 남의 서버 표의 제약에 우리 문의
     * 생사를 걸지 않는다. 겹치면 <b>먼저 온 줄</b>을 남긴다(둘 중 무엇이든 관계는 같은 값이다).
     */
    private static Map<String, ResolveResult> 관계를_번호로_모은다(AccessibleResult accessible) {
        Map<String, ResolveResult> relations = new LinkedHashMap<>();
        for (AccessibleResult.Entry entry : accessible.streamers()) {
            relations.putIfAbsent(String.valueOf(entry.streamerUserId()), entry.relation());
        }
        return relations;
    }

    /**
     * 우리 토큰인데 {@code sub}가 숫자가 아니면 auth에 물어볼 수가 없다. <b>ERROR를 남기고</b>
     * 「볼 수 없다」로 접는다 — {@code BroadcastAccessGuard.parseNumeric}·
     * {@code SegmentQueryService.parseNumeric}과 <b>같은 판정이다</b>(셋이 같은 모양이라야
     * 한 문만 다르게 답하는 일이 없다). 다른 것은 로그 이름뿐이다({@code clip.list.*}) —
     * 자리가 갈려야 어느 문이 아픈지가 보인다.
     *
     * <p>ERROR인 이유는 이것이 <b>조용한 장애</b>이기 때문이다. 응답으로는 영영 구분이 안 된다.
     * <b>값 자체는 안 찍는다</b> — 개행이 섞이면 로그 한 줄이 여러 줄로 쪼개져 없던 기록을
     * 위조할 수 있다. 저 셋과 달리 여기엔 실을 {@code streamId}가 없다(문이 목록이다).
     */
    private static long 요청자_번호(String requesterSubject) {
        try {
            return Long.parseLong(requesterSubject);
        } catch (NumberFormatException e) {
            log.error("clip.list.identity_not_numeric reason=subject_not_numeric");
            throw new AccessErrors.NotViewableException("subject_not_numeric");
        }
    }

    /**
     * 기준점은 부가 칸이다. 못 재면(장부 표가 없거나 옛 모양) 칸만 비우고 목록은 낸다. 여기서 던지면 홈·라이브·다시보기가
     * 전부 500이 된다(로컬 리뷰 1라운드). 비면 화면은 방송 시작 시각으로 대신한다.
     */
    private Map<String, Instant> originsOrEmpty(List<Broadcast> page) {
        try {
            return origins.originsOf(page);
        } catch (DataAccessException e) {
            log.warn("broadcast.timeline_origin_failed rows={} reason={}", page.size(), e.getClass().getSimpleName());
            return Map.of();
        }
    }
}
