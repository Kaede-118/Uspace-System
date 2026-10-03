package com.kaede.uspace.order;

import com.kaede.uspace.order.dto.PayChannelVo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 支付通道的可用性查询 —— 用户端「有哪些付款方式可选」的唯一来源。
 *
 * <p><b>它存在的理由是「前端不该自己判」</b>。一个通道能不能出现在收银台上，
 * 取决于两件只有服务端知道的事：
 * <ol>
 *   <li><b>本店收不收这种钱</b> —— {@code uspace.payment.enabled-channels}。
 *       12 月投产时三条线上通道全部关闭，只剩扫码转账</li>
 *   <li><b>这类收款受不受理它</b> —— {@link PaymentTargetHandler#supportsChannel}。
 *       月卡与商品曾经挡掉传截图通道，因为那时人工核销接口是订单专用的</li>
 * </ol>
 *
 * <p>把这两个判断收在一处，是为了让<b>「页面上选得到」与「点下去不报错」始终等价</b>。
 * 前端若自己再判一遍（比如写死「商品不支持传截图」），两处迟早漂移 ——
 * 而漂移的表现是「选项就在那里，点了却报参数不合法」，是最难排查的一类问题。
 *
 * <p>本类与 {@code PaymentService#createPayment} 里的准入校验是<b>同一组判断的
 * 两个用途</b>：这里用来「展示」，那里用来「把关」。两者都调用
 * {@link PaymentProperties#isChannelEnabled} 与
 * {@link PaymentTargetHandler#supportsChannel}，判断依据只有一份。
 *
 * @see PayChannelVo
 */
@Service
public class PaymentChannelService {

    private final PaymentProperties properties;
    private final List<PaymentTargetHandler> handlers;

    public PaymentChannelService(PaymentProperties properties, List<PaymentTargetHandler> handlers) {
        this.properties = properties;
        this.handlers = handlers;
    }

    /**
     * 列出某类收款当前可用的支付通道。
     *
     * <p>顺序取 {@link PaymentChannel} 的声明顺序 —— 那是「历史与推荐程度」的顺序
     * （线上通道在前、扫码转账在后），前端原样渲染即可，不要自己重排。
     *
     * @param targetType 收款类型（订单 / 包场 / 月卡 / 商品）
     * @return 可用通道列表；类型为 null 或没有任何可用通道时返回<b>空列表</b>而非 null，
     *         调用方不必判空
     */
    public List<PayChannelVo> listFor(PaymentTargetType targetType) {
        List<PayChannelVo> channels = new ArrayList<>();
        if (targetType == null) {
            return channels;
        }
        for (PaymentChannel channel : PaymentChannel.values()) {
            if (!properties.isChannelEnabled(channel.name())) {
                continue;
            }
            if (!anyHandlerSupports(targetType, channel)) {
                continue;
            }
            channels.add(PayChannelVo.of(channel));
        }
        return channels;
    }

    /**
     * 判断某类收款的处理器是否受理某个通道。
     *
     * <p>用 {@code anyMatch} 而不是「找到第一个再问」：一个目标类型正常只有一个处理器，
     * 但接口没有强制这一点 —— 万一将来有人加了第二个（比如把订单再拆成
     * 「房间订单」与「包场订单」），只要有一个受理就该展示出来，
     * 而不是取决于 {@code List} 的注入顺序。
     *
     * @param type    收款类型
     * @param channel 待判断的通道
     * @return 有任一处理器受理则返回 true
     */
    private boolean anyHandlerSupports(PaymentTargetType type, PaymentChannel channel) {
        return handlers.stream()
                .filter(handler -> handler.type() == type)
                .anyMatch(handler -> handler.supportsChannel(channel));
    }
}
