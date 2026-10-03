package com.kaede.uspace.order;

import java.util.Arrays;

/**
 * 收款码属于哪个收款账号。
 *
 * <p><b>刻意不复用 {@link PaymentChannel}</b>：那个枚举描述的是「支付路由」——
 * 这笔钱走微信内 JSAPI 还是微信外 H5、要不要调网关、有没有回调；
 * 本枚举描述的是「这笔钱进了哪个收款账号」。是两件事。
 *
 * <p>混用的代价很具体：{@code WXPAY_JSAPI} 与 {@code WXPAY_H5} 在顾客眼里
 * 是同一个微信收款码（都是「我扫了码付钱」），而对账时它们又确实不是同一条流水 ——
 * 用一个枚举就必然要在某处写个 {@code if} 把它们合并回去，那个 {@code if}
 * 就是错的开始。至于 {@code ALIPAY_WAP}（手机网站支付）与「支付宝收款码」，
 * 更是一点关系都没有：前者有平台流水可查，后者只能靠用户上传的截图。
 *
 * <p>凭证上记的 {@code pay_qr_id} 指向本枚举所在表的那一行，于是
 * 「这笔钱扫的是哪张码」是可追溯的 —— 店里有多个收款账号时
 * （比如老板与老板娘各自一张微信码），对账能分得清钱进了谁的口袋。
 *
 * @see com.kaede.uspace.order.entity.PayQr
 */
public enum PayQrChannel {

    /** 微信收款码 */
    WXPAY("微信"),

    /** 支付宝收款码 */
    ALIPAY("支付宝");

    /** 面向用户与运营的中文名 */
    private final String label;

    PayQrChannel(String label) {
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
     * <p>用于校验新增 / 修改收款码的入参。与 {@link PaymentChannel#isValid} 同一套写法。
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
     * <p><b>认不出的取值原样返回</b>，与 {@link PaymentChannel#userLabelOf} 同一条契约：
     * 渠道列是 {@code VARCHAR}，库里可能存着枚举之外的字符串，
     * 让它一眼看得出来比伪装成一个正常的中文名要好。
     *
     * <p>常量在左（{@code c.name().equals(name)}）以保 null 安全 ——
     * 与 {@link PaymentChannel#isOnline} 同一套写法，不要改成 {@code Set.of(...).contains()}。
     *
     * @param name 取值，可为 null
     * @return 中文名；认不出的取值原样返回
     */
    public static String labelOf(String name) {
        return Arrays.stream(values())
                .filter(c -> c.name().equals(name))
                .map(PayQrChannel::getLabel)
                .findFirst()
                .orElse(name);
    }
}
