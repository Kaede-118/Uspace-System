package com.kaede.uspace.order.reconcile;

import com.kaede.uspace.order.PaymentProofStatus;
import com.kaede.uspace.order.PaymentTargetType;
import com.kaede.uspace.order.entity.PaymentProof;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ReconcileMatcher} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring、不连库</b> —— 匹配算法是个纯函数，
 * 这是整个对账功能里最该被测的类。
 *
 * <p>十五条里最要紧的是这四条，它们各自钉着一个「写错了也不会报错」的坑：
 * <ol>
 *   <li>金额 {@code 8.0} 与 {@code 8.00} 必须匹配成功（{@code compareTo} 而不是 {@code equals}）</li>
 *   <li>没填流水号的凭证要进「未填流水号」而不是「凭证无对应账单」——
 *       后者会把「用户没填」说成「用户可能骗钱」</li>
 *   <li>已被别的批次认领的单号出现在账单里时，要算「跳过」而<b>不是</b>「账单无对应凭证」——
 *       重传同一份账单时，这一条决定了一屏是安静还是几十条假差异</li>
 *   <li>被驳回的凭证的单号不在账单里时，什么都不报</li>
 * </ol>
 */
class ReconcileMatcherTests {

    private static final String NO_A = "4200001234202609301234567890";

    private static final String NO_B = "4200009999202609301234567890";

    // ==================================================================
    // 匹配成功
    // ==================================================================

    @Test
    @DisplayName("单号相符 + 金额相符 → 匹配，且不产生任何差异")
    void match_successProducesNoDiff() {
        ReconcilePlan plan = ReconcileMatcher.match(
                List.of(bill(NO_A, "8.00")),
                List.of(proof(1L, NO_A, "8.00", PaymentProofStatus.SUBMITTED.name(), null)));

        assertEquals(1, plan.matched().size());
        assertEquals(1L, plan.matched().get(0).proofId());
        assertEquals(List.of(), plan.diffs(), "对上了就不该有差异 —— 这一条的对偶是下面那条空账单");
        assertEquals(0, plan.billSkipped());
        assertEquals(0, plan.proofSkipped());
    }

    @Test
    @DisplayName("金额 8.0 与 8.00 必须匹配成功 —— 标度不同不是「金额不符」")
    void match_amountScaleDoesNotBreakEquality() {
        ReconcilePlan plan = ReconcileMatcher.match(
                List.of(bill(NO_A, "8.0")),
                List.of(proof(1L, NO_A, "8.00", PaymentProofStatus.SUBMITTED.name(), null)));

        assertEquals(1, plan.matched().size(),
                "账单侧标度 1、凭证侧标度 2 时 equals 会判不相等。用 equals 的话"
                        + "每一笔整元金额都会被报成「金额不符」—— 而它们其实一模一样。"
                        + "项目为 BigDecimal 标度问题踩过坑，所以这里用 compareTo");
        assertEquals(List.of(), plan.diffs());
    }

    @Test
    @DisplayName("已核对的凭证与待复核的凭证一样参与匹配")
    void match_includesConfirmedProofs() {
        ReconcilePlan plan = ReconcileMatcher.match(
                List.of(bill(NO_A, "8.00"), bill(NO_B, "12.00")),
                List.of(proof(1L, NO_A, "8.00", PaymentProofStatus.CONFIRMED.name(), null),
                        proof(2L, NO_B, "12.00", PaymentProofStatus.SUBMITTED.name(), null)));

        assertEquals(2, plan.matched().size(),
                "对账的目的是「钱到底到没到」，还没复核的凭证一样要参与 —— "
                        + "而且对上了还能反过来帮管理员复核");
    }

    // ==================================================================
    // 金额不符
    // ==================================================================

    @Test
    @DisplayName("单号相符但金额不符 → 金额不符，两侧的金额都要留下")
    void match_amountMismatch() {
        ReconcilePlan plan = ReconcileMatcher.match(
                List.of(bill(NO_A, "8.00")),
                List.of(proof(1L, NO_A, "12.00", PaymentProofStatus.SUBMITTED.name(), null)));

        assertEquals(0, plan.matched().size());
        assertEquals(1, plan.diffs().size());
        ReconcilePlan.DiffDraft diff = plan.diffs().get(0);
        assertEquals(ReconcileDiffType.AMOUNT_MISMATCH, diff.type());
        assertEquals(0, new BigDecimal("8.00").compareTo(diff.billAmount()),
                "账单侧说收了多少");
        assertEquals(0, new BigDecimal("12.00").compareTo(diff.proofAmount()),
                "系统里记了多少 —— 两侧都要留下，只有一个数的话管理员看不出差在哪");
    }

    // ==================================================================
    // 一单多认领
    // ==================================================================

    @Test
    @DisplayName("同号两条凭证 → 一单多认领，两条都被消费掉，不再各自报「凭证无对应账单」")
    void match_duplicateClaimConsumesBothProofs() {
        ReconcilePlan plan = ReconcileMatcher.match(
                List.of(bill(NO_A, "8.00")),
                List.of(proof(1L, NO_A, "8.00", PaymentProofStatus.SUBMITTED.name(), null),
                        proof(2L, NO_A, "8.00", PaymentProofStatus.SUBMITTED.name(), null)));

        assertEquals(0, plan.matched().size(), "两条抢一笔，谁都不算匹配上");
        assertEquals(1, plan.diffs().size(),
                "只记一条差异代表这一笔的冲突 —— 记两条的话，同一件事会在列表里出现两次");
        assertEquals(ReconcileDiffType.DUPLICATE_CLAIM, plan.diffs().get(0).type());
        assertEquals(1L, plan.diffs().get(0).proofId(), "指向前一条，管理员据此去找那张重复的截图");
    }

    @Test
    @DisplayName("已被别的批次认领的单号又出现在本次账单里 → 旧凭证与本批次的新凭证构成一单多认领")
    void match_claimedPlusNewProofIsDuplicate() {
        ReconcilePlan plan = ReconcileMatcher.match(
                List.of(bill(NO_A, "8.00")),
                List.of(proof(1L, NO_A, "8.00", PaymentProofStatus.SUBMITTED.name(), 999L),
                        proof(2L, NO_A, "8.00", PaymentProofStatus.SUBMITTED.name(), null)));

        assertEquals(1, plan.diffs().size(),
                "上一批认领过、这一批又冒出一条抢同一笔 —— 那说明有人拿同一笔钱提交了两次凭证");
        assertEquals(ReconcileDiffType.DUPLICATE_CLAIM, plan.diffs().get(0).type());
        assertEquals(1, plan.proofSkipped(), "已被认领的那条计入跳过数");
    }

    // ==================================================================
    // 单侧存在的三类
    // ==================================================================

    @Test
    @DisplayName("账单里有、系统里没人认领 → 账单无对应凭证，且没有凭证可指")
    void match_billOnly() {
        ReconcilePlan plan = ReconcileMatcher.match(
                List.of(bill(NO_A, "8.00")),
                List.of());

        assertEquals(1, plan.diffs().size());
        ReconcilePlan.DiffDraft diff = plan.diffs().get(0);
        assertEquals(ReconcileDiffType.BILL_ONLY, diff.type());
        assertNull(diff.proofId(),
                "这一类说的正是「系统里没有任何凭证认领这笔钱」，本来就没有凭证可指");
        assertEquals(0, new BigDecimal("8.00").compareTo(diff.billAmount()));
        assertNull(diff.proofAmount(), "系统侧没有记录，金额为空是语义而不是缺失");
    }

    @Test
    @DisplayName("凭证有、账单里找不到 → 凭证无对应账单，账单侧四个字段全空")
    void match_proofOnly() {
        ReconcilePlan plan = ReconcileMatcher.match(
                List.of(),
                List.of(proof(1L, NO_A, "8.00", PaymentProofStatus.SUBMITTED.name(), null)));

        assertEquals(1, plan.diffs().size());
        ReconcilePlan.DiffDraft diff = plan.diffs().get(0);
        assertEquals(ReconcileDiffType.PROOF_ONLY, diff.type());
        assertEquals(1L, diff.proofId());
        assertEquals(NO_A, diff.paymentNo());
        assertEquals(0, new BigDecimal("8.00").compareTo(diff.proofAmount()));
        assertNull(diff.billAmount(), "账单侧没有这笔钱，金额为空");
    }

    @Test
    @DisplayName("凭证没填流水号 → 未填流水号，绝不能报成「凭证无对应账单」")
    void match_noPaymentNo() {
        ReconcilePlan plan = ReconcileMatcher.match(
                List.of(),
                List.of(proof(1L, null, "8.00", PaymentProofStatus.SUBMITTED.name(), null),
                        proof(2L, "   ", "8.00", PaymentProofStatus.SUBMITTED.name(), null)));

        assertEquals(2, plan.diffs().size());
        assertEquals(ReconcileDiffType.NO_PAYMENT_NO, plan.diffs().get(0).type(),
                "流水号是选填的（项目文档原话是「选填但强烈建议填」）。"
                        + "把「没填」报成「系统里有这笔凭证但账单里找不到」，"
                        + "等于把一个信息不足的条目说成作弊嫌疑");
        assertEquals(ReconcileDiffType.NO_PAYMENT_NO, plan.diffs().get(1).type(),
                "全是空白的串归一化之后也是 null —— 与真的没填走同一支");
    }

    // ==================================================================
    // 被驳回的
    // ==================================================================

    @Test
    @DisplayName("被驳回的凭证，其单号出现在账单里 → 驳回后有款")
    void match_rejectedInBill() {
        ReconcilePlan plan = ReconcileMatcher.match(
                List.of(bill(NO_A, "8.00")),
                List.of(proof(1L, NO_A, "8.00", PaymentProofStatus.REJECTED.name(), null)));

        assertEquals(0, plan.matched().size(), "驳回是管理员的结论，对账不替它翻案");
        assertEquals(1, plan.diffs().size());
        ReconcilePlan.DiffDraft diff = plan.diffs().get(0);
        assertEquals(ReconcileDiffType.REJECTED_IN_BILL, diff.type(),
                "这一类的含义不是「可疑」，而是「系统当前的结论与事实相反」——"
                        + "并进「账单无对应凭证」的话，它会淹在几十条「顾客没交凭证」里被错过");
        assertEquals(1L, diff.proofId(), "要指回那条凭证，管理员才知道回去改哪一条");
    }

    @Test
    @DisplayName("被驳回的凭证，其单号不在账单里 → 什么都不报")
    void match_rejectedNotInBillIsSilent() {
        ReconcilePlan plan = ReconcileMatcher.match(
                List.of(),
                List.of(proof(1L, NO_A, "8.00", PaymentProofStatus.REJECTED.name(), null)));

        assertEquals(List.of(), plan.diffs(),
                "已经驳回过了，账单里也没有这笔钱 —— 这条结论没问题，没有新闻可报。"
                        + "为它产生一条差异只会让「凭证无对应账单」那一堆再多一条噪声");
    }

    // ==================================================================
    // 幂等：重传同一份账单
    // ==================================================================

    @Test
    @DisplayName("已被之前批次认领的单号 → 计入跳过数，不报任何差异")
    void match_claimedBillIsSkippedSilently() {
        ReconcilePlan plan = ReconcileMatcher.match(
                List.of(bill(NO_A, "8.00")),
                List.of(proof(1L, NO_A, "8.00", PaymentProofStatus.CONFIRMED.name(), 999L)));

        assertEquals(1, plan.billSkipped(),
                "「这个单号已经被对过了」—— 这正是不筛 reconcile_batch_id 的原因："
                        + "候选里留着那条已认领的凭证，账单侧才能判出「已对过」");
        assertEquals(List.of(), plan.diffs(),
                "重传同一份账单时必须安静。报一条「账单无对应凭证」的话，"
                        + "整屏都是假差异，管理员立刻就不看这一页了");
        assertEquals(1, plan.proofSkipped(), "已认领的凭证计入跳过数 —— 页面上要能解释「这次为什么匹配 0 笔」");
        assertEquals(0, plan.activeCount(), "它不参与比对");
    }

    @Test
    @DisplayName("同一份账单连对两次：第二次匹配数与差异数都是 0，但跳过数不为 0")
    void match_secondRunIsIdempotent() {
        List<BillRecord> bills = List.of(bill(NO_A, "8.00"), bill(NO_B, "12.00"));
        List<PaymentProof> firstRound = List.of(
                proof(1L, NO_A, "8.00", PaymentProofStatus.SUBMITTED.name(), null),
                proof(2L, NO_B, "12.00", PaymentProofStatus.SUBMITTED.name(), null));

        ReconcilePlan first = ReconcileMatcher.match(bills, firstRound);
        assertEquals(2, first.matched().size(), "第一遍：两笔全对上");

        // 第二遍：那两条凭证已经被第一批认领了（reconcile_batch_id 填上了）
        List<PaymentProof> secondRound = List.of(
                proof(1L, NO_A, "8.00", PaymentProofStatus.SUBMITTED.name(), 999L),
                proof(2L, NO_B, "12.00", PaymentProofStatus.SUBMITTED.name(), 999L));
        ReconcilePlan second = ReconcileMatcher.match(bills, secondRound);

        assertEquals(0, second.matched().size(), "重复认领不产生任何写入");
        assertEquals(List.of(), second.diffs(), "也不产生任何差异");
        assertEquals(2, second.billSkipped(), "但跳过数不为 0 —— 它是「为什么这次什么都没对出来」的唯一答案");
    }

    // ==================================================================
    // 计数与恒等式
    // ==================================================================

    @Test
    @DisplayName("计数：参与比对的笔数与金额只算「可匹配」那一组")
    void match_countsOnlyActive() {
        ReconcilePlan plan = ReconcileMatcher.match(
                List.of(bill(NO_A, "8.00")),
                List.of(proof(1L, NO_A, "8.00", PaymentProofStatus.SUBMITTED.name(), null),
                        proof(2L, NO_B, "12.00", PaymentProofStatus.CONFIRMED.name(), 999L),
                        proof(3L, "X-3", "5.00", PaymentProofStatus.REJECTED.name(), null)));

        assertEquals(1, plan.activeCount(),
                "已被认领的与被驳回的都不算 —— 它们不参与比对");
        assertEquals(0, new BigDecimal("8.00").compareTo(plan.activeAmount()));
        assertEquals(0, new BigDecimal("8.00").compareTo(plan.matchedAmount()));
        assertEquals(1, plan.proofSkipped());
    }

    @Test
    @DisplayName("恒等式：账单笔数 − 跳过 = 匹配 + 四条差异类型之和")
    void match_billCountIdentity() {
        List<BillRecord> bills = List.of(
                bill("N1", "8.00"),    // 匹配
                bill("N2", "8.00"),    // 金额不符
                bill("N3", "8.00"),    // 一单多认领
                bill("N4", "8.00"),    // 账单无对应凭证
                bill("N5", "8.00"));   // 已对过 → 跳过
        List<PaymentProof> candidates = List.of(
                proof(1L, "N1", "8.00", PaymentProofStatus.SUBMITTED.name(), null),
                proof(2L, "N2", "12.00", PaymentProofStatus.SUBMITTED.name(), null),
                proof(3L, "N3", "8.00", PaymentProofStatus.SUBMITTED.name(), null),
                proof(4L, "N3", "8.00", PaymentProofStatus.SUBMITTED.name(), null),
                proof(5L, "N5", "8.00", PaymentProofStatus.CONFIRMED.name(), 999L));

        ReconcilePlan plan = ReconcileMatcher.match(bills, candidates);

        // 「账单侧」的四类：其余两类（无对应账单 / 未填流水号）是系统侧产出的，不在这个恒等式里
        Set<ReconcileDiffType> billSideTypes = Set.of(ReconcileDiffType.AMOUNT_MISMATCH,
                ReconcileDiffType.DUPLICATE_CLAIM, ReconcileDiffType.BILL_ONLY,
                ReconcileDiffType.REJECTED_IN_BILL);
        long billSideDiffs = plan.diffs().stream()
                .filter(d -> billSideTypes.contains(d.type()))
                .count();
        assertEquals(bills.size() - plan.billSkipped(), plan.matched().size() + billSideDiffs,
                "账单里的每一笔要么被匹配、要么变成一条差异、要么是被跳过（已对过）—— "
                        + "三者之外没有第四个去处。任何一个计数算错都会让这条恒等式不成立，"
                        + "而单看某一项是看不出来的");
        assertEquals(1, plan.billSkipped());
        assertEquals(1, plan.matched().size());
        assertEquals(3, plan.diffs().size(), "金额不符 + 一单多认领 + 账单无对应凭证");
    }

    @Test
    @DisplayName("空账单：全部可匹配的凭证都进差异，一条不漏")
    void match_emptyBill() {
        ReconcilePlan plan = ReconcileMatcher.match(
                List.of(),
                List.of(proof(1L, NO_A, "8.00", PaymentProofStatus.SUBMITTED.name(), null),
                        proof(2L, null, "8.00", PaymentProofStatus.SUBMITTED.name(), null)));

        assertEquals(2, plan.diffs().size(),
                "服务层会在更早的地方用 RECONCILE_BILL_EMPTY 挡住空账单，"
                        + "但匹配器本身不该依赖那个前提");
        assertEquals(ReconcileDiffType.PROOF_ONLY, plan.diffs().get(0).type());
        assertEquals(ReconcileDiffType.NO_PAYMENT_NO, plan.diffs().get(1).type());
    }

    @Test
    @DisplayName("全部匹配时差异为空、跳过为 0")
    void match_allMatched() {
        ReconcilePlan plan = ReconcileMatcher.match(
                List.of(bill(NO_A, "8.00"), bill(NO_B, "12.00")),
                List.of(proof(1L, NO_A, "8.00", PaymentProofStatus.SUBMITTED.name(), null),
                        proof(2L, NO_B, "12.00", PaymentProofStatus.SUBMITTED.name(), null)));

        assertEquals(List.of(), plan.diffs());
        assertEquals(0, plan.billSkipped());
        assertEquals(0, plan.proofSkipped());
        assertEquals(2, plan.matchedProofIds().size());
        assertEquals(0, new BigDecimal("20.00").compareTo(plan.matchedAmount()),
                "匹配金额是账单侧的合计");
    }

    @Test
    @DisplayName("单号归一化：大小写与空白的差异不影响匹配")
    void match_normalizesPaymentNo() {
        ReconcilePlan plan = ReconcileMatcher.match(
                List.of(bill("abc123", "8.00")),
                List.of(proof(1L, " ABC 123 ", "8.00", PaymentProofStatus.SUBMITTED.name(), null)));

        assertEquals(1, plan.matched().size(),
                "候选里的单号是库里的原值，必须归一化后再比 —— "
                        + "两边的归一化是同一个函数，任何一边漏调都会让本来能对上的单子对不上，"
                        + "而表现是「全都是差异」，看不出哪里错了");
    }

    @Test
    @DisplayName("匹配成功后没有差异时，matchedProofIds 与 matched 一一对应")
    void matchedProofIds_lineUpWithMatched() {
        ReconcilePlan plan = ReconcileMatcher.match(
                List.of(bill(NO_A, "8.00"), bill(NO_B, "12.00")),
                List.of(proof(11L, NO_A, "8.00", PaymentProofStatus.SUBMITTED.name(), null),
                        proof(22L, NO_B, "12.00", PaymentProofStatus.SUBMITTED.name(), null)));

        assertEquals(List.of(11L, 22L), plan.matchedProofIds(),
                "这个列表直接喂给 markReconciled，顺序与内容都要与 matched 对得上");
        assertEquals(plan.matched().size(), plan.matchedProofIds().size(),
                "两个列表长度必须一致 —— markReconciled 拿返回行数与它一比，"
                        + "就知道有没有被并发的批次抢走凭证");
    }

    // ==================================================================
    // 测试数据构造
    // ==================================================================

    /**
     * 造一条账单记录。
     *
     * @param paymentNo 单号（会被归一化，见 {@link ReconcileTexts#normalize}）
     * @param amount    金额文本
     * @return 账单记录
     */
    private static BillRecord bill(String paymentNo, String amount) {
        return new BillRecord(ReconcileTexts.normalize(paymentNo), paymentNo,
                new BigDecimal(amount), LocalDateTime.of(2026, 9, 30, 21, 45, 32), "舞萌");
    }

    /**
     * 造一条凭证。
     *
     * @param id               凭证 ID
     * @param paymentNo        流水号，可为 null（模拟没填）
     * @param amount           金额文本
     * @param verifyStatus     复核状态
     * @param reconcileBatchId 已认领的批次 ID，null 表示未认领
     * @return 凭证实体
     */
    private static PaymentProof proof(Long id, String paymentNo, String amount,
                                      String verifyStatus, Long reconcileBatchId) {
        PaymentProof proof = new PaymentProof();
        proof.setId(id);
        proof.setTargetType(PaymentTargetType.ORDER.name());
        proof.setTargetId(id * 100);
        proof.setOrderNo("OD" + id);
        proof.setUserId(1001L);
        proof.setAmount(new BigDecimal(amount));
        proof.setProofUrl("/uploads/proof/p" + id + ".jpg");
        proof.setPaymentNo(paymentNo);
        proof.setVerifyStatus(verifyStatus);
        proof.setReconcileBatchId(reconcileBatchId);
        return proof;
    }
}
