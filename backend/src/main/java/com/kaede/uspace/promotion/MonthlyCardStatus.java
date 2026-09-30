package com.kaede.uspace.promotion;

import java.util.Arrays;

/**
 * 月卡状态。取值与建表脚本里 {@code biz_monthly_card.status} 的注释一一对应。
 *
 * <p>状态流转只有两跳：
 * <pre>
 *   （支付成功 → 生成卡即进入）ACTIVE 生效中
 *          │
 *       到期日已过（定时任务翻转）
 *          ↓
 *      EXPIRED 已过期
 *
 *   ACTIVE ──管理员人工退款──> REFUNDED 已退款
 * </pre>
 *
 * <p><b>为什么没有「待支付」</b>：还没付钱的卡不是卡。购买尝试记在
 * {@code biz_monthly_card_order} 里（状态见 {@link CardOrderStatus}），
 * 付成功了才往本表插一张卡。合成一张表的话，
 * {@code start_date} / {@code end_date} 就得放开为可空 ——
 * 而那是「卡」的核心属性，让核心属性可空说明表里混进了不是卡的东西。
 *
 * <p><b>过期与否以 {@code end_date} 为准，状态只是给人看的</b>：
 * 免单判定同时校验状态与日期，所以定时任务漏跑（服务停过）也不会
 * 出现「过期卡还在免单」。反过来，把卡置成 {@link #REFUNDED}
 * 会立刻停止免单 —— 这正是日期之外还要判状态的理由。
 */
public enum MonthlyCardStatus {

    /** 生效中。有效期内免费使用 */
    ACTIVE("生效中"),

    /** 已过期。到期日已过，由定时任务翻转 */
    EXPIRED("已过期"),

    /** 已退款。走管理员人工审批，资金线下退 */
    REFUNDED("已退款");

    /** 面向用户的中文说明，供前端展示 */
    private final String label;

    MonthlyCardStatus(String label) {
        this.label = label;
    }

    /**
     * 取中文说明。
     *
     * @return 状态的中文名称
     */
    public String getLabel() {
        return label;
    }

    /**
     * 判断一个字符串是否为合法状态名。
     *
     * @param name 待校验的状态名，可为 null
     * @return 合法返回 true；null 或不在枚举内返回 false
     */
    public static boolean isValid(String name) {
        return name != null && Arrays.stream(values()).anyMatch(s -> s.name().equals(name));
    }

    /**
     * 把状态名转成中文说明。
     *
     * <p>认不出的状态名<b>原样返回</b>，让异常数据在界面上一眼看得出来。
     *
     * @param name 状态名，可为 null
     * @return 中文说明；认不出时原样返回
     */
    public static String labelOf(String name) {
        return isValid(name) ? valueOf(name).getLabel() : name;
    }
}
