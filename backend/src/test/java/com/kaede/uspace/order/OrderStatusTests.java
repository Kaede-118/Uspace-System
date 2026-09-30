package com.kaede.uspace.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OrderStatus} 的单元测试。
 *
 * <p>枚举只有三个取值，但两个静态判断被别处依赖，各自钉住一条：
 * <ol>
 *   <li>{@code isUnpaid} —— 下单前的「上一单还没了结」校验与管理员人工调整
 *       都拿它当开关。判错一个取值，用户要么被无故挡在门外、要么能欠着费接着玩</li>
 *   <li>{@code isValid} —— 后台按状态筛选时拿它挡非法入参。
 *       状态列是 {@code VARCHAR}，认错了会把任意字符串拼进 SQL，而库不会拦</li>
 * </ol>
 *
 * <p><b>本类最要紧的是「传 null 不抛异常」那一组。</b>
 * {@code isUnpaid} 原先写作 {@code Set.of(...).contains(name)}，
 * 而 JDK 的不可变集合对 null 查询会抛 {@link NullPointerException}
 * （内部用 {@code requireNonNull} 挡 null），与它自己「null 返回 false」的契约
 * 正好相反。同一个坑在 {@code DeviceStatus.isUsable} 上已被逮到过一次，
 * 本类补的是同一批剩余的写法 —— 改动 {@code isUnpaid} 时请一并跑本类。
 */
class OrderStatusTests {

    @Test
    @DisplayName("未付款判定：使用中与待支付算未付款，已支付不算")
    void isUnpaid() {
        assertTrue(OrderStatus.isUnpaid("IN_USE"), "人还在店里玩，这一单自然没了结");
        assertTrue(OrderStatus.isUnpaid("PENDING_PAYMENT"),
                "账单已出、钱还没付 —— 这正是「欠着费」要挡的那一档");

        assertFalse(OrderStatus.isUnpaid("PAID"),
                "付过的单是已经了结的，算进去会让所有老顾客都开不了新单");
    }

    @Test
    @DisplayName("未付款判定：null 与认不出的取值一律返回 false，不抛异常")
    void isUnpaid_toleratesNullAndUnknown() {
        assertFalse(OrderStatus.isUnpaid(null),
                "契约写的就是「null 返回 false」，实现不能因为用了 Set.of 就抛 NPE");
        assertFalse(OrderStatus.isUnpaid("CANCELLED"),
                "建表脚本里留着这两个保留值，真被人手工插进来时不该崩");
        assertFalse(OrderStatus.isUnpaid("CREATED"));
        assertFalse(OrderStatus.isUnpaid("in_use"), "大小写不匹配就不算数，不做模糊匹配");
        assertFalse(OrderStatus.isUnpaid(""));
    }

    @Test
    @DisplayName("合法取值：三个枚举名都认，其余一律不认")
    void isValid() {
        assertTrue(OrderStatus.isValid("IN_USE"));
        assertTrue(OrderStatus.isValid("PENDING_PAYMENT"));
        assertTrue(OrderStatus.isValid("PAID"));

        assertFalse(OrderStatus.isValid("CREATED"),
                "它是建表脚本的默认值，但业务代码从不产生它 —— 不该被当成合法状态放行");
        assertFalse(OrderStatus.isValid("in_use"));
        assertFalse(OrderStatus.isValid(""));
        assertFalse(OrderStatus.isValid(null));
    }

    @Test
    @DisplayName("中文说明：三个取值各有标签，认不出的原样返回")
    void labelOf() {
        assertEquals("使用中", OrderStatus.labelOf("IN_USE"));
        assertEquals("待支付", OrderStatus.labelOf("PENDING_PAYMENT"));
        assertEquals("已支付", OrderStatus.labelOf("PAID"));

        assertEquals("CREATED", OrderStatus.labelOf("CREATED"),
                "认不出的取值原样返回，让异常数据在界面上一眼看得出来 —— "
                        + "伪装成一个正常的中文状态反而会把它藏起来");
        assertNull(OrderStatus.labelOf(null), "null 进来 null 出去，前端自行兜底显示");
    }
}
