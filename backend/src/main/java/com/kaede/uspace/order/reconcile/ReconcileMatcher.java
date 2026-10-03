package com.kaede.uspace.order.reconcile;

import com.kaede.uspace.order.PaymentProofStatus;
import com.kaede.uspace.order.entity.PaymentProof;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把账单与系统凭证勾稽起来。
 *
 * <p><b>纯算法</b>：无 Spring、无 Mapper、无数据库。给它一批账单记录与一批候选凭证，
 * 它算出「哪些对上了、哪些差异是什么」。整个对账功能里最该被测的类，
 * 单测有十五条。
 *
 * <h3>候选必须先分三组 —— 这是全类最容易写错的地方</h3>
 *
 * <p>调用方拿到的是窗口内的<b>全部</b>凭证，其中包括<b>已经被之前的批次认领过的</b>。
 * 把它们简单跳过（或者更糟：在 SQL 里就筛掉）会丢掉一个关键信息：
 * <b>「这个单号已经被对过了」</b>。于是重传同一份账单时，账单里每一笔都找不到
 * 「在等的」凭证，全部变成 {@code BILL_ONLY} —— <b>一屏假差异，管理员立刻就不看了</b>。
 *
 * <p>所以分三组，各司其职：
 * <table border="1">
 *   <caption>候选的三个组</caption>
 *   <tr><th>组</th><th>判据</th><th>用途</th></tr>
 *   <tr><td>可匹配的</td><td>未被认领、未被驳回</td><td>参与单号匹配</td></tr>
 *   <tr><td>已被认领的</td><td>{@code reconcile_batch_id} 非空</td>
 *       <td>只用来判「账单里这笔已经对过了」→ {@code billSkipped++}，<b>不记差异</b></td></tr>
 *   <tr><td>被驳回的</td><td>{@code verify_status = REJECTED}</td>
 *       <td>只用来报「驳回后有款」</td></tr>
 * </table>
 *
 * <h3>两阶段</h3>
 *
 * <p><b>阶段一：逐笔账单。</b> 先看有没有「在等的」凭证，再决定这一笔是匹配、
 * 是金额不符、是一单多认领、是已对过、是驳回后有款，还是「账单有、系统无」。
 *
 * <p><b>阶段二：系统侧剩下的凭证。</b> 阶段一里没被碰过的，说明账单里没有它们 ——
 * 有单号的报「凭证无对应账单」，没填单号的报「未填流水号」。
 * 这两类<b>必须分开</b>：流水号是选填的，合成一类会把「用户没填」误报成「用户可能骗钱」。
 *
 * <p><b>金额比对用 {@code compareTo} 而不是 {@code equals}</b>：账单侧是
 * {@code new BigDecimal("8.00")}，凭证侧从 {@code DECIMAL(10,2)} 读出来也是两位，
 * 但两边标度一旦不一致（{@code 8} 与 {@code 8.00}），{@code equals} 会判不相等 ——
 * 而那是「明明是对的一笔却报金额不符」。项目为标度问题踩过坑。
 */
public final class ReconcileMatcher {

    /**
     * 匹配。
     *
     * @param bills      账单侧的收款记录；调用方保证非空（空账单在更早的地方就被挡掉了）
     * @param candidates 窗口内的全部候选凭证，含已被认领与被驳回的
     * @return 匹配结果
     */
    public static ReconcilePlan match(List<BillRecord> bills, List<PaymentProof> candidates) {
        // ============ 阶段 0：候选分三组 ============
        Map<String, List<PaymentProof>> activeByNo = new HashMap<>();
        List<PaymentProof> activeWithoutNo = new ArrayList<>();
        Set<String> claimedNos = new HashSet<>();
        Map<String, List<PaymentProof>> rejectedByNo = new HashMap<>();

        int proofSkipped = 0;
        int activeCount = 0;
        BigDecimal activeAmount = BigDecimal.ZERO;

        for (PaymentProof proof : candidates) {
            if (PaymentProofStatus.REJECTED.name().equals(proof.getVerifyStatus())) {
                addByNo(rejectedByNo, ReconcileTexts.normalize(proof.getPaymentNo()), proof);
                continue;
            }
            if (proof.getReconcileBatchId() != null) {
                // 已被之前的批次认领：不进可匹配的那一组，但要留下单号 ——
                // 账单侧要靠它判断「这笔已经对过了」，而不是当成一笔没人认领的钱
                proofSkipped++;
                String no = ReconcileTexts.normalize(proof.getPaymentNo());
                if (no != null) {
                    claimedNos.add(no);
                }
                continue;
            }

            String no = ReconcileTexts.normalize(proof.getPaymentNo());
            if (no == null) {
                activeWithoutNo.add(proof);
            } else {
                addByNo(activeByNo, no, proof);
            }
            activeCount++;
            activeAmount = activeAmount.add(amountOf(proof));
        }

        // ============ 阶段 1：逐笔账单 ============
        Set<Long> consumed = new HashSet<>();
        List<ReconcilePlan.MatchedPair> matched = new ArrayList<>();
        List<ReconcilePlan.DiffDraft> diffs = new ArrayList<>();
        int billSkipped = 0;

        for (BillRecord bill : bills) {
            String no = bill.paymentNo();
            List<PaymentProof> hits = activeByNo.getOrDefault(no, List.of());
            boolean alreadyClaimed = claimedNos.contains(no);

            if (hits.isEmpty()) {
                if (alreadyClaimed) {
                    // 之前的批次已经把这笔对走了 —— 本次什么都不做，也**不记差异**。
                    // 重传同一份账单时走的就是这一支：它不是错误，是幂等
                    billSkipped++;
                } else if (rejectedByNo.containsKey(no)) {
                    PaymentProof rejected = rejectedByNo.get(no).get(0);
                    diffs.add(draft(ReconcileDiffType.REJECTED_IN_BILL, rejected, bill, no));
                } else {
                    diffs.add(draft(ReconcileDiffType.BILL_ONLY, null, bill, no));
                }
                continue;
            }

            if (alreadyClaimed || hits.size() > 1) {
                // 两种情况在这里合流，因为它们的含义是同一件事的两面：
                //   · 本批次里有两条以上凭证抢同一笔账单
                //   · 本批次有一条在抢，而之前某个批次已经认领走了
                // 都表示「同一笔钱被多条凭证认领」，值得人看一眼
                for (PaymentProof hit : hits) {
                    consumed.add(hit.getId());
                }
                diffs.add(draft(ReconcileDiffType.DUPLICATE_CLAIM, hits.get(0), bill, no));
                continue;
            }

            PaymentProof proof = hits.get(0);
            consumed.add(proof.getId());
            if (proof.getAmount() != null && proof.getAmount().compareTo(bill.amount()) == 0) {
                matched.add(new ReconcilePlan.MatchedPair(proof.getId(), bill));
            } else {
                diffs.add(draft(ReconcileDiffType.AMOUNT_MISMATCH, proof, bill, no));
            }
        }

        // ============ 阶段 2：系统侧剩下的凭证 ============
        for (Map.Entry<String, List<PaymentProof>> entry : activeByNo.entrySet()) {
            for (PaymentProof proof : entry.getValue()) {
                if (!consumed.contains(proof.getId())) {
                    // 单号取索引的键 —— 它就是归一化之后的那一份，
                    // 也正是写进差异表 payment_no 列的东西（比对用的号）
                    diffs.add(draft(ReconcileDiffType.PROOF_ONLY, proof, null, entry.getKey()));
                }
            }
        }
        for (PaymentProof proof : activeWithoutNo) {
            // 没填流水号的单独一类：它是「信息不足」，不是「可疑」
            diffs.add(draft(ReconcileDiffType.NO_PAYMENT_NO, proof, null, null));
        }

        BigDecimal matchedAmount = matched.stream()
                .map(pair -> pair.bill().amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new ReconcilePlan(matched, diffs, billSkipped, proofSkipped,
                activeCount, activeAmount, matchedAmount);
    }

    /**
     * 造一条差异草稿，把两侧的信息各取一半。
     *
     * @param type 类型
     * @param proof 系统侧凭证，可为 null（{@code BILL_ONLY}）
     * @param bill  账单侧记录，可为 null（{@code PROOF_ONLY} / {@code NO_PAYMENT_NO}）
     * @param normalizedNo 归一化后的单号，可为 null
     * @return 差异草稿
     */
    private static ReconcilePlan.DiffDraft draft(ReconcileDiffType type, PaymentProof proof,
                                                 BillRecord bill, String normalizedNo) {
        return new ReconcilePlan.DiffDraft(
                type,
                proof == null ? null : proof.getId(),
                // 单号优先取账单侧归一化后的那个：它才是「比对用的号」。
                // 凭证侧没有的（NO_PAYMENT_NO）就是 null
                bill != null ? bill.paymentNo() : normalizedNo,
                proof == null ? null : proof.getOrderNo(),
                proof == null ? null : proof.getTargetType(),
                bill == null ? null : bill.amount(),
                proof == null ? null : proof.getAmount(),
                bill == null ? null : bill.tradedAt(),
                bill == null ? null : bill.summary());
    }

    /**
     * 往「单号 → 凭证列表」的索引里放一条。
     *
     * @param index 索引
     * @param no    归一化后的单号，可为 null（为空时不放）
     * @param proof 凭证
     */
    private static void addByNo(Map<String, List<PaymentProof>> index, String no, PaymentProof proof) {
        if (no == null) {
            return;
        }
        index.computeIfAbsent(no, key -> new ArrayList<>()).add(proof);
    }

    /**
     * 取凭证金额，为空时按 0 处理。
     *
     * <p>库列是 {@code NOT NULL}，理论上取不到 null；但这里是在算一个<b>汇总数</b>，
     * 为了一个不该出现的情况让整个对账抛 NPE 是不划算的。
     *
     * @param proof 凭证
     * @return 金额
     */
    private static BigDecimal amountOf(PaymentProof proof) {
        return proof.getAmount() == null ? BigDecimal.ZERO : proof.getAmount();
    }

    /** 工具类，不实例化 */
    private ReconcileMatcher() {
    }
}
