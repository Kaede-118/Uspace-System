package com.kaede.uspace.order;

import java.util.Arrays;

/**
 * 支付通道。
 *
 * <p>取值与建表脚本里 {@code biz_order.payment_method} 的注释一一对应。
 *
 * <p><b>通道由前端按用户所处的浏览器环境预选，用户仍可手动切换</b> ——
 * 因为<b>微信内打不开支付宝、支付宝内打不开微信</b>（双方互相屏蔽外链），
 * 预选错了必然出现「选了却调不起来」的死路。判断依据是 User-Agent：
 * 含 {@code MicroMessenger} → 微信内；含 {@code AlipayClient} → 支付宝内；
 * 其余 → 微信外手机浏览器。做成「预选 + 可改」而不是「自动跳转且不可改」，
 * 是因为「微信里想用支付宝」是常见需求（零钱不够、有红包）。
 *
 * <p><b>本店实际收哪几种钱由 {@code uspace.payment.enabled-channels} 决定</b>，
 * 前端能选到的选项取自 {@code GET /api/payments/channels}，不要在页面里写死 ——
 * 12 月投产时三条线上通道全部关闭，只剩 {@link #QR_UPLOAD}。
 *
 * <p>三个线上通道共用同一套回调处理与订单状态机，只是协议不同：
 * <ul>
 *   <li>微信的 JSAPI 与 H5 共用一个 {@code notify_url}（同一个 API v3 协议）</li>
 *   <li>支付宝的异步通知因协议不同（表单验签 vs. 微信的 RSA 验签 + AES 解密）
 *       单独一个端点</li>
 * </ul>
 * {@code payment_method} 列记下具体走的哪条通道，对账时能区分来源。
 */
public enum PaymentChannel {

    /** 微信内浏览器 → JSAPI 支付。下单返回 {@code prepay_id}，前端用 WeixinJSBridge 调起 */
    WXPAY_JSAPI("微信内浏览器", "微信支付"),

    /** 微信外的手机浏览器 → H5 支付。下单返回 {@code h5_url}，前端跳转拉起微信 App */
    WXPAY_H5("微信外手机浏览器", "微信支付"),

    /** 支付宝内置浏览器 / 支付宝场景 → 手机网站支付（WAP）。下单返回一段自动提交的 form HTML */
    ALIPAY_WAP("支付宝手机网站支付", "支付宝"),

    /**
     * 扫码转账 + 上传付款截图 + 管理员复核。
     *
     * <p><b>2026-09-30 起它是 12 月投产的唯一收款方式</b>，不再是「降级路径」：
     * 微信 H5 支付的产品定位排除了纯线下服务收款场景，JSAPI 要认证服务号或小程序
     * 且每年有年费，支付宝 WAP 需企业账号 —— 三条线上通道在投产时都走不通。
     * 于是改为「商家收款码 + 用户上传付款截图 + OCR 识别流水号 + 管理员复核 +
     * 每日导出账单对账」，以信任制为主（南沙市场小，做熟人生意居多）。
     *
     * <p>它<b>不是线上通道</b>：不调网关、不产生支付平台交易号，也不需要回调。
     * 凭证与对账的完整设计见 {@code biz_payment_proof} 与设计文档第九章。
     */
    QR_UPLOAD("传截图人工核销", "扫码转账");

    /**
     * 技术口径的中文说明，供管理后台与日志使用。
     *
     * <p>它说明的是<b>这笔钱从哪个入口进来的</b> ——「微信内浏览器」与
     * 「微信外手机浏览器」在对账时是两条不同的流水来源，不能混为一谈。
     */
    private final String label;

    /**
     * 用户口径的支付方式名，供用户端展示。
     *
     * <p>用户不需要知道 JSAPI 与 H5 的区别 —— 在他那里都是「微信支付」。
     * <b>两个口径刻意分开</b>：合成一个的话，要么用户看到「微信外手机浏览器」
     * 这种莫名其妙的措辞，要么管理员对账时看不出这笔钱从哪个入口来。
     */
    private final String userLabel;

    PaymentChannel(String label, String userLabel) {
        this.label = label;
        this.userLabel = userLabel;
    }

    /**
     * 取技术口径的中文说明。
     *
     * @return 通道的中文名称（如「微信内浏览器」）
     */
    public String getLabel() {
        return label;
    }

    /**
     * 取用户口径的支付方式名。
     *
     * @return 用户端展示用的名称（如「微信支付」）
     */
    public String getUserLabel() {
        return userLabel;
    }

    /**
     * 这条通道是否要求用户上传付款凭证。
     *
     * <p>目前只有 {@link #QR_UPLOAD} 需要。
     *
     * <p><b>单独抽成方法，而不是让前端判 {@code channel === 'QR_UPLOAD'}</b>：
     * 「哪条通道要传凭证」是业务定义的一部分。将来若加一条「现金」通道
     * （管理员线下收款），它同样不是线上通道、却<b>不需要</b>用户传凭证 ——
     * 那时所有 {@code !isOnline} 形式的判断都会集体出错，
     * 而问一句「这条通道要凭证吗」永远是准的。
     *
     * @return 需要上传付款凭证返回 true
     */
    public boolean requiresProof() {
        return this == QR_UPLOAD;
    }

    /**
     * 把通道名转成用户口径的中文说明。
     *
     * <p>给「已知一个 {@code payment_method} 字符串、要显示给用户看」的场景用：
     * 各类 VO 的 {@code paymentMethodLabel} 字段都由它生成，用户端因此不需要
     * 自己维护一张「通道名 → 中文」的映射表 —— 那样的一张表迟早与枚举漂移，
     * 而且新增通道时一定会漏改（2026-09-30 之前就有两份，散在订单详情页与后台里）。
     *
     * <p><b>认不出的取值原样返回</b>，与 {@code OrderStatus.labelOf} 同一条契约：
     * 通道列是 {@code VARCHAR}，库里可能存着枚举之外的字符串，让它一眼看得出来，
     * 比伪装成一个正常的中文通道名要好。
     *
     * <p><b>刻意不写成 {@code c -> name.equals(c.name())}</b>：{@code name} 可为 null
     * （尚未支付或 0 元结清的订单读出来就是 null），常量在左才能让 null 安全地
     * 落到「认不出」这一支，而不是抛 NPE。与 {@link #isOnline(String)} 同一套写法。
     *
     * @param name 通道名，可为 null
     * @return 用户口径的中文名；认不出的取值原样返回
     */
    public static String userLabelOf(String name) {
        return Arrays.stream(values())
                .filter(c -> c.name().equals(name))
                .map(PaymentChannel::getUserLabel)
                .findFirst()
                .orElse(name);
    }

    /**
     * 判断一个字符串是否为合法通道名。
     *
     * <p>用于校验发起支付的入参。非法值会让 Jackson 反序列化直接失败（走 400），
     * 但本方法仍需要 —— 从回调报文里取通道名时没有 Jackson 把关，
     * 那里拿到的是支付平台给的字符串。
     *
     * @param name 待校验的通道名，可为 null
     * @return 合法返回 true；null 或不在枚举内返回 false
     */
    public static boolean isValid(String name) {
        return name != null && Arrays.stream(values()).anyMatch(c -> c.name().equals(name));
    }

    /**
     * 判断某个通道是否属于「线上通道」。
     *
     * <p>线上通道要调支付网关下单、要等回调；{@link #QR_UPLOAD} 不走网关，
     * 发起支付时不该去调网关（调了必然失败）。
     *
     * <p><b>刻意不写成 {@code Set.of(...).contains(name)}</b>：JDK 的不可变集合
     * 对 {@code null} 查询会抛 {@link NullPointerException}（它们用
     * {@code requireNonNull} 挡住 null），而本方法的契约是「不是线上通道就返回 false」。
     * 用 {@code equals} 逐个比则天生安全 —— {@code payment_method} 列可空，
     * 0 元结清或尚未支付的订单在读到它时就是 null，
     * 那时问一句「这是不是线上通道」是正常调用，不该崩。
     *
     * <p>与 {@code DeviceStatus.isUsable} 是同一套写法，改动时两处一起改。
     *
     * @param name 通道名，可为 null
     * @return 线上通道返回 true；null、{@link #QR_UPLOAD} 或认不出的取值返回 false
     */
    public static boolean isOnline(String name) {
        return WXPAY_JSAPI.name().equals(name)
                || WXPAY_H5.name().equals(name)
                || ALIPAY_WAP.name().equals(name);
    }
}
