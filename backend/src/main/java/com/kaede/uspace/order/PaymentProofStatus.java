package com.kaede.uspace.order;

import java.util.Arrays;

/**
 * 付款凭证的复核状态。
 *
 * <p>取值与建表脚本里 {@code biz_payment_proof.verify_status} 的注释一一对应。
 *
 * <p><b>三态而不是两态</b>：{@code CONFIRMED}（已核对）与 {@code REJECTED}（未通过）
 * 都表示「管理员已经看过了」，但去向完全不同 —— 前者是这笔钱认了，
 * 后者是这笔钱不认、要用户说明或重新提交。合成一个「已处理」的话，
 * 后台就再也分不出哪些单子的钱是收到过的。
 *
 * <p><b>驳回之后凭证不会消失</b>（{@code biz_payment_proof} 是 append-only 的，
 * 见 {@code PaymentProof} 的类注释）：重新提交走的是 UPDATE，
 * 把状态翻回 {@code SUBMITTED} 并清掉驳回原因。这样「这条凭证的一生」
 * 不留档，换来的是提交动作天然幂等 —— 手机上连点两下不会产生两条凭证。
 * 对账要的那份「最终结论」，{@code CONFIRMED} 这一条记录就够了。
 */
public enum PaymentProofStatus {

    /** 已提交，等待管理员复核。默认状态 */
    SUBMITTED("待复核"),

    /** 管理员已核对到账。凭证的终点状态，此后不再改写 */
    CONFIRMED("已核对"),

    /**
     * 管理员核对后发现有问题（金额对不上、截图看不出是这笔、重复使用同一张图）。
     *
     * <p>此时凭证留在库里供追溯，但复核结论是「这笔钱不认」。
     * 目标若属于「提交即交付」类（订单 / 商品），可能已经交付过了 ——
     * 那只能人工回退，后台会据此把这类条目显著标出。
     */
    REJECTED("未通过");

    /** 面向运营的中文名 */
    private final String label;

    PaymentProofStatus(String label) {
        this.label = label;
    }

    /**
     * 取中文名。
     *
     * @return 状态的中文名称
     */
    public String getLabel() {
        return label;
    }

    /**
     * 判断一个字符串是否为合法取值。
     *
     * <p>用于校验后台复核的入参。与 {@link PaymentChannel#isValid} 同一套写法。
     *
     * @param name 待校验的取值，可为 null
     * @return 合法返回 true；null 或不在枚举内返回 false
     */
    public static boolean isValid(String name) {
        return name != null && Arrays.stream(values()).anyMatch(s -> s.name().equals(name));
    }

    /**
     * 把取值转成中文名。
     *
     * <p><b>认不出的取值原样返回</b>，与 {@link PaymentChannel#userLabelOf} 同一条契约：
     * 状态列是 {@code VARCHAR}，库里可能存着枚举之外的字符串，
     * 让它一眼看得出来比伪装成一个正常的中文名要好。
     *
     * <p>常量在左（{@code s.name().equals(name)}）以保 null 安全 ——
     * 与 {@link PaymentChannel#isOnline} 同一套写法，不要改成 {@code Set.of(...).contains()}。
     *
     * @param name 取值，可为 null
     * @return 中文名；认不出的取值原样返回
     */
    public static String labelOf(String name) {
        return Arrays.stream(values())
                .filter(s -> s.name().equals(name))
                .map(PaymentProofStatus::getLabel)
                .findFirst()
                .orElse(name);
    }
}
