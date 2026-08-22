package com.pokeclip.auth.delegation.api.dto;

import com.pokeclip.auth.delegation.DelegationRelation;

import java.util.List;

/**
 * 이 사람이 볼 수 있는 스트리머 전부. 본인이 항상 들어간다(OWNER) — 첫 줄에 두지만
 * clip은 <b>순서가 아니라 relation으로</b> 찾아야 한다. 이 응답에 NONE은 나오지 않는다 —
 * 목록에 없는 것이 곧 NONE이다.
 *
 * <p>번호와 관계만 싣는다. DelegationResponse(공개 API, 이름 포함)를 재사용하지 않는다 —
 * 서버용 창구에 사람 화면용 정보가 실리면 노출 축이 하나 는다(POK-57 authz-auditor).
 *
 * <p>상한 없음. 목록 길이는 편집자 본인이 못 늘린다 — 스트리머가 초대해야 한 줄이 늘고,
 * 전체 길이는 스트리머 수에 묶인다(PRD 결정).
 */
public record AccessibleStreamersResponse(List<Entry> streamers) {

    public record Entry(Long streamerUserId, DelegationRelation relation) {
    }
}
