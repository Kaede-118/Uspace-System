package com.kaede.uspace.order;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 支付模块配置，对应配置文件中的 {@code uspace.payment.*}。
 *
 * <p>与门锁模块（{@link com.kaede.uspace.lock.LockProperties}）是同一套思路：
 * <b>接口按真实规格设计，实现下落为模拟代码</b>，用 {@link #provider} 切换。
 *
 * <p><b>但开关只有两档，不像门锁那样按通道各配一个</b> —— 因为
 * <b>通道是运行时按用户所处的浏览器环境决定的，不是部署时的配置项</b>。
 * 微信内走 JSAPI、微信外走 H5、支付宝内走 WAP，同一个部署要同时支持三条，
 * 所以只有「整体用模拟实现还是真实实现」这一个维度需要配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "uspace.payment")
public class PaymentProperties {

    /**
     * 支付实现方式：{@code mock} 模拟实现（不访问外网，默认）/ {@code real} 真实支付平台。
     *
     * <p>模拟实现要能脱离外网跑通<b>全部三条通道</b>的完整流程 ——
     * 包括「拿到 prepay_id / h5_url / form HTML → 模拟用户在微信或支付宝完成支付
     * → 回调后端 → 订单转 PAID」。演示时可在页面上直接切换通道，
     * 把三种浏览器环境各演一遍，不必真的准备三个环境。
     *
     * <p>切到 {@code real} 时还需要微信商户号、API v3 密钥、商户私钥、
     * 支付宝应用私钥与公钥等凭据（个人主体办不了商户号，见建表脚本与设计文档的说明）。
     * 这些配置项等真正接入时再加，现在留空 —— 提前加一堆用不上的字段，
     * 只会让人分不清哪些是当前生效的。
     */
    private String provider = "mock";

    /** 模拟实现的参数，{@code provider=mock} 时生效 */
    private final Mock mock = new Mock();

    /**
     * 模拟实现的参数。
     *
     * <p>与门锁的模拟实现同理：<b>必须能注入故障，否则异常处理分支在演示时永远走不到</b>。
     * 支付链路上最需要演示的两个分支是「网关调用失败」与「回调丢失」。
     *
     * <p>刻意声明为 {@code final} 且就地初始化：Lombok 不生成 setter，
     * 「这个对象永远不为 null」由编译期保证。Spring Boot 对嵌套配置的绑定
     * 是在这个既有对象上设属性，不需要外层 setter。
     */
    @Data
    public static class Mock {

        /**
         * 模拟网关调用失败概率，取值 0~1。默认 0（从不失败）。
         *
         * <p>影响下单（发起支付）与查单。演示异常处理时调到 0.3 表示三成概率失败 ——
         * 订单会收到 {@code PAYMENT_GATEWAY_UNAVAILABLE}(502)，
         * 而不是一个语焉不详的 500。
         */
        private double failureRate = 0;

        /**
         * 模拟回调投递丢失概率，取值 0~1。默认 0（必达）。
         *
         * <p><b>设为 1 可以稳定复现「钱付了、回调没到」</b> —— 这是生产环境的必然事件
         * （网络抖动、平台重试耗尽、服务重启窗口），也是本模块必须提供
         * 「主动查单」补偿路径的理由。调成 1 后支付成功但订单仍停在待支付，
         * 点一下查单就补上，整个补偿链路在演示中走得通。
         */
        private double notifyDropRate = 0;

        /** 模拟单次调用的网络延迟（毫秒）。调大可观察前端加载态与超时处理 */
        private long latencyMillis = 0;

        /**
         * 模拟支付接口的路径，会作为 {@code mockPayUrl} 返回给前端。
         *
         * <p><b>它指向的是一个 API，不是页面</b>：真实场景下用户被 {@code h5_url}
         * 或 form 表单带到微信 / 支付宝的页面上完成付款，而模拟环境里没有那个页面。
         * 前端拿到非空的 {@code mockPayUrl} 就知道「这是模拟模式」，
         * 于是弹出自己的模拟收银台，由它去调这个接口。
         *
         * <p>做成 API 而不是后端渲染的收银台页面，是为了<b>不必为演示开一个匿名路径</b> ——
         * 模拟支付同样要带 JWT 并校验支付单归属，否则任何登录用户
         * 都能把别人的订单标记成已支付。
         */
        private String payPath = "/api/payments/mock/pay";
    }
}
