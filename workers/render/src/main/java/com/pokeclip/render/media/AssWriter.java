package com.pokeclip.render.media;

import com.pokeclip.render.recipe.Recipe.Aspect;
import com.pokeclip.render.recipe.Recipe.SubtitlePosition;
import com.pokeclip.render.recipe.Recipe.SubtitleSegment;

import java.util.List;
import java.util.Locale;

/**
 * 자리가 있는 번인 자막(계약6 7절 {@code subtitles.position})을 ASS로 쓴다.
 *
 * <p><b>SRT가 아니라 ASS인 이유</b>: 자리를 결과 픽셀 좌표로 찍어야 한다. SRT를 태우면 ffmpeg가 384×288 기준 좌표로 옮기고
 * {@code force_style} 정렬 번호는 옛 SSA 방식이며, 줄 앞의 위치 태그({@code {\pos}})는 버린다(2026-09-27 실측). 그래서
 * 「가운데」를 한 줄 높이로 어림할 수밖에 없어 두 줄 자막이 반 줄 어긋났다(PR #201 codex). ASS는 기준 좌표를 결과 해상도로
 * 두고({@code PlayResX/Y}) 가운데 자막에 {@code \an5\pos}를 붙여, 줄 수와 상관없이 덩어리 가운데가 {@code y}에 온다.
 *
 * <p>글자는 편집기 미리보기의 글자(결과 칸 기준 폭 240px에서 11px·굵게, 좌우 8px, 위·아래 여백은 {@code y}가 이미 담았다)를
 * 결과 폭 비로 옮긴 값이다. <b>ASS 글꼴 크기는 CSS의 em이 아니라 줄 높이(ascent+descent)다</b>. Noto Sans CJK는 그 비가
 * 1.448이라 곱한다. 글자 그림자(미리보기 {@code 0 1px 3px})는 테두리 1px로 대신한다.
 */
public final class AssWriter {

    static final double FONT_EM = 11.0 / 240;
    static final double SIDE = 8.0 / 240;
    static final double OUTLINE = 1.0 / 240;
    static final double NOTO_CJK_LINE = 1.448;

    private AssWriter() {
    }

    /**
     * @param clipped  {@link SrtWriter#clipped}의 결과(이미 클립 축, 빈 줄 없음)
     * @param position 자막 자리(null 아님 — 자리가 없는 v1 자막은 SRT + 예전 모양으로 태운다)
     */
    public static String render(List<SubtitleSegment> clipped, SubtitlePosition position, Aspect aspect) {
        int w = aspect.width();
        int h = aspect.height();
        double fontSize = FONT_EM * w * NOTO_CJK_LINE;
        int side = (int) Math.round(SIDE * w);
        int alignment;
        long marginV;
        String tag;
        switch (position.anchor()) {
            case TOP -> {
                alignment = 8;
                marginV = Math.round(position.y() * h);
                tag = "";
            }
            case BOTTOM -> {
                alignment = 2;
                marginV = Math.round((1 - position.y()) * h);
                tag = "";
            }
            default -> {
                alignment = 5;
                marginV = 0;
                tag = "{\\an5\\pos(" + w / 2 + "," + Math.round(position.y() * h) + ")}";
            }
        }
        StringBuilder out = new StringBuilder()
                .append("[Script Info]\nScriptType: v4.00+\nPlayResX: ").append(w).append("\nPlayResY: ").append(h)
                .append("\nScaledBorderAndShadow: yes\nWrapStyle: 0\n\n")
                .append("[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, "
                        + "BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, "
                        + "Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
                .append("Style: Default,Noto Sans CJK KR,").append(decimal(fontSize))
                .append(",&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,")
                .append(decimal(OUTLINE * w)).append(",0,").append(alignment).append(',').append(side).append(',')
                .append(side).append(',').append(marginV).append(",1\n\n")
                .append("[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");
        for (SubtitleSegment s : clipped) {
            out.append("Dialogue: 0,").append(time(s.startAtMs())).append(',').append(time(s.endAtMs()))
                    .append(",Default,,0,0,0,,").append(tag).append(text(s.text())).append('\n');
        }
        return out.toString();
    }

    /**
     * 자막 글을 ASS 본문으로. 줄바꿈은 {@code \N}. 중괄호는 꾸밈 태그의 시작이고 역슬래시는 제어 문자라, 사용자가 쓴 글이
     * 태그로 읽히지 않게 전각으로 바꾼다(빼면 글이 달라진다).
     */
    static String text(String raw) {
        return raw.replace("\r", "").strip()
                .replace("\\", "＼").replace("{", "｛").replace("}", "｝")
                .replaceAll("\n+", "\\\\N");
    }

    /** ASS 시각 {@code H:MM:SS.cc}(100분의 1초). */
    static String time(long ms) {
        long cs = ms / 10;
        return String.format(Locale.ROOT, "%d:%02d:%02d.%02d", cs / 360_000, cs / 6_000 % 60, cs / 100 % 60, cs % 100);
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
