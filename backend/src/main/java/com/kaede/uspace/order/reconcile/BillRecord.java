package com.kaede.uspace.order.reconcile;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 账单里的一笔收款。
 *
 * <p>这是解析层的产出、匹配层的输入 —— 它已经<b>脱离了文件的形态</b>：
 * 不管是微信的 xlsx、支付宝的 CSV 还是标准模板，到了这一步都是同一副样子。
 * {@link ReconcileMatcher} 完全不知道它从哪来。
 *
 * @param paymentNo    归一化后的交易单号（见 {@link ReconcileTexts#normalize}），
 *                     匹配用的就是它。<b>保证非空</b> —— 没有单号的行在解析时就被跳过了
 * @param rawPaymentNo 原样的单号，保留下来是为了「写进差异记录让人看」：
 *                     归一化过的值可能多了大写的转换，管理员对着账单核时
 *                     看到的应该是他自己文件里的那一串
 * @param amount       金额（元），{@code BigDecimal} 且标度为 2。
 *                     <b>保证非空</b> —— 没有金额的行在解析时就被跳过了
 * @param tradedAt     账单上记的交易时刻，<b>可为 null</b>：
 *                     时间格式认不出来时不该把整笔记录丢掉（丢一笔收款
 *                     比窗口算不准严重得多），只是它不参与窗口起止的计算
 * @param summary      商品 / 备注，只用于人工看差异时多一个上下文
 */
public record BillRecord(
        String paymentNo,
        String rawPaymentNo,
        BigDecimal amount,
        LocalDateTime tradedAt,
        String summary) {
}
