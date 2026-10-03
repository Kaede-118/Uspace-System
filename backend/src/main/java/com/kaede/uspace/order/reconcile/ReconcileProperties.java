package com.kaede.uspace.order.reconcile;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对账配置，对应配置文件中的 {@code uspace.reconcile.*}。
 *
 * <p>用途与 {@code PaymentProperties}、{@code OcrProperties} 相同：
 * 把「可以调的数值」从代码里拿出来。但这里有一个额外的理由 ——
 * <b>窗口那两项会被记进批次表</b>，所以事后能回答「当时用的是哪个参数」。
 * 换成硬编码的话，改一次参数，所有历史批次都解释不清了。
 */
@Data
@Component
@ConfigurationProperties(prefix = "uspace.reconcile")
public class ReconcileProperties {

    /**
     * 账单原文件的留档目录。
     *
     * <p>⚠️ <b>绝不能落在 {@code uspace.upload.dir} 之下。</b>
     * 那个目录是<b>公开</b>的（{@code SecurityConfig.PUBLIC_PATHS} 里的
     * {@code /uploads/**} 匿名可访问 —— 用户上传的头像与截图要用 {@code <img src>}
     * 加载，而浏览器不为图片请求带 {@code Authorization} 头）。
     * 账单里含全部交易对手、备注、金额与时间，是本店最敏感的经营数据，
     * 落在那里等于挂在公网上。
     *
     * <p>这个约束由 {@link ReconcileBillStorage} 在启动时校验，重叠则<b>拒绝启动</b> ——
     * 与「空 JWT 密钥拒绝启动」同源：配置写错不会报错，只会悄悄把经营数据公开，
     * 那宁可起不来。
     *
     * <p>账单不注册静态资源映射、也不进 {@code PUBLIC_PATHS}，
     * 只能通过带鉴权的下载接口取。
     */
    private String billDir = "reconcile-bills";

    /**
     * 系统侧凭证候选窗口的下界，相对账单最早交易时间<b>往前</b>放宽几天，默认 1。
     *
     * <p>为什么要往前放宽：用户可能<b>先提交凭证、后完成付款</b>
     *（截图是上一张的、或者付款页面停留了一会儿），也可能手机时钟与服务器有偏差。
     * 不放宽的话，这些凭证会落在窗口外，既不匹配也不产生差异 —— <b>静默漏掉</b>。
     */
    private int windowBeforeDays = 1;

    /**
     * 系统侧凭证候选窗口的上界，相对账单最晚交易时间<b>往后</b>放宽几天，默认 2。
     *
     * <p>为什么比下界宽，而且两边不对称：用户当天玩完忘了传、<b>隔天才想起来</b>
     * 是常见情形，跨一个零点再加一整天，两天是够的。
     * 放宽的代价只是多查几条凭证，而漏掉一笔的代价是「账单里有钱、系统里查不到」
     * 这条差异要人工去追。
     */
    private int windowAfterDays = 2;

    /**
     * 候选凭证数超过多少条时记一条 warn，默认 5000。
     *
     * <p>不拦、只是留个痕：管理员可能传了三年的账单，那时窗口会宽得离谱，
     * 一次匹配捞出上万条凭证。不记的话，那种情况只会表现为「这次对账有点慢」，
     * 而慢的原因没有任何线索。
     */
    private int candidateWarnThreshold = 5000;
}
