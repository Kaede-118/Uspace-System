package com.kaede.uspace.space;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BookingStatus} 的单元测试。
 *
 * <p>两个静态判断各钉一条：
 * <ol>
 *   <li>{@code occupiesSlot} —— 排期时用它判断「这场包场还占不占着这个时段」。
 *       判宽了会把已取消的包场也算成占位、白白挡住新排期；
 *       判窄了会允许两场包场撞在同一时段，付款后才发现进不去门</li>
 *   <li>{@code isValid} —— 状态列是 {@code VARCHAR}，落库前靠它挡非法取值</li>
 * </ol>
 *
 * <p><b>本类最要紧的是 {@code occupiesSlot(null)}</b>：它的实现原先写作
 * {@code Set.of(...).contains(name)}，而 JDK 的不可变集合对 null 查询会抛
 * {@link NullPointerException}，与「认不出就返回 false」的契约正好相反。
 * 同一个坑在 {@code DeviceStatus.isUsable} 上已被逮到过一次，本类是同一批的收尾。
 */
class BookingStatusTests {

    @Test
    @DisplayName("占时段判定：待付款与已付款都算占着，已取消 / 已退款 / 已结束不算")
    void occupiesSlot() {
        assertTrue(BookingStatus.occupiesSlot("PENDING_PAYMENT"),
                "虽然还没付款，但管理员排期时已经把它许出去了 —— "
                        + "此时再排一场重叠的，付款后会撞车");
        assertTrue(BookingStatus.occupiesSlot("PAID"));

        assertFalse(BookingStatus.occupiesSlot("CANCELLED"),
                "取消了就不该继续占着时段，否则这个时段白白空着排不出去");
        assertFalse(BookingStatus.occupiesSlot("REFUNDED"),
                "撤销退款就是把时段还回来 —— 判成占着的话，那一场退掉之后 "
                        + "这个时段再也排不出去，且没有任何报错");
        assertFalse(BookingStatus.occupiesSlot("CLOSED"), "时段已过，归档状态不参与排期判定");
    }

    @Test
    @DisplayName("占时段判定：null 与认不出的取值一律返回 false，不抛异常")
    void occupiesSlot_toleratesNullAndUnknown() {
        assertFalse(BookingStatus.occupiesSlot(null),
                "契约写的就是「认不出返回 false」，实现不能因为用了 Set.of 就抛 NPE");
        assertFalse(BookingStatus.occupiesSlot("pending_payment"), "大小写不匹配就不算数");
        assertFalse(BookingStatus.occupiesSlot(""));
    }

    @Test
    @DisplayName("合法取值：五个枚举名都认，其余一律不认")
    void isValid() {
        assertTrue(BookingStatus.isValid("PENDING_PAYMENT"));
        assertTrue(BookingStatus.isValid("PAID"));
        assertTrue(BookingStatus.isValid("CANCELLED"));
        assertTrue(BookingStatus.isValid("REFUNDED"));
        assertTrue(BookingStatus.isValid("CLOSED"));

        assertFalse(BookingStatus.isValid("paid"));
        assertFalse(BookingStatus.isValid("待付款"));
        assertFalse(BookingStatus.isValid(""));
        assertFalse(BookingStatus.isValid(null));
    }
}
