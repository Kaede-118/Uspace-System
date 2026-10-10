package com.kaede.uspace.order;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 支付模块配置，对应配置文件中的 {@code uspace.payment.*}。
 *
 * <p>与门锁模块（{@link com.kaede.uspace.lock.LockProperties}）是同一套思路：
 * <b>接口按真实规格设计，实现下落为模拟代码</b>，只是这里有两个开关而不是一个。
 *
 * <h3>两个开关各管一件事，不允许互相推断</h3>
 * <table border="1">
 *   <caption>配置项的分工</caption>
 *   <tr><th>配置项</th><th>管什么</th><th>谁读它</th></tr>
 *   <tr>
 *     <td>{@link #provider}</td>
 *     <td><b>代码能不能对外调用支付平台</b> —— 决定容器里装配哪个
 *         {@code PaymentGateway} 实现（{@code mock} / {@code disabled} / {@code real}）</td>
 *     <td>只有各实现类上的 {@code @ConditionalOnProperty} 注解</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #enabledChannels}</td>
 *     <td><b>本店当前收哪几种钱</b> —— 这是业务事实，不是技术开关</td>
 *     <td>{@code GET /api/payments/channels}（决定给前端展示哪些选项）
 *         与 {@code PaymentService#createPayment}（准入校验）</td>
 *   </tr>
 * </table>
 *
 * <p><b>两者不可互相替代</b>。{@code provider=mock} 而 {@code enabled-channels}
 * 里没有线上通道，是完全正常的组合：模拟收银台还在（开发与测试能用），
 * 但用户看不到那几个选项 —— 这正是 12 月投产前后的差别。
 * 反过来 {@code provider=disabled} 而 {@code enabled-channels} 里留着线上通道
 * 就是配置矛盾了：选项展示得出来，点下去必然失败。{@link #warnOnInconsistentConfig()}
 * 会在启动时把这种情况喊出来。
 *
 * <p>这与门锁模块「按通道各配一个 provider」的做法不同，不是随意为之：
 * 门锁的通道是<b>部署时就要定死的硬件形态</b>（蓝牙锁还是网关锁），
 * 而支付的通道是<b>运营决策</b>（这店收不收微信、收不收支付宝），
 * 与技术实现是否就绪是两件事。
 */
@Data
@Slf4j
@Component
@ConfigurationProperties(prefix = "uspace.payment")
public class PaymentProperties {

    /**
     * 支付实现方式，三档：
     * <ul>
     *   <li>{@code mock} —— 模拟实现，不访问外网（默认，供本机开发与答辩演示）</li>
     *   <li>{@code disabled} —— <b>没有线上支付</b>。装配一个所有方法都返回失败的
     *       {@code DisabledPaymentGatewayImpl}，让应用照常起得来。
     *       12 月投产用这一档：收款只走 {@link PaymentChannel#QR_UPLOAD}</li>
     *   <li>{@code real} —— 真实支付平台（待实现）</li>
     * </ul>
     *
     * <p><b>{@code disabled} 这一档是必需的，不是可有可无的占位。</b>
     * {@code PaymentService} 的构造器依赖一个 {@code PaymentGateway} bean，
     * 而模拟实现与将来的真实实现都挂在 {@code @ConditionalOnProperty} 上 ——
     * 少了它，把 provider 改成任何一个非 mock 的值都会让容器装配失败、
     * <b>应用直接起不来</b>，而不是「优雅地关掉支付」。
     *
     * <p>切到 {@code real} 时还需要微信商户号、API v3 密钥、商户私钥、
     * 支付宝应用私钥与公钥等凭据（个人主体办不了商户号，见建表脚本与设计文档的说明）。
     * 这些配置项等真正接入时再加，现在留空 —— 提前加一堆用不上的字段，
     * 只会让人分不清哪些是当前生效的。
     */
    private String provider = "mock";

    /**
     * 本店当前收哪几种钱，取值为 {@link PaymentChannel} 的枚举名，逗号分隔。
     *
     * <p>默认只收 {@link PaymentChannel#QR_UPLOAD}（扫码转账）——
     * <b>默认值取「安全侧」</b>：配置文件漏了这一项时，结果是「少收了钱」
     * 而不是「收了一个根本调不通的通道」。
     *
     * <p>本机开发与答辩演示时把它配成四条全开，好把三种浏览器环境各演一遍：
     * <pre>
     *   uspace.payment.enabled-channels=QR_UPLOAD,WXPAY_JSAPI,WXPAY_H5,ALIPAY_WAP
     * </pre>
     *
     * <p>用 {@code List<String>} 而不是自己 {@code split(",")}：
     * Spring Boot 原生支持逗号分隔绑到 List，手写解析会在别处长出第二份。
     * 与 {@link #provider} 不同，这里的每一项都<b>允许</b>是个非法值 ——
     * 由 {@link #warnOnInconsistentConfig()} 报警后忽略，而不是让应用起不来
     * （配置写错一个字就启不了服务，比少收一种钱严重得多）。
     */
    private List<String> enabledChannels = List.of(PaymentChannel.QR_UPLOAD.name());

    /**
     * 同一用户两次<b>成功</b>上传付款截图之间的最小间隔（如 {@code 10s}）。
     * {@code 0} 表示不限制。
     *
     * <p><b>防的是 OCR 额度被烧掉</b>：识别在上传那一刻做一次，而通用文字识别
     *（高精度版）个人认证每月 1000 次免费 —— 正常一天三十来单用不到上限，
     * 跑脚本或连点却可以。网页端与群内传图两条路共用这道冷却
     *（它们走的是同一个上传方法）。
     *
     * <p><b>为什么是 10 秒</b>：正常用户「传错了换一张」只需要几秒，
     * 取值要留出这个余地 —— 参考 QQ 写指令冷却从 20 秒降到 5 秒的那次教训：
     * 挡住的多数是正常操作时，冷却就成了纯粹的麻烦。而脚本被压到
     * 每分钟 6 次，识别额度（约每天 33 次）就算保住了。
     *
     * <p>⚠️ <b>只有【成功】的上传才计时</b>：文件不合法（不是图片、超大小）
     * 在识别之前就被挡下、不花额度，若把它也计入冷却，用户换一张合法图
     * 立刻重传会被莫名拒绝。
     */
    private Duration proofImageCooldown = Duration.ofSeconds(10);

    /** 模拟实现的参数，{@code provider=mock} 时生效 */
    private final Mock mock = new Mock();

    /**
     * 判断某个通道当前是否开放收款。
     *
     * <p>{@code createPayment} 的准入校验与 {@code /api/payments/channels} 的
     * 选项过滤都调它 —— <b>两处必须用同一个判断</b>，否则会出现
     * 「页面上选得到、点下去报错」这种最让人困惑的组合。所以判断只写在这里。
     *
     * <p><b>刻意不写成 {@code enabledChannels.contains(name)}</b>：
     * {@code payment_method} 列可空，调用方传 null 是正常情形
     * （判「这个通道开放没有」时手上那个值可能为空），
     * 而 {@code List.of(...)} 的 {@code contains(null)} 行为随实现而异。
     * 用 {@code equals} 逐个比则天生安全，与 {@link PaymentChannel#isOnline} 同一套写法。
     *
     * @param name 通道名，可为 null
     * @return 开放返回 true；null、非法名、未列入的通道都返回 false
     */
    public boolean isChannelEnabled(String name) {
        return name != null && enabledChannels.stream().anyMatch(name::equals);
    }

    /**
     * 启动时检查配置里有没有「不报错、只会静默失效」的矛盾。
     *
     * <p>检查两件事：
     * <ol>
     *   <li><b>无法识别的通道名</b> —— 拼错一个字母，那个通道就静默消失了。
     *       不打日志的话，排查时只会看到「这个选项怎么没了」</li>
     *   <li><b>网关关了、通道还开着</b> —— 选项会正常展示给用户，
     *       点下去必然失败。这是纯粹的配置矛盾</li>
     * </ol>
     *
     * <p>刻意只 WARN 不抛异常：配置问题不该让服务起不来，
     * 而且这一档在 12 月投产前后切换时很容易短暂出现。
     */
    @PostConstruct
    void warnOnInconsistentConfig() {
        for (String name : enabledChannels) {
            if (!PaymentChannel.isValid(name)) {
                log.warn("[支付] uspace.payment.enabled-channels 里有无法识别的通道名「{}」，该项会被忽略", name);
            }
        }

        boolean anyOnline = enabledChannels.stream().anyMatch(PaymentChannel::isOnline);
        if (anyOnline && !"mock".equals(provider) && !"real".equals(provider)) {
            log.warn("[支付] 配置矛盾：uspace.payment.provider={} 表示没有可用的线上支付实现，"
                            + "但 uspace.payment.enabled-channels={} 里仍有线上通道 —— "
                            + "这些选项会展示给用户，点下去必然失败。投产时应把它们去掉",
                    provider, enabledChannels);
        }
    }

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
