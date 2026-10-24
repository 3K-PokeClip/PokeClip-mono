package com.pokeclip.db.support;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * search_path 가로채기(CVE-2018-1058 계열) 덫 — public CREATE 를 다시 연 DB 를 흉내 낸다(POK-428, 선례 media
 * {@code pgtest/searchpath.go}).
 *
 * <p>3번 서버 넷은 {@code pg_advisory_xact_lock(hashtext(?))} 를 맨 이름으로 부른다(auth
 * {@code AudioTrackLabelRepository} · {@code PairingAttemptRecorder}). 인자가 varchar 라 public 에 {@code hashtext(varchar)}
 * 가 있으면 그 판이 pg_catalog 의 {@code hashtext(text)} 보다 정확히 맞아 골리고, 그것이 내는 integer 는 다시 public 의
 * {@code pg_advisory_xact_lock(integer)} 를 고른다(pg_catalog 에는 bigint 판뿐이다). 경로 순서와 무관하다 — 그래서 서버의
 * 경로를 자기 스키마 하나로 둔다(설계 D5). 이 덫은 그 경로에서 덫이 0회인지 재는 데 쓴다.
 *
 * <p>덫은 관리자가 시험 DB 의 public 에 심는다. 원래 결과를 그대로 돌려주므로 시험 흐름은 멈추지 않고, 불릴 때마다
 * 기록 표에 한 행을 남기고 NOTICE 를 낸다. Flyway 의 잠금은 덫을 두지 않는다 — Flyway 12.4 는 integer 범위 밖의 bigint
 * 리터럴로 부르고 pg_catalog 에 정확히 맞는 bigint 판이 있어 경로와 무관하게 pg_catalog 판이 골린다. 덫은 시험 DB 와
 * 함께 지워진다({@link GrantedRoleDatabase#close}).
 */
public final class SearchPathTraps {

    private static final String LOG_TABLE = "public.pc_search_path_trap_log";

    private final GrantedRoleDatabase db;

    private SearchPathTraps(GrantedRoleDatabase db) {
        this.db = db;
    }

    /** db 의 public 에 덫 둘과 기록 표를 관리자로 심는다. 덫은 부른 신분으로 돌므로 기록 표 INSERT 를 PUBLIC 에 연다. */
    public static SearchPathTraps plant(GrantedRoleDatabase db) {
        List<String> statements = List.of(
                "CREATE TABLE " + LOG_TABLE + " (trap text NOT NULL, caller name NOT NULL DEFAULT current_user)",
                "GRANT INSERT ON " + LOG_TABLE + " TO PUBLIC",
                trapFunction("pg_advisory_xact_lock(integer)", "void",
                        "PERFORM pg_catalog.pg_advisory_xact_lock($1::bigint);"),
                trapFunction("hashtext(varchar)", "integer", "RETURN pg_catalog.hashtext($1::text);"));
        for (String sql : statements) {
            db.asAdmin(sql);
        }
        return new SearchPathTraps(db);
    }

    /** 덫이 지금까지 불린 횟수다(덫 둘의 합). */
    public int calls() {
        try (Connection connection = db.adminConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + LOG_TABLE)) {
            rows.next();
            return rows.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException("덫 기록 읽기 실패", e);
        }
    }

    /** pg_catalog 함수 이름 그대로인 덫이다 — 기록을 남기고 NOTICE 를 낸 뒤 result 로 원래 결과를 만든다. */
    private static String trapFunction(String signature, String returns, String result) {
        return "CREATE FUNCTION public." + signature + " RETURNS " + returns + " LANGUAGE plpgsql AS $fn$ BEGIN "
                + "INSERT INTO " + LOG_TABLE + " (trap) VALUES ('" + signature + "'); "
                + "RAISE NOTICE 'search_path trap % called', '" + signature + "'; " + result + " END $fn$";
    }
}
