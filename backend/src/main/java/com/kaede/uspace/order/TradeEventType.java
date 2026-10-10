package com.kaede.uspace.order;

import java.util.Arrays;

/**
 * 交易流水的事件类型（模块 8）。
 *
 * <p>四种事件覆盖一笔收款从「有人交了凭证」到「钱被认下或退回去」的全程，
 * 外加未付款单被取消这一种<b>没有钱发生、但同样是交易事实</b>的记录：
 *
 * <pre>
 *   PROOF_SUBMITTED   提交付款凭证   —— 四类收款都有
 *   PAY_RECEIVED      收款到账       —— 「提交即落账」的订单/商品在提交那一刻就到账；
 *                                        包场/月卡在管理员复核通过时到账；
 *                                        线上通道由回调落账（来源 SYSTEM）
 *   PROOF_REJECTED    凭证驳回       —— 管理员判这笔钱不认，交付随之退回
 *   CANCEL_UNPAID     取消未付款单   —— 单子没付钱就被关掉（用户取消 / 群里取消 /
 *                                        管理员在后台取消）
 * </pre>
 *
 * <p>⚠️ <b>「复核通过」刻意没有单独一类</b>：对包场与月卡来说，「复核通过」与
 * 「收款到账」是同一件事（邀请令牌与月卡都产生在落账那一步），
 * 分成两条会让同一笔钱在同一时刻留下两行、金额还要乘二。
 * 若要看「谁复核的」，流水行上的 {@code remark} 记着「管理员复核通过」，
 * 而凭证那边另有 {@code verified_by} / {@code verified_at} 两列存权威值。
 *
 * <p>取值是封闭的四种，库里存枚举名（{@code VARCHAR}，无库级约束）。
 */
public enum TradeEventType {

    /** 用户提交了付款凭证（网页端或群内传图） */
    PROOF_SUBMITTED("提交付款凭证"),

    /** 收款到账 —— 与目标上的 {@code paid_at} 是同一时刻，这里只是留一份流水 */
    PAY_RECEIVED("收款到账"),

    /** 付款凭证被管理员驳回 */
    PROOF_REJECTED("凭证驳回"),

    /** 未付款单被取消（单子关掉了，钱一分没动） */
    CANCEL_UNPAID("取消未付款单"),

    /**
     * 账单对账确认（2026-10-10 加，由用户提出要区分）。
     *
     * <p>⚠️ <b>它与 {@link #PAY_RECEIVED} 是两件事，不要合并</b>：
     * 「收款到账」说的是<b>系统按自己的记录认了这笔钱</b> —— 有人传了凭证，
     * 或者机器识别到交易单号就放行了；而这一条说的是
     * <b>「收款账号导出的账单里也真有这一笔」</b>。
     * 前者可能只是没人细看，后者才是钱确实进了口袋。
     *
     * <p>所以小额收款「识别到单号即免人工复核」<b>不产生这一条</b>：
     * 免复核只是为了少让管理员看几眼，它不构成「与账单对上过」。
     * 这一条只在管理员上传账单跑完对账、某笔凭证在账单里被认领时写入。
     */
    RECONCILED("账单对账确认");

    /** 面向人的中文说明，供后台展示 */
    private final String label;

    TradeEventType(String label) {
        this.label = label;
    }

    /**
     * 取中文说明。
     *
     * @return 事件的中文名称
     */
    public String getLabel() {
        return label;
    }

    /**
     * 判断一个字符串是否为合法取值。
     *
     * @param name 待校验的名字，可为 null
     * @return 合法返回 true；null 或不在枚举内返回 false
     */
    public static boolean isValid(String name) {
        return name != null && Arrays.stream(values()).anyMatch(t -> t.name().equals(name));
    }

    /**
     * 把名字转成中文说明，认不出的原样返回。
     *
     * @param name 名字，可为 null
     * @return 中文说明；认不出时原样返回
     */
    public static String labelOf(String name) {
        return isValid(name) ? valueOf(name).getLabel() : name;
    }
}
