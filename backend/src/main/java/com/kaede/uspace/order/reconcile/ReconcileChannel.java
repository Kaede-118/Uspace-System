package com.kaede.uspace.order.reconcile;

import java.util.Arrays;

/**
 * 一次对账对的是「钱进了哪个收款账号」。
 *
 * <p>取值与建表脚本里 {@code biz_reconcile_batch.channel} 的注释一一对应。
 *
 * <p><b>刻意不复用 {@code PayQrChannel}</b>（那个只有 {@code WXPAY} / {@code ALIPAY}）：
 * 这里是「这份账单是从哪儿导出来的」，而标准模板是管理员手工整理的文件 ——
 * 它<b>根本无从判定钱进了哪个账号</b>，硬塞进微信或支付宝哪一个都是编造。
 * 加一个取值比在某个枚举上挂一个「其实不确定」的选项干净。
 *
 * <p>这与 {@code PayQrChannel} 不复用 {@code PaymentChannel} 是同一条理由：
 * 两个枚举看着像，但它们回答的问题不一样 ——
 * {@code PaymentChannel} 问「这笔钱走哪条支付路由」，{@code PayQrChannel} 问
 * 「这笔钱进了哪个收款账号」，而本枚举问「这份账单是从哪个入口导出来的」。
 * 合并的代价是必然要在某处写个 {@code if} 把它们拆回去，而那个 {@code if} 就是错的开始。
 *
 * <p><b>前端不要自己拼中文</b>：批次视图里有 {@code channelLabel} 字段，
 * 取的就是这里的 {@link #getLabel()}。
 */
public enum ReconcileChannel {

    /** 微信账单（App 里导出的 xlsx） */
    WXPAY("微信"),

    /** 支付宝账单（导出的 CSV） */
    ALIPAY("支付宝"),

    /** 系统标准模板，管理员手工整理，无法判定钱进了哪个账号 */
    STANDARD("标准模板");

    /** 面向运营的中文名 */
    private final String label;

    ReconcileChannel(String label) {
        this.label = label;
    }

    /**
     * 取中文名。
     *
     * @return 渠道的中文名称
     */
    public String getLabel() {
        return label;
    }

    /**
     * 判断一个字符串是否为合法取值。
     *
     * <p>与 {@code PaymentChannel#isValid}、{@code PaymentProofStatus#isValid}
     * 同一套写法。
     *
     * @param name 待校验的取值，可为 null
     * @return 合法返回 true；null 或不在枚举内返回 false
     */
    public static boolean isValid(String name) {
        return name != null && Arrays.stream(values()).anyMatch(c -> c.name().equals(name));
    }

    /**
     * 把取值转成中文名。
     *
     * <p><b>认不出的取值原样返回</b>，与 {@code PaymentChannel#userLabelOf} 同一条契约：
     * 库里的列是 {@code VARCHAR}，可能存着枚举之外的字符串，
     * 让它一眼看得出来比伪装成一个正常的中文名要好。
     *
     * <p>常量在左（{@code c.name().equals(name)}）以保 null 安全 ——
     * 不要改成 {@code Set.of(...).contains()}，那个对 null 会抛 NPE
     *（项目为此踩过四次，见 {@code DeviceStatus} 的类注释）。
     *
     * @param name 取值，可为 null
     * @return 中文名；认不出的取值原样返回
     */
    public static String labelOf(String name) {
        return Arrays.stream(values())
                .filter(c -> c.name().equals(name))
                .map(ReconcileChannel::getLabel)
                .findFirst()
                .orElse(name);
    }
}
