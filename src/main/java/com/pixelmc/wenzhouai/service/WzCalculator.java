package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** Gameplay arithmetic with decimal numbers. The grammar has no external state or executable names. */
final class WzCalculator {
    private static final MathContext PRECISION = MathContext.DECIMAL128;
    static final int MAX_LENGTH = 256;
    private final String input;
    private int position;

    private WzCalculator(String input) {
        this.input = input;
    }

    static JsonObject calculate(String expression) {
        JsonObject reply = new JsonObject();
        try {
            if (expression == null || expression.isBlank() || expression.length() > MAX_LENGTH)
                throw new IllegalArgumentException("请输入 1 至 256 字符的算式。");
            WzCalculator parser = new WzCalculator(expression);
            BigDecimal value = parser.sum().stripTrailingZeros();
            parser.skipSpaces();
            if (parser.position != expression.length()) throw parser.syntax();
            reply.addProperty("status", "calculated");
            reply.addProperty("expression", expression.strip());
            reply.addProperty("result", value.toPlainString());
        } catch (IllegalArgumentException invalid) {
            reply.addProperty("status", "invalid_calculation");
            reply.addProperty("message", invalid.getMessage());
        } catch (ArithmeticException invalid) {
            reply.addProperty("status", "invalid_calculation");
            reply.addProperty("message", "除数不能为零，幂指数必须为 -1000 至 1000 的整数。");
        }
        return reply;
    }

    private BigDecimal sum() {
        BigDecimal value = product();
        while (true) {
            if (take('+')) value = bounded(value.add(product(), PRECISION));
            else if (take('-')) value = bounded(value.subtract(product(), PRECISION));
            else return value;
        }
    }

    private BigDecimal product() {
        BigDecimal value = signedPower();
        while (true) {
            if (take('*')) value = bounded(value.multiply(signedPower(), PRECISION));
            else if (take('/')) value = bounded(value.divide(signedPower(), PRECISION));
            else return value;
        }
    }

    private BigDecimal signedPower() {
        if (take('-')) return signedPower().negate();
        if (take('+')) return signedPower();
        BigDecimal value = primary();
        if (take('%')) value = bounded(value.movePointLeft(2));
        if (take('^')) {
            int exponent = signedPower().intValueExact();
            if (exponent < -1000 || exponent > 1000)
                throw new IllegalArgumentException("幂指数超出 -1000 至 1000。");
            if (value.signum() == 0 && exponent <= 0)
                throw new IllegalArgumentException("零的零次幂或负数次幂没有可用结果。");
            value = bounded(value.pow(exponent, PRECISION));
        }
        return value;
    }

    private BigDecimal primary() {
        if (take('(')) return group();
        for (String function : new String[]{"ceil", "floor"}) {
            if (input.startsWith(function, position)) {
                position += function.length();
                if (!take('(')) throw syntax();
                RoundingMode mode = function.equals("ceil") ? RoundingMode.CEILING : RoundingMode.FLOOR;
                return group().setScale(0, mode);
            }
        }
        int start = position;
        boolean digit = false;
        boolean dot = false;
        while (position < input.length()) {
            char c = input.charAt(position);
            if (c >= '0' && c <= '9') digit = true;
            else if (c == '.' && !dot) dot = true;
            else break;
            position++;
        }
        if (!digit) throw syntax();
        return bounded(new BigDecimal(input.substring(start, position), PRECISION));
    }

    private BigDecimal group() {
        BigDecimal value = sum();
        if (!take(')')) throw syntax();
        return value;
    }

    private static BigDecimal bounded(BigDecimal value) {
        int magnitude = value.precision() - value.scale();
        if (value.signum() != 0 && (magnitude > 256 || magnitude < -256))
            throw new IllegalArgumentException("数值超出计算范围。");
        return value.signum() == 0 ? BigDecimal.ZERO : value;
    }

    private void skipSpaces() {
        while (position < input.length() && input.charAt(position) == ' ') position++;
    }

    private boolean take(char expected) {
        skipSpaces();
        if (position == input.length() || input.charAt(position) != expected) return false;
        position++;
        return true;
    }

    private IllegalArgumentException syntax() {
        return new IllegalArgumentException("第 " + (position + 1) + " 个字符附近的算式无效。");
    }
}
