package com.kaede.uspace.order.dto;

import com.kaede.uspace.order.entity.Order;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OrderOpenVo} 的单元测试。
 *
 * <p>只测一件容易写错、且写错了不会报错的事：{@code inBooking} 这个标记。
 * 它决定密码弹窗上「包场参与者全员都要开门计时」那句提示显不显示 ——
 * 判错了不会报任何警：
 * <ul>
 *   <li>判宽了（比如按「订单挂没挂 bookingId」判）→ 一个提前一天到店的参与者，
 *       打开弹窗会看到一句包场的话，<b>进而以为现在不计费</b></li>
 *   <li>判窄了 → 真正在包场里的人看不到那条规矩，各自不开门计时，到场人员核对不上</li>
 * </ul>
 */
class OrderOpenVoTests {

    /**
     * 造一个「刚开出来的订单」。
     *
     * @param id 订单 ID
     * @return 订单实体，字段填到够 {@link OrderOpenVo#of} 用
     */
    private static Order order(Long id) {
        Order order = new Order();
        order.setId(id);
        order.setOrderNo("UO2026093000" + id);
        order.setPasscode("123456");
        order.setPasscodeStart(LocalDateTime.now());
        order.setPasscodeEnd(LocalDateTime.now().plusHours(12));
        order.setStartTime(LocalDateTime.now());
        return order;
    }

    @Test
    @DisplayName("此刻在包场时段内：inBooking 为 true")
    void of_inBookingTrueInsideBooking() {
        assertTrue(OrderOpenVo.of(order(1L), false, false, true).isInBooking(),
                "包场里的人要看到那条「各自开门计时、各自结账」的规矩");
    }

    @Test
    @DisplayName("此刻不在包场时段内：inBooking 为 false，哪怕订单挂着包场")
    void of_inBookingFalseOutsideBooking() {
        Order order = order(2L);
        // 挂着明天的包场 —— 但那是「他是为这场包场来的」，不等于「此刻在包场里」
        order.setBookingId(99L);

        assertFalse(OrderOpenVo.of(order, false, false, false).isInBooking(),
                "判据是「此刻落在包场时段内」，不是「订单挂没挂 bookingId」—— "
                        + "参与者提前一天到店时订单也会挂上 bookingId，但他此刻就是在普通消费，"
                        + "弹窗上冒出一句包场的话会让他以为现在不计费");
    }
}
