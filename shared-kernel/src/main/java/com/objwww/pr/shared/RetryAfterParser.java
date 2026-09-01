package com.objwww.pr.shared;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/** Retry-After 的两种标准形态解析：正整数秒或 RFC 1123 HTTP-date。 */
public final class RetryAfterParser {

    private RetryAfterParser() {
    }

    /** 缺失、非法、零/负数或过去时返回 {@code null}；HTTP-date 向上取整到秒。 */
    public static Long parseSeconds(String value, Instant now) {
        Objects.requireNonNull(now, "now");
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            return seconds > 0 ? seconds : null;
        } catch (NumberFormatException ignored) {
            // 继续尝试 HTTP-date。
        }
        try {
            Instant target = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            long millis = target.toEpochMilli() - now.toEpochMilli();
            return millis > 0 ? Math.max(1L, (millis + 999L) / 1000L) : null;
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
