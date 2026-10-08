package com.kaede.uspace.order.reconcile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 一次匹配算出来的全部结果。
 *
 * <p>{@link ReconcileMatcher} 的产出，{@code ReconcileService} 的输入 ——
 * 中间这一层是<b>纯数据</b>，不碰数据库、不碰 Spring。
 * 那条边界让「匹配算法对不对」这件事能脱离一切环境单测：给它一批账单记录
 * 与一批凭证，看它算出什么。
 *
 * <p><b>差异用的是 {@link DiffDraft} 而不是现成的实体</b>：
 * 匹配发生在批次插入之前（批次的计数要一次写入，所以必须先算完），
 * 那时 {@code batch_id} 还不存在。草稿留在内存里，等批次落了库再由 Service
 * 补上批次 ID 转成实体。
 *
 * <h3>{@code billUnclaimed*} 是「总账对没对平」的依据</h3>
 *
 * <p>口径：<b>账单里的这笔钱，在系统里有没有一条「有效凭证」指着它</b>。
 * 没有的才算未认领 —— 具体是 {@code BILL_ONLY}（一条凭证都没有）与
 * {@code REJECTED_IN_BILL}（有凭证指着，但那条被驳回了，不算数）两支。
 *
 * <p>⚠️ <b>{@code AMOUNT_MISMATCH} 与 {@code DUPLICATE_CLAIM} 刻意不算未认领</b>：
 * 那两类钱确实到了、也有凭证指着它，只是有疑点。混进来的话，一笔「金额差 2 元」
 * 会被报成「有 8 元没收到」—— 把结论说重了，而管理员据此会去找一笔并不存在的账。
 * 它们仍然进差异列表，只是不拉低总账。
 *
 * @param matched       匹配成功的（凭证 ID，账单记录）对
 * @param diffs         差异草稿，已去重由 Service 负责
 * @param billSkipped   账单侧因「已被之前的批次认领」而跳过的笔数
 * @param billUnclaimedCount  账单侧未被任何有效凭证认领的笔数
 * @param billUnclaimedAmount 账单侧未被认领的金额合计（元）
 * @param proofSkipped  系统侧因「已被之前的批次认领」而跳过的凭证数
 * @param activeCount   参与比对的凭证数（未被认领、未被驳回）
 * @param activeAmount  参与比对的凭证金额合计（元）
 * @param matchedAmount 匹配成功的金额合计（元）
 */
public record ReconcilePlan(
        List<MatchedPair> matched,
        List<DiffDraft> diffs,
        int billSkipped,
        int billUnclaimedCount,
        BigDecimal billUnclaimedAmount,
        int proofSkipped,
        int activeCount,
        BigDecimal activeAmount,
        BigDecimal matchedAmount) {

    /**
     * 取匹配成功的凭证 ID。
     *
     * <p>给 {@code PaymentProofMapper#markReconciled} 用 —— 那个方法收的是 ID 列表。
     * 空列表要由调用方挡掉：它拼进 SQL 会变成 {@code IN ()} 语法错误。
     *
     * @return 凭证 ID 列表，可能为空
     */
    public List<Long> matchedProofIds() {
        return matched.stream().map(MatchedPair::proofId).toList();
    }

    /**
     * 一对匹配（系统侧凭证 ↔ 账单侧记录）。
     *
     * @param proofId 凭证 ID
     * @param bill    账单记录
     */
    public record MatchedPair(Long proofId, BillRecord bill) {
    }

    /**
     * 一条差异草稿，字段与 {@code ReconcileDiff} 实体一一对应，只差 {@code batchId}。
     *
     * <p>账单侧没有记录时（{@code PROOF_ONLY} / {@code NO_PAYMENT_NO}），
     * 四个 {@code bill*} 字段全是 null；系统侧没有凭证时（{@code BILL_ONLY}），
     * {@code proofId} / {@code orderNo} / {@code targetType} / {@code proofAmount} 是 null。
     * 那是语义而不是数据不全，见 {@code ReconcileDiffType} 的类注释。
     *
     * @param type        差异类型
     * @param proofId     相关凭证 ID，可空
     * @param paymentNo   归一化后的流水号，可空
     * @param orderNo     商户订单号快照，可空
     * @param targetType  收款类型快照，可空
     * @param billAmount  账单侧金额（元），可空
     * @param proofAmount 系统侧凭证金额（元），可空
     * @param billTime    账单上的交易时刻，可空
     * @param billSummary 账单侧的商品 / 交易对方摘要，可空
     */
    public record DiffDraft(
            ReconcileDiffType type,
            Long proofId,
            String paymentNo,
            String orderNo,
            String targetType,
            BigDecimal billAmount,
            BigDecimal proofAmount,
            LocalDateTime billTime,
            String billSummary) {
    }
}
