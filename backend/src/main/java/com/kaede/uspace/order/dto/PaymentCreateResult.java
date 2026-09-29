package com.kaede.uspace.order.dto;

import lombok.Data;

/**
 * 向支付平台下单的结果。
 *
 * <p><b>三个通道返回的东西完全不一样</b>，所以三个字段并存、按通道二选一有值：
 * <table border="1">
 *   <caption>各通道的下单产物</caption>
 *   <tr><th>通道</th><th>产物</th><th>前端怎么用</th></tr>
 *   <tr><td>微信 JSAPI</td><td>{@code prepayId}</td>
 *       <td>{@code WeixinJSBridge.invoke('getBrandWCPayRequest', ...)}</td></tr>
 *   <tr><td>微信 H5</td><td>{@code h5Url}</td>
 *       <td>{@code location.href = h5Url} 拉起微信 App</td></tr>
 *   <tr><td>支付宝 WAP</td><td>{@code formHtml}</td>
 *       <td>把这段 HTML 插进页面并提交表单，拉起支付宝 App</td></tr>
 * </table>
 *
 * <p>另外还有一个 {@code mockPayUrl} 只在模拟实现下出现，指向模拟收银台。
 */
@Data
public class PaymentCreateResult {

    /** 本次调用是否成功。失败时看 {@link #errmsg} */
    private boolean success;

    /** 平台错误码。微信与支付宝各有自己的编码体系，透传以便排障 */
    private Integer errcode;

    /** 错误描述 */
    private String errmsg;

    /**
     * 微信 JSAPI 的预支付交易会话标识。
     *
     * <p><b>有效期 2 小时</b>，过期需用原参数重新下单；未支付时可多次拉起支付。
     * 这也是「不要在订单创建时就下单、而应在用户点『去支付』时才下单」的原因 ——
     * 提前下单只会让它在用户还没决定付款时就白白过期。
     */
    private String prepayId;

    /**
     * 微信 H5 支付的跳转链接。
     *
     * <p><b>有效期只有 5 分钟</b>（比 Native 的 2 小时短得多），
     * 且官方明确<b>严禁篡改、拆分或截断</b>该链接；要指定支付后跳回的页面，
     * <b>只能</b>在末尾拼接一个 {@code redirect_url} 参数，不能再加别的。
     */
    private String h5Url;

    /**
     * 支付宝手机网站支付返回的一段自动提交的表单 HTML。
     *
     * <p>支付宝这个接口不给链接，给的是一整段带签名的表单 ——
     * 前端把它插入页面并提交，浏览器就会带着表单去拉起支付宝 App。
     */
    private String formHtml;

    /**
     * 模拟收银台的地址（仅 {@code provider=mock} 时非空）。
     *
     * <p>它模拟的是「支付平台的收银台页面」：真实场景下用户被
     * {@code h5Url} 或 form 表单带到微信 / 支付宝的页面上完成付款，
     * 演示时则跳到这里点一下「模拟支付」。
     */
    private String mockPayUrl;

    /**
     * 构造一个成功结果。
     *
     * @return 成功的结果对象
     */
    public static PaymentCreateResult ok() {
        PaymentCreateResult result = new PaymentCreateResult();
        result.setSuccess(true);
        return result;
    }

    /**
     * 构造一个失败结果。
     *
     * @param errcode 平台错误码，可为 null
     * @param errmsg  错误描述
     * @return 失败的结果对象
     */
    public static PaymentCreateResult fail(Integer errcode, String errmsg) {
        PaymentCreateResult result = new PaymentCreateResult();
        result.setSuccess(false);
        result.setErrcode(errcode);
        result.setErrmsg(errmsg);
        return result;
    }
}
