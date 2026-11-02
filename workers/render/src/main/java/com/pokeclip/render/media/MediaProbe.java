package com.pokeclip.render.media;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** ffprobe 한 번으로 조각의 해상도·소리 스트림 수·영상 길이·소리 자리를 잰다. */
public class MediaProbe {

    private final ProcessRunner runner;
    private final ObjectMapper mapper;
    private final String ffprobe;

    public MediaProbe(ProcessRunner runner, ObjectMapper mapper, String ffprobe) {
        this.runner = runner;
        this.mapper = mapper;
        this.ffprobe = ffprobe;
    }

    public MediaInfo probe(Path file, Path workDir, Instant deadline) {
        ProcessRunner.Result result = runner.run(List.of(ffprobe, "-v", "error",
                "-show_entries",
                "format=start_time:stream=codec_type,width,height,start_time,duration:stream_side_data=rotation",
                "-of", "json", file.toString()), workDir, deadline);
        return parse(mapper.readTree(result.stdout()));
    }

    static MediaInfo parse(JsonNode root) {
        int width = 0;
        int height = 0;
        long videoDurationMs = 0;
        int audio = 0;
        double audioStart = 0;
        double audioDuration = 0;
        boolean videoSeen = false;
        for (JsonNode stream : root.path("streams")) {
            String type = stream.path("codec_type").asString("");
            if ("video".equals(type) && !videoSeen) {
                videoSeen = true;
                width = stream.path("width").asInt(0);
                height = stream.path("height").asInt(0);
                videoDurationMs = Math.round(seconds(stream, "duration") * 1000);
                int rotation = 0;
                for (JsonNode side : stream.path("side_data_list")) {
                    rotation = side.path("rotation").asInt(rotation);
                }
                if (Math.abs(rotation) % 180 == 90) {
                    int swap = width;
                    width = height;
                    height = swap;
                }
            } else if ("audio".equals(type)) {
                if (audio == 0) {
                    audioStart = seconds(stream, "start_time");
                    audioDuration = seconds(stream, "duration");
                }
                audio++;
            }
        }
        double fileStart = seconds(root.path("format"), "start_time");
        return new MediaInfo(width, height, audio, videoDurationMs,
                Math.round((audioStart - fileStart) * 1_000_000), Math.round(audioDuration * 1_000_000));
    }

    private static double seconds(JsonNode node, String field) {
        String value = node.path(field).asString("0");
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
