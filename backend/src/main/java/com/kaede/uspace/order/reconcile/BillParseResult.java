package com.kaede.uspace.order.reconcile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 一份账单解析完的结果。
 *
 * <p>三个数字是给管理员看的「这个文件读得对不对」的第一手依据 ——
 * 上传成功后页面上要显示它们，管理员据此判断自己有没有传错文件。
 *
 * @param channel     这份账单属于哪个渠道，由「哪个解析器认出了它」定下来。
 *                    它会写进批次的 {@code channel} 列 —— 判定依据是表头，
 *                    所以真实账单的列名一旦与预期不符，这里就可能降级成
 *                    {@code STANDARD}。<b>那只影响报表归类，不影响对账结果</b>，
 *                    因为三种格式共用同一套解析逻辑
 * @param records       解析出的收入记录
 * @param excludedCount <b>未参与对账</b>的笔数，两类合在一起：收支方向不是「收入」的
 *                      （支出与「不计收支」），以及交易类型不在白名单里的
 *                      （个人账单里的转账、红包、别处买东西的退款）。
 *                      <b>只计数、不参与匹配</b> —— 它们每一笔都找不到对应凭证，
 *                      放进去只会变成一屏没有意义的差异。
 *                      <p>两类合并成一个数，是因为它们对管理员是同一件事：
 *                      「这些行系统没管」。分成两个数反而要他在两个相近的数字之间猜
 * @param excludedTypes 被交易类型白名单挡下的类型（去重、按出现顺序）。
 *                      <b>只在「一条记录都没有」的报错里用到</b> ——
 *                      那正是白名单该补什么的第一手依据，见
 *                      {@code ReconcileService} 的 {@code RECONCILE_BILL_EMPTY} 分支
 * @param skippedRows   认不出的行数（金额或单号缺失、状态缺失）。
 *                      也要显示给管理员：跳过了 20 行而他一无所知的话，
 *                      「账单里有 87 笔、系统只认了 67 笔」这件事就永远说不清了
 */
public record BillParseResult(
        ReconcileChannel channel,
        List<BillRecord> records,
        int excludedCount,
        List<String> excludedTypes,
        int skippedRows) {

    /**
     * 账单里最早的一笔交易时刻。
     *
     * <p>对账窗口的下界由它推出。交易时刻为 null 的记录不参与计算 ——
     * 这个值只影响窗口的宽窄，而窗口宽窄只影响「有没有把该比的凭证捞进来」，
     * 拿一个缺失的时刻去污染它反而是错的。
     *
     * @return 最早时刻；一条带时刻的记录都没有时返回 null
     */
    public LocalDateTime minTradedAt() {
        return records.stream()
                .map(BillRecord::tradedAt)
                .filter(java.util.Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElse(null);
    }

    /**
     * 账单里最晚的一笔交易时刻。
     *
     * @return 最晚时刻；一条带时刻的记录都没有时返回 null
     */
    public LocalDateTime maxTradedAt() {
        return records.stream()
                .map(BillRecord::tradedAt)
                .filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
    }

    /**
     * 收入合计（元）。
     *
     * @return 各笔金额之和，标度为 2
     */
    public BigDecimal totalAmount() {
        return records.stream()
                .map(BillRecord::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * 有没有可对账的记录。
     *
     * <p>服务层据此报 {@code RECONCILE_BILL_EMPTY}：<b>空账单必须报错，
     * 不能静默建成一个「0 笔、0 差异」的批次</b> ——
     * 那会让管理员以为「对完了、没问题」。
     *
     * @return 一条收入记录都没有时返回 true
     */
    public boolean isEmpty() {
        return records.isEmpty();
    }
}
