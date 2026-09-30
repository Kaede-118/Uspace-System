package com.kaede.uspace.space;

import java.util.Arrays;

/**
 * 包场参与者角色。
 *
 * <p>取值与建表脚本里 {@code biz_booking_participant.role} 的注释一一对应。
 *
 * <p><b>为什么包场人也要在参与者表里占一行</b>：这样「谁在这场包场里」只有一个答案，
 * 不必一处查 {@code biz_booking.host_user_id}、另一处查参与者表。
 * 两个列表（我创建的 / 我参与的）之间的差别随之只剩 {@code role} 一个变量，
 * 将来要支持包场人转让、或多发起人，加一个取值即可，查询不必改。
 *
 * <p>但要注意<b>它不改变准入的兜底</b>：{@code HOST} 行在付款成功那一刻才写入，
 * 而付款之前的准入判定仍要看 {@code biz_booking.host_user_id} ——
 * 详见 {@code OrderService#isBookingAllowed}。
 */
public enum BookingParticipantRole {

    /** 包场人（创建者）。付款成功时写入，与邀请令牌同一事务 */
    HOST("发起人"),

    /** 被邀请者。点开邀请链接时写入 */
    PARTICIPANT("参与者");

    /** 中文文案，直接给前端展示，省得前端再维护一份翻译 */
    private final String text;

    BookingParticipantRole(String text) {
        this.text = text;
    }

    /**
     * 取角色对应的中文文案。
     *
     * @return 可直接展示的文案，如「发起人」
     */
    public String getText() {
        return text;
    }

    /**
     * 判断一个字符串是否为合法角色名。
     *
     * <p>用于校验接口入参，避免把非法值写进数据库 ——
     * 角色列是 {@code VARCHAR} 而非 {@code ENUM}，库不会替我们挡住。
     *
     * @param name 待校验的角色名，可为 null
     * @return 合法返回 true；null 或不在枚举内返回 false
     */
    public static boolean isValid(String name) {
        return name != null && Arrays.stream(values()).anyMatch(r -> r.name().equals(name));
    }

    /**
     * 把数据库里的角色名翻译成中文文案。
     *
     * <p>认不出的取值返回 null 而不是抛异常：角色名来自数据库的 {@code VARCHAR} 列，
     * 手工改库、或将来加了新取值而这里没跟上，都会走到这条路径上。
     * 让整个参与者列表因为一个认不出的角色而 500，代价远大于少显示一个标签。
     *
     * @param name 角色名，可为 null
     * @return 对应的中文文案；认不出时返回 null
     */
    public static String textOf(String name) {
        return Arrays.stream(values())
                .filter(r -> r.name().equals(name))
                .map(BookingParticipantRole::getText)
                .findFirst()
                .orElse(null);
    }

    /**
     * 判断某个角色名是不是包场人。
     *
     * <p><b>刻意不写成 {@code HOST.name().equals(...)} 之外的写法</b>：
     * 与 {@code BookingStatus.occupiesSlot} 同款 —— 用 {@code equals} 逐个比，
     * 而不是 {@code Set.of(...).contains(name)}。后者在 JDK 里对 {@code null}
     * 会抛 {@link NullPointerException}，与本方法「认不出返回 false」的契约正好相反。
     *
     * @param name 角色名，可为 null
     * @return 是包场人返回 true；null 或认不出返回 false
     */
    public static boolean isHost(String name) {
        return HOST.name().equals(name);
    }
}
