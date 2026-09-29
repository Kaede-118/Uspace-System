package com.kaede.uspace.common.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link ErrorCode} 自身的约束测试（公共层）。
 *
 * <p><b>为什么需要这个测试类</b>：枚举里的业务码是给前端做分支判断用的，
 * 但重复了**既不会编译报错，也不会让任何业务测试变红** ——
 * 因为业务测试断言的是枚举常量（{@code ErrorCode.BOOKING_PREPARING}），
 * 不是那个数字。等到前端按 code 分流时才会暴露：两个不同语义的码拿到同一个数字，
 * 它无法判断该弹哪个提示，而这时已经要真机联调了。
 *
 * <p>2026-09-29 加包场准入的两个码时就撞过一次（写成了已有订单模块占用的
 * 40918 / 40919），因此在这里钉住。
 */
class ErrorCodeTests {

    @Test
    @DisplayName("业务码不得重复")
    void codesAreUnique() {
        Map<Integer, ErrorCode> seen = new HashMap<>();
        for (ErrorCode code : ErrorCode.values()) {
            ErrorCode previous = seen.put(code.getCode(), code);
            if (previous != null) {
                fail(String.format("业务码 %d 同时被 %s 与 %s 使用 —— 前端按 code 分流时无法区分两者",
                        code.getCode(), previous, code));
            }
        }
    }

    @Test
    @DisplayName("业务码前三位与 HTTP 状态码对齐")
    void codePrefixMatchesHttpStatus() {
        for (ErrorCode code : ErrorCode.values()) {
            assertEquals(code.getHttpStatus().value(), code.getCode() / 100,
                    () -> String.format("%s 的业务码 %d 与 HTTP 状态码 %s 不对齐",
                            code, code.getCode(), code.getHttpStatus().value()));
        }
    }
}
