package io.github.jerryt92.j2agent.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 时间工具类
 * <p>
 * 提供以下功能：
 * 1. 获取当前本地时间
 * 2. 时间戳与日期时间的相互转换（支持多种时区和格式）
 * <p>
 * 所有工具方法均通过 @Tool 注解暴露给 AI Agent 使用
 *
 * @author AI Center Team
 */
@Slf4j
@Component
public class TimeTool {

    /**
     * 获取当前本地时间工具
     * <p>
     * 返回系统默认时区的当前时间，格式化为可读字符串
     *
     * @return 格式化后的当前时间字符串，格式: yyyy-MM-dd HH:mm:ss
     */
    @Tool(name = "get_current_time", description = "获取当前本地时间，返回格式为 yyyy-MM-dd HH:mm:ss")
    public String getCurrentTime() {
        try {
            LocalDateTime now = LocalDateTime.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            String currentTime = now.format(formatter);
            log.info("获取当前时间: {}", currentTime);
            return currentTime;
        } catch (Exception e) {
            log.error("获取当前时间失败", e);
            return "Error: 获取时间失败 - " + e.getMessage();
        }
    }

    /**
     * 时间戳转日期时间工具
     * <p>
     * 将 Unix 时间戳转换为人类可读的日期时间字符串
     * <p>
     * 特性：
     * - 自动识别秒级（10位）和毫秒级（13位）时间戳
     * - 支持自定义时区（默认使用系统时区）
     * - 支持自定义日期格式（默认: yyyy-MM-dd HH:mm:ss）
     *
     * @param timestamp 时间戳值（秒或毫秒），例如: 1700000000000（毫秒）或 1700000000（秒）
     * @param timezone  可选的时区ID，例如: Asia/Shanghai、UTC、America/New_York，默认为系统时区
     * @param format    可选的日期格式模式，例如: yyyy-MM-dd HH:mm:ss、yyyy/MM/dd，默认为 yyyy-MM-dd HH:mm:ss
     * @return 格式化后的日期时间字符串，失败时返回错误信息
     */
    @Tool(name = "timestamp_to_datetime", description = "将时间戳转换为可读的日期时间字符串，自动识别秒/毫秒级时间戳")
    public String timestampToDatetime(
            @ToolParam(description = "时间戳（毫秒或秒），例如: 1700000000000 或 1700000000") long timestamp,
            @ToolParam(description = "可选的时区ID，例如: Asia/Shanghai, UTC, America/New_York，默认为系统时区") String timezone,
            @ToolParam(description = "可选的日期格式，例如: yyyy-MM-dd HH:mm:ss, yyyy/MM/dd，默认为 yyyy-MM-dd HH:mm:ss") String format
    ) {
        try {
            // 判断时间戳是秒还是毫秒（小于 10^12 认为是秒级时间戳）
            long actualTimestamp = timestamp;
            if (timestamp < 1000000000000L) {
                actualTimestamp = timestamp * 1000;
                log.info("检测到秒级时间戳，已转换为毫秒: {} -> {}", timestamp, actualTimestamp);
            }

            // 设置时区（优先使用传入参数，否则使用系统默认时区）
            ZoneId zoneId = StringUtils.hasText(timezone) ? ZoneId.of(timezone) : ZoneId.systemDefault();

            // 设置日期格式（优先使用传入参数，否则使用默认格式）
            String dateFormat = StringUtils.hasText(format) ? format : "yyyy-MM-dd HH:mm:ss";

            // 执行转换：时间戳 -> Instant -> LocalDateTime -> 格式化字符串
            LocalDateTime dateTime = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(actualTimestamp),
                    zoneId
            );

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(dateFormat);
            String result = dateTime.format(formatter);

            log.info("时间戳转换: {} -> {} (时区: {}, 格式: {})", timestamp, result, zoneId, dateFormat);
            return result;
        } catch (Exception e) {
            log.error("时间戳转换失败: {}", timestamp, e);
            return "Error: 时间戳转换失败 - " + e.getMessage();
        }
    }

    /**
     * 日期时间转时间戳工具
     * <p>
     * 将人类可读的日期时间字符串转换为 Unix 时间戳（毫秒级）
     * <p>
     * 特性：
     * - 支持自定义日期格式解析
     * - 支持自定义时区（影响时间戳计算）
     * - 返回毫秒级时间戳（13位）
     *
     * @param datetime 日期时间字符串，例如: 2024-01-01 12:00:00
     * @param format   日期格式模式，例如: yyyy-MM-dd HH:mm:ss、yyyy/MM/dd HH:mm:ss，默认为 yyyy-MM-dd HH:mm:ss
     * @param timezone 可选的时区ID，例如: Asia/Shanghai、UTC、America/New_York，默认为系统时区
     * @return 毫秒级时间戳（long类型）
     * @throws RuntimeException 当日期格式不正确或解析失败时抛出异常
     */
    @Tool(name = "datetime_to_timestamp", description = "将日期时间字符串转换为毫秒级时间戳")
    public long datetimeToTimestamp(
            @ToolParam(description = "日期时间字符串，例如: 2024-01-01 12:00:00") String datetime,
            @ToolParam(description = "日期格式，例如: yyyy-MM-dd HH:mm:ss, yyyy/MM/dd HH:mm:ss，默认为 yyyy-MM-dd HH:mm:ss") String format,
            @ToolParam(description = "可选的时区ID，例如: Asia/Shanghai, UTC, America/New_York，默认为系统时区") String timezone
    ) {
        try {
            if (!StringUtils.hasText(datetime)) {
                throw new IllegalArgumentException("日期时间字符串不能为空");
            }

            // 设置日期格式（优先使用传入参数，否则使用默认格式）
            String dateFormat = StringUtils.hasText(format) ? format : "yyyy-MM-dd HH:mm:ss";

            // 设置时区（优先使用传入参数，否则使用系统默认时区）
            ZoneId zoneId = StringUtils.hasText(timezone) ? ZoneId.of(timezone) : ZoneId.systemDefault();

            // 执行转换：字符串 -> LocalDateTime -> ZonedDateTime -> Instant -> 时间戳
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(dateFormat);
            LocalDateTime dateTime = LocalDateTime.parse(datetime, formatter);

            long timestamp = dateTime.atZone(zoneId).toInstant().toEpochMilli();

            log.info("日期时间转换: {} -> {} (时区: {}, 格式: {})", datetime, timestamp, zoneId, dateFormat);
            return timestamp;
        } catch (Exception e) {
            log.error("日期时间转换失败: {}", datetime, e);
            throw new RuntimeException("日期时间转换失败: " + e.getMessage(), e);
        }
    }
}