package com.kaede.uspace.order.dto;

import com.kaede.uspace.order.entity.ReconcileBatch;
import com.kaede.uspace.order.reconcile.ReconcileChannel;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 后台对账批次列表里的一条。
 *
 * <p><b>管理员在这一页上要回答两个问题</b>，字段就是围着它们组织的：
 * <ol>
 *   <li><b>「这次对得怎么样」</b> —— {@link #matchedCount} 与 {@link #diffCount}
 *       一对，配 {@link #unhandledCount}（还有几条没处理）</li>
 *   <li><b>「我有没有传错文件」</b> —— {@link #billCount} / {@link #billAmount} / {@link #periodStart}
 *       是账单侧的自己报的数，管理员一眼就该认出「这不是我导的那份」</li>
 * </ol>
 *
 * <p><b>{@link #billSkippedCount} 与 {@link #proofSkippedCount} 是防「看起来坏了」的</b>：
 * 重传一份已经对过的账单时，匹配数与差异数都会是 0 —— 没有这两个数，
 * 管理员会以为系统坏了，而实际上系统正确地什么都没做。
 *
 * <p><b>{@link #windowStart} / {@link #windowEnd} 要显示在页面上</b>：
 * 它们回答了「为什么某条凭证没被算进来」—— 那笔账过去之后，
 * 光看一条批次记录是推不回去的（窗口是配置项算出来的）。
 */
@Data
public class ReconcileBatchVo {

    /** 批次 ID */
    private Long id;

    /** 钱从哪导出来的，取值为 {@code ReconcileChannel} 的枚举名 */
    private String channel;

    /** 渠道的中文名（微信 / 支付宝 / 标准模板） */
    private String channelLabel;

    /** 上传时的原始文件名 */
    private String fileName;

    /** 账单内容里最早的交易时间，可空 */
    private LocalDateTime periodStart;

    /** 账单内容里最晚的交易时间，可空 */
    private LocalDateTime periodEnd;

    /** 系统侧凭证的候选窗口下界，可空 */
    private LocalDateTime windowStart;

    /** 系统侧凭证的候选窗口上界，可空 */
    private LocalDateTime windowEnd;

    /** 账单侧解析出的「收入且成功」笔数 */
    private Integer billCount;

    /** 账单侧收入合计（元） */
    private BigDecimal billAmount;

    /**
     * 账单里未参与对账的笔数（方向不是收入 + 交易类型不在白名单里）。
     *
     * <p>与 {@link #billCount} 并列显示才有意义：一份个人收款账单上写着收了 47764 元，
     * 而系统里只认了 6 元 —— 差额全在这里。没有它，管理员只看得到「账单 3 笔」，
     * 连那个文件原本有 40 行都不知道。
     */
    private Integer billExcludedCount;

    /** 账单侧因「已被之前的批次认领」而跳过的笔数 */
    private Integer billSkippedCount;

    /** 系统侧参与比对的凭证数 */
    private Integer proofCount;

    /** 系统侧参与比对的凭证金额合计（元） */
    private BigDecimal proofAmount;

    /** 落在窗口内但已被之前批次认领、本次跳过的凭证数 */
    private Integer proofSkippedCount;

    /** 匹配成功的笔数 */
    private Integer matchedCount;

    /** 匹配成功的金额合计（元） */
    private BigDecimal matchedAmount;

    /** 本次写入的差异条数（已去重） */
    private Integer diffCount;

    /**
     * 这个批次还有几条差异没处理。
     *
     * <p>列表与详情都有。列表上它是一个显眼的列 —— 传错文件产生的批次
     * 一眼就能看出来（一屏差异、一条都没处理）。
     */
    private Integer unhandledCount;

    /**
     * 账单原文件还在不在。
     *
     * <p>供前端把下载按钮置灰。为 false 不等于对账失败：留档那一步失败时会记 warn
     * 但不阻断对账（管理员的核心目的是把账对起来），此时这里为 false。
     */
    private boolean hasBillFile;

    /** 执行对账的管理员 ID */
    private Long createdBy;

    /** 执行对账的管理员昵称，查不到时为空 */
    private String createdByName;

    /** 对账时刻 */
    private LocalDateTime createdAt;

    /**
     * 各类差异的条数，<b>只有批次详情才填</b>（列表页不需要，一次 GROUP BY 也省了）。
     *
     * <p>键是 {@code ReconcileDiffType} 的枚举名，值是条数。
     * <b>某个类型一条都没有时该键不存在</b> —— 前端要用 {@code ?? 0} 取值，
     * 而不是把它当成「这个类型不存在」。
     */
    private Map<String, Integer> diffTypeCounts;

    /**
     * 由实体组装视图。
     *
     * <p>两个参数都是凭证表之外的信息，由 Service 查好后传进来 ——
     * 视图对象自己不去查库（与 {@code AdminProofVo.from} 同一套做法）。
     *
     * @param batch         批次实体，不可为 null
     * @param unhandledCount 该批次未处理的差异条数
     * @param createdByName 执行对账的管理员昵称，查不到时传 null
     * @return 视图对象
     */
    public static ReconcileBatchVo from(ReconcileBatch batch, int unhandledCount, String createdByName) {
        ReconcileBatchVo vo = new ReconcileBatchVo();
        vo.setId(batch.getId());
        vo.setChannel(batch.getChannel());
        vo.setChannelLabel(ReconcileChannel.labelOf(batch.getChannel()));
        vo.setFileName(batch.getFileName());
        vo.setPeriodStart(batch.getPeriodStart());
        vo.setPeriodEnd(batch.getPeriodEnd());
        vo.setWindowStart(batch.getWindowStart());
        vo.setWindowEnd(batch.getWindowEnd());
        vo.setBillCount(batch.getBillCount());
        vo.setBillAmount(batch.getBillAmount());
        vo.setBillExcludedCount(batch.getBillExcludedCount());
        vo.setBillSkippedCount(batch.getBillSkippedCount());
        vo.setProofCount(batch.getProofCount());
        vo.setProofAmount(batch.getProofAmount());
        vo.setProofSkippedCount(batch.getProofSkippedCount());
        vo.setMatchedCount(batch.getMatchedCount());
        vo.setMatchedAmount(batch.getMatchedAmount());
        vo.setDiffCount(batch.getDiffCount());
        vo.setUnhandledCount(unhandledCount);
        vo.setHasBillFile(batch.getBillFilePath() != null && !batch.getBillFilePath().isBlank());
        vo.setCreatedBy(batch.getCreatedBy());
        vo.setCreatedByName(createdByName);
        vo.setCreatedAt(batch.getCreatedAt());
        return vo;
    }
}
