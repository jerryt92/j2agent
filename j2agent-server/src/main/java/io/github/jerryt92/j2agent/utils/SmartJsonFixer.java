package io.github.jerryt92.j2agent.utils;

import java.util.regex.Pattern;

public class SmartJsonFixer {

    private static final Pattern NUMERIC_PATTERN = Pattern.compile("^-?\\d+(\\.\\d+)?$");

    /**
     * 尝试修复各种非标准 JSON 字符串
     */
    public static String fix(String json) {
        if (json == null || json.trim().isEmpty()) {
            return json;
        }
        String input = json.trim();

        // 预处理：修复并行调用粘连问题 "...} {" -> "...}, {"
        input = input.replace("}{", "},{");

        StringBuilder sb = new StringBuilder();
        boolean inQuote = false;
        boolean expectKey = false; // 只有在 { 之后或者 , 之后才期待 Key
        boolean expectValue = false; // 在 : 或 = 之后期待 Value
        StringBuilder buffer = new StringBuilder(); // 用于累积当前的 Key 或 Value 文本

        // 简单的状态标记，用于判断当前层级（仅做简单推断）
        int braceLevel = 0;
        int bracketLevel = 0;

        char[] chars = input.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];

            if (inQuote) {
                // 如果在引号内，直接追加，直到遇到结束引号（且未转义）
                buffer.append(c);
                if (c == '"' && (i == 0 || chars[i - 1] != '\\')) {
                    inQuote = false;
                    sb.append(buffer);
                    buffer.setLength(0); // 清空缓冲区
                }
                continue;
            }

            switch (c) {
                case '{':
                    sb.append(processBuffer(buffer, expectValue)); // 可能是 Value 位置开始了一个对象
                    sb.append(c);
                    expectKey = true;
                    expectValue = false;
                    braceLevel++;
                    break;
                case '}':
                    sb.append(processBuffer(buffer, expectValue)); // 结束前的内容处理
                    sb.append(c);
                    expectKey = false;
                    expectValue = false;
                    braceLevel--;
                    break;
                case '[':
                    sb.append(processBuffer(buffer, expectValue));
                    sb.append(c);
                    expectKey = false; // 数组内不是键值对
                    expectValue = true; // 数组内全是 Value
                    bracketLevel++;
                    break;
                case ']':
                    sb.append(processBuffer(buffer, expectValue));
                    sb.append(c);
                    expectValue = false;
                    bracketLevel--;
                    break;
                case ':':
                case '=':
                    // 分隔符，统一转为 :
                    // 在此之前 buffer 里的内容通常是 Key
                    String key = buffer.toString().trim();
                    if (!key.isEmpty()) {
                        // 如果 Key 没有引号，加上引号
                        if (!key.startsWith("\"")) {
                            sb.append("\"").append(key).append("\"");
                        } else {
                            sb.append(key);
                        }
                    }
                    buffer.setLength(0);
                    sb.append(':');
                    expectKey = false;
                    expectValue = true;
                    break;
                case ',':
                    sb.append(processBuffer(buffer, expectValue));
                    sb.append(c);
                    buffer.setLength(0);
                    // 只有在对象内遇到逗号，下一个才是 Key
                    if (braceLevel > 0 && bracketLevel == 0) {
                        expectKey = true;
                    }
                    expectValue = false; // 重置
                    break;
                case '"':
                    // 遇到引号开始
                    inQuote = true;
                    buffer.append(c);
                    break;
                default:
                    // 普通字符，累积到缓冲区
                    buffer.append(c);
                    break;
            }
        }

        // 处理最后可能剩余的 buffer
        if (buffer.length() > 0) {
            sb.append(processBuffer(buffer, expectValue));
        }

        String result = sb.toString();

        // 最后兜底：如果整个字符串不是以 [ 或 { 开头，强制包装成数组
        if (!result.startsWith("[") && !result.startsWith("{")) {
            return "[" + result + "]";
        }
        // 如果是单个对象，为了适配 List<JSONObject>，包装成数组
        if (result.startsWith("{")) {
            return "[" + result + "]";
        }

        return result;
    }

    /**
     * 处理缓冲区的内容 (可能是 Value，也可能是无用的空白)
     */
    private static String processBuffer(StringBuilder buffer, boolean isValuePosition) {
        String text = buffer.toString().trim();
        buffer.setLength(0);

        if (text.isEmpty()) {
            return "";
        }

        // 如果我们在 Value 的位置 (即冒号之后，或者数组元素中)
        if (isValuePosition) {
            // 如果已经是被引号包裹的，直接返回
            if (text.startsWith("\"") && text.endsWith("\"")) {
                // 修复双重引号问题: ""value"" -> "value"
                if (text.startsWith("\"\"") && text.endsWith("\"\"")) {
                    return text.substring(1, text.length() - 1);
                }
                return text;
            }

            // 关键逻辑：判断是否需要强制加引号
            // 如果是纯数字（整数或小数），直接返回，不需要引号
            if (NUMERIC_PATTERN.matcher(text).matches()) {
                return text;
            }

            // 如果是布尔值或 null，直接返回
            if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text) || "null".equalsIgnoreCase(text)) {
                return text.toLowerCase();
            }

            // **所有其他情况（包括数学表达式 1+1, IP地址, 字符串等），强制加上引号**
            // 注意转义内部的引号
            return "\"" + text.replace("\"", "\\\"") + "\"";
        }

        return text;
    }
}