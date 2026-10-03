package com.kaede.uspace.order.dto;

import com.kaede.uspace.order.PaymentChannel;
import lombok.Data;

/**
 * 一个可供用户选择的支付通道。
 *
 * <p>它是 {@code GET /api/payments/channels} 的元素，<b>由后端算好、前端照着渲染</b>：
 * 哪些通道开着（{@code uspace.payment.enabled-channels}）、
 * 这类收款支不支持它（{@code PaymentTargetHandler#supportsChannel}），
 * 两个判断都只有服务端知道。前端拿到列表直接铺成选项即可，
 * <b>不要自己再判一遍</b> —— 判重了就会出现「页面上选得到、点下去报错」。
 *
 * <p>刻意<b>不返回</b>的几样东西：
 * <ul>
 *   <li><b>emoji 图标</b> —— 那是纯视觉，与业务无关，放前端</li>
 *   <li><b>按浏览器环境的预选结果</b> —— 依赖 User-Agent，只有前端拿得到</li>
 * </ul>
 * 与 {@code utils/labels.js} 那条「配色没有第二个来源」是同一条边界：
 * 服务端给事实，客户端给呈现。
 *
 * @see com.kaede.uspace.order.PaymentChannelService
 */
@Data
public class PayChannelVo {

    /** 通道名，取值为 {@link PaymentChannel} 的枚举名，提交支付时原样回传 */
    private String channel;

    /**
     * 面向用户的支付方式名（如「微信支付」「扫码转账」）。
     *
     * <p>用的是 {@link PaymentChannel#getUserLabel()} 而不是技术口径的
     * {@code getLabel()} —— 用户不需要知道 JSAPI 与 H5 的区别。
     */
    private String label;

    /**
     * 这条通道是否需要用户上传付款凭证。
     *
     * <p>前端据此决定走哪套收银台：{@code true} 弹上传凭证的面板，
     * {@code false} 走原有的线上支付流程（拉起微信/支付宝）。
     */
    private boolean needProof;

    /**
     * 是否线上通道。
     *
     * <p>与 {@link #needProof} <b>在当前取值下互补，但语义不同</b>，
     * 两个都要留着：线上通道决定「要不要向平台发起支付、要不要轮询查单」，
     * 而 {@code needProof} 决定「要不要展示上传界面」。
     * 将来若加一条「现金」通道（管理员线下收款），它<b>既非线上、也不需要凭证</b>，
     * 那时只有一个字段就不够用了。
     */
    private boolean online;

    /**
     * 由枚举值构造一个选项。
     *
     * @param channel 通道枚举，不可为 null
     * @return 对应的视图对象
     */
    public static PayChannelVo of(PaymentChannel channel) {
        PayChannelVo vo = new PayChannelVo();
        vo.setChannel(channel.name());
        vo.setLabel(channel.getUserLabel());
        vo.setNeedProof(channel.requiresProof());
        vo.setOnline(PaymentChannel.isOnline(channel.name()));
        return vo;
    }
}
