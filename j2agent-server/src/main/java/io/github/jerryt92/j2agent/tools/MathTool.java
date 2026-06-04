package io.github.jerryt92.j2agent.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

/**
 * 数学计算工具
 * 利用 Spring SpEL 表达式引擎进行安全的数学运算
 * <p>
 * 更新：已支持 Long 类型运算，自动将整数字面量升级为 Long 以防止 int 溢出。
 */
@Slf4j
@Component
public class MathTool {

    private final ExpressionParser parser = new SpelExpressionParser();

    // 预编译正则：用于匹配独立的整数（不包含小数点、不包含L后缀）
    // 说明：
    // (?<![\d.])  : 回顾后发断言，前面不能是数字或小数点（防止匹配小数的后半部分）
    // (\d+)       : 匹配一个或多个数字
    // (?![\d.L])  : 展望先行断言，后面不能是数字、小数点或L（防止匹配小数的前半部分或已经是Long的数）
    private final Pattern INTEGER_PATTERN = Pattern.compile("(?<![\\d.])(\\d+)(?![\\d.L])");

    @Tool(name = "math_calculator", description = "数学计算器，支持 Long 精度（64位整数）及浮点运算。可执行加减乘除、括号等表达式。")
    private String mathCalculator(
            @ToolParam(description = "需要计算的数学表达式，支持大整数运算，例如: (2000000000 * 3) + 5") String expression
    ) {
        String result;
        if (!StringUtils.hasText(expression)) {
            result = "Error: 表达式不能为空";
        } else {
            try {
                // 1. 基础符号替换
                String safeExpression = expression.replace("（", "(")
                        .replace("）", ")")
                        .replace("×", "*")
                        .replace("÷", "/");

                // 2. 核心修改：将表达式中的普通整数转换为 Long 字面量 (例如 "10" -> "10L")
                // 这样 SpEL 在计算时会使用 64位 Long 进行运算，避免 int * int 溢出
                safeExpression = INTEGER_PATTERN.matcher(safeExpression).replaceAll("$1L");

                // 3. 配置上下文：保持 SimpleEvaluationContext 以确保安全
                SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding().build();

                // 4. 解析并计算
                Expression expressionObj = parser.parseExpression(safeExpression);
                Object value = expressionObj.getValue(context);

                if (value != null) {
                    // 这里的 toString() 对于 Long 类型会自动处理
                    result = value.toString();
                } else {
                    result = "Error: 计算结果为空";
                }
                log.info("MathCalculator executed: orig=[{}] safe=[{}] result=[{}]", expression, safeExpression, value);
            } catch (Exception e) {
                log.error("MathCalculator execution failed for expression: {}", expression, e);
                result = "Error: 计算失败 - " + e.getMessage();
            }
        }
        return result;
    }
}