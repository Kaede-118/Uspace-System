package com.kaede.uspace.order.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kaede.uspace.order.PaymentTargetType;
import com.kaede.uspace.order.entity.ReconcileDiff;
import com.kaede.uspace.order.reconcile.ReconcileDiffType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReconcileDiffMapper} 的集成测试，连本机真实 MySQL。
 *
 * <p><b>三重保护不污染开发库</b>：{@code @Transactional} 每个用例结束后自动回滚；
 * {@code @EnabledIfEnvironmentVariable} 让没配 {@code MYSQL_PASSWORD} 的机器整个跳过；
 * 批次 ID 取 {@link #BATCH_SEQ} 起的连号 —— 本表没有外键约束，所以可以直接用一个
 * 不与真实批次冲突的大数，不必先插一条批次。
 *
 * <p><b>两处只有真库验证得到</b>：
 * <ol>
 *   <li><b>{@code ORDER BY FIELD(diff_type, ...)}</b> —— 那段字面量是编译期常量
 *       （注解要求），与枚举的声明顺序<b>可能漂移</b>。漂移了不会有任何报错，
 *       只表现为「最紧急的那类差异排到了中间」。单测只能验常量本身，
 *       真库这条才能验「排序真的按它生效」</li>
 *   <li><b>{@code handle} 的状态守卫</b> —— 两个管理员同时处理的竞态，
 *       假 Mapper 是照着自己的守卫写的，验不出真 SQL 里少没少那个
 *       {@code AND handled = 0}</li>
 * </ol>
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+",
        disabledReason = "未配置 MYSQL_PASSWORD，跳过需要真实数据库的集成测试")
class ReconcileDiffMapperIntegrationTests {

    /** 批次 ID 的起点，取大数以免与开发库里真实的批次撞上 */
    private static final AtomicLong BATCH_SEQ = new AtomicLong(999901L);

    /** 测试用的管理员 ID */
    private static final Long ADMIN_ID = 999901L;

    @Autowired
    private ReconcileDiffMapper diffMapper;

    // ==================================================================
    // 落库
    // ==================================================================

    @Test
    @DisplayName("插入：主键被回填，默认待处理")
    void insert_backfillsGeneratedKey() {
        ReconcileDiff diff = newDiff(BATCH_SEQ.incrementAndGet(), ReconcileDiffType.BILL_ONLY, null, "WX-1");

        diffMapper.insert(diff);

        assertNotNull(diff.getId(), "MyBatis-Plus 自带的 insert 会回填主键");
        assertNotNull(diff.getCreatedAt(), "created_at 由 AuditMetaObjectHandler 填充");
        ReconcileDiff reloaded = diffMapper.selectById(diff.getId());
        assertEquals(0, reloaded.getHandled(), "新差异默认待处理 —— 建表脚本里那个 DEFAULT 是第二道防线");
        assertNull(reloaded.getHandledAt(), "还没处理，处理时刻为空");
    }

    @Test
    @DisplayName("可空列真能留空：BILL_ONLY 没有凭证，PROOF_ONLY 没有账单侧金额")
    void insert_allowsNullColumns() {
        // 两类差异的「缺一半」是语义而不是数据不全：BILL_ONLY 说的是「系统里没人认领这笔钱」，
        // 所以它没有凭证可指；PROOF_ONLY 说的是「账单里找不到这笔钱」，所以它没有账单侧金额
        ReconcileDiff billOnly = newDiff(BATCH_SEQ.incrementAndGet(), ReconcileDiffType.BILL_ONLY, null, "WX-1");
        billOnly.setProofAmount(null);
        diffMapper.insert(billOnly);

        ReconcileDiff proofOnly = newDiff(BATCH_SEQ.incrementAndGet(), ReconcileDiffType.PROOF_ONLY, 12345L, "WX-2");
        proofOnly.setBillAmount(null);
        diffMapper.insert(proofOnly);

        assertNull(diffMapper.selectById(billOnly.getId()).getProofId(),
                "「账单里有、系统里没人认领」这一类本来就没有凭证可指，空着是语义而不是缺失");
        assertNull(diffMapper.selectById(proofOnly.getId()).getBillAmount(),
                "「系统有、账单无」这一类账单侧没有金额");
    }

    // ==================================================================
    // 处理：状态守卫
    // ==================================================================

    @Test
    @DisplayName("标记已处理：写结论与时刻")
    void handle_writesConclusion() {
        ReconcileDiff diff = newDiff(BATCH_SEQ.incrementAndGet(), ReconcileDiffType.NO_PAYMENT_NO, 7001L, null);
        diffMapper.insert(diff);

        int affected = diffMapper.handle(diff.getId(), ADMIN_ID, "已联系顾客补单号");

        assertEquals(1, affected, "第一次处理应当成功");
        ReconcileDiff reloaded = diffMapper.selectById(diff.getId());
        assertEquals(1, reloaded.getHandled());
        assertEquals("已联系顾客补单号", reloaded.getHandleNote());
        assertEquals(ADMIN_ID, reloaded.getHandledBy());
        assertNotNull(reloaded.getHandledAt());
    }

    @Test
    @DisplayName("标记已处理：第二次返回 0 —— 这正是并发时唯一的防线")
    void handle_isGuardedByStatus() {
        ReconcileDiff diff = newDiff(BATCH_SEQ.incrementAndGet(), ReconcileDiffType.PROOF_ONLY, 7002L, "WX-3");
        diffMapper.insert(diff);
        diffMapper.handle(diff.getId(), ADMIN_ID, "第一次的结论");

        int second = diffMapper.handle(diff.getId(), 999902L, "第二次的结论");

        assertEquals(0, second,
                "两个管理员同时看对账结果时，后一个的结论不该覆盖前一个的。"
                        + "守卫写在 SQL 的 WHERE 里（AND handled = 0），"
                        + "而不是「先查状态再更新」—— 后者中间有竞态窗口");
        assertEquals("第一次的结论", diffMapper.selectById(diff.getId()).getHandleNote(),
                "被拒的那次不能改到任何东西");
    }

    @Test
    @DisplayName("备注可以留空 —— 它是管理员写给自己的备忘，不是给顾客的结论")
    void handle_allowsBlankNote() {
        ReconcileDiff diff = newDiff(BATCH_SEQ.incrementAndGet(), ReconcileDiffType.NO_PAYMENT_NO, 7003L, null);
        diffMapper.insert(diff);

        assertEquals(1, diffMapper.handle(diff.getId(), ADMIN_ID, null),
                "强制填备注的后果是管理员对 50 条同类差异逐个打上「已处理」三个字，"
                        + "然后这个功能就没人用了");
        assertNull(diffMapper.selectById(diff.getId()).getHandleNote());
    }

    // ==================================================================
    // 列表：排序与筛选
    // ==================================================================

    @Test
    @DisplayName("排序：待处理在前，同类里按枚举声明顺序（最紧急的排最前）")
    void selectPageForAdmin_ordersByHandledThenTypePriority() {
        long batchId = BATCH_SEQ.incrementAndGet();
        // 故意按「与优先级相反的顺序」插入，确保排序不是靠插入顺序碰巧对的
        for (ReconcileDiffType type : List.of(ReconcileDiffType.NO_PAYMENT_NO,
                ReconcileDiffType.BILL_ONLY, ReconcileDiffType.AMOUNT_MISMATCH,
                ReconcileDiffType.PROOF_ONLY, ReconcileDiffType.DUPLICATE_CLAIM,
                ReconcileDiffType.REJECTED_IN_BILL)) {
            diffMapper.insert(newDiff(batchId, type, null, "WX-" + type.name()));
        }

        List<ReconcileDiff> records = diffMapper
                .selectPageForAdmin(new Page<>(1, 20), batchId, null, null).getRecords();

        assertEquals(6, records.size());
        assertEquals(List.of("REJECTED_IN_BILL", "DUPLICATE_CLAIM", "PROOF_ONLY",
                        "AMOUNT_MISMATCH", "BILL_ONLY", "NO_PAYMENT_NO"),
                records.stream().map(ReconcileDiff::getDiffType).toList(),
                "ORDER BY FIELD(...) 里的字面量是编译期常量，与枚举的声明顺序可能漂移。"
                        + "漂移了不会报任何错，只表现为「最紧急的那类差异排到了中间」");
    }

    @Test
    @DisplayName("排序：已处理的排到待处理之后")
    void selectPageForAdmin_handledGoesLast() {
        long batchId = BATCH_SEQ.incrementAndGet();
        ReconcileDiff pending = newDiff(batchId, ReconcileDiffType.REJECTED_IN_BILL, null, "WX-A");
        diffMapper.insert(pending);
        ReconcileDiff handled = newDiff(batchId, ReconcileDiffType.BILL_ONLY, null, "WX-B");
        diffMapper.insert(handled);
        diffMapper.handle(handled.getId(), ADMIN_ID, "看过了");

        List<ReconcileDiff> records = diffMapper
                .selectPageForAdmin(new Page<>(1, 20), batchId, null, null).getRecords();

        assertEquals(pending.getId(), records.get(0).getId(),
                "REJECTED_IN_BILL 优先级最高，但它已处理的话仍要排在待办之后 —— "
                        + "不筛状态时（默认视图），管理员最关心的是待办");
        assertEquals(handled.getId(), records.get(1).getId());
    }

    @Test
    @DisplayName("筛选：按类型与处理状态，空串与 null 都表示不过滤")
    void selectPageForAdmin_filters() {
        long batchId = BATCH_SEQ.incrementAndGet();
        diffMapper.insert(newDiff(batchId, ReconcileDiffType.BILL_ONLY, null, "WX-C"));
        diffMapper.insert(newDiff(batchId, ReconcileDiffType.PROOF_ONLY, 7004L, "WX-D"));

        assertEquals(1, diffMapper.selectPageForAdmin(new Page<>(1, 20), batchId,
                "PROOF_ONLY", null).getRecords().size(), "按类型筛");
        assertEquals(2, diffMapper.selectPageForAdmin(new Page<>(1, 20), batchId,
                "", null).getRecords().size(), "空串表示不过滤");
        assertEquals(2, diffMapper.selectPageForAdmin(new Page<>(1, 20), batchId,
                null, null).getRecords().size(), "null 同理");
        assertEquals(0, diffMapper.selectPageForAdmin(new Page<>(1, 20), batchId,
                null, 1).getRecords().size(), "只看已处理的 —— 这几条都还没处理");
        assertEquals(0, diffMapper.selectPageForAdmin(new Page<>(1, 20), batchId,
                "NOPE", null).getRecords().size(),
                "认不出的类型返回空列表，而不是当成「不过滤」把全部捞回来");
    }

    // ==================================================================
    // 计数
    // ==================================================================

    @Test
    @DisplayName("按类型计数：一次查回全部类型")
    void countByBatchGroupByType() {
        long batchId = BATCH_SEQ.incrementAndGet();
        diffMapper.insert(newDiff(batchId, ReconcileDiffType.BILL_ONLY, null, "WX-E"));
        diffMapper.insert(newDiff(batchId, ReconcileDiffType.BILL_ONLY, null, "WX-F"));
        diffMapper.insert(newDiff(batchId, ReconcileDiffType.PROOF_ONLY, 7005L, "WX-G"));

        Map<String, Integer> counts = diffMapper.countByBatchGroupByType(batchId).stream()
                .collect(Collectors.toMap(ReconcileDiffTypeCount::getDiffType,
                        ReconcileDiffTypeCount::getCnt));

        assertEquals(2, counts.get("BILL_ONLY"));
        assertEquals(1, counts.get("PROOF_ONLY"));
        assertNull(counts.get("AMOUNT_MISMATCH"),
                "没有差异的类型不会出现在结果里（GROUP BY 的本性）——"
                        + "调用方按「查不到即 0」处理，这比补一堆 0 行省事");
    }

    @Test
    @DisplayName("批量数未处理：走一次查询，不是 N+1")
    void countUnhandledByBatchIds() {
        long batchIdA = BATCH_SEQ.incrementAndGet();
        long batchIdB = BATCH_SEQ.incrementAndGet();
        diffMapper.insert(newDiff(batchIdA, ReconcileDiffType.BILL_ONLY, null, "WX-H"));
        diffMapper.insert(newDiff(batchIdA, ReconcileDiffType.BILL_ONLY, null, "WX-I"));
        ReconcileDiff handledOne = newDiff(batchIdA, ReconcileDiffType.NO_PAYMENT_NO, 7006L, null);
        diffMapper.insert(handledOne);
        diffMapper.handle(handledOne.getId(), ADMIN_ID, "处理掉了");

        Map<Long, Integer> counts = diffMapper
                .countUnhandledByBatchIds(List.of(batchIdA, batchIdB)).stream()
                .collect(Collectors.toMap(ReconcileBatchDiffCount::getBatchId,
                        ReconcileBatchDiffCount::getCnt));

        assertEquals(2, counts.get(batchIdA), "已处理的那条不算在内");
        assertNull(counts.get(batchIdB), "一条差异都没有的批次不出现在结果里");
    }

    // ==================================================================
    // 去重用的两条查询
    // ==================================================================

    @Test
    @DisplayName("按凭证取未处理差异：只返回没处理的")
    void selectUnhandledByProofIds() {
        long proofIdA = 880001L;
        long proofIdB = 880002L;
        ReconcileDiff pending = newDiff(BATCH_SEQ.incrementAndGet(), ReconcileDiffType.PROOF_ONLY, proofIdA, "WX-J");
        diffMapper.insert(pending);
        ReconcileDiff handled = newDiff(BATCH_SEQ.incrementAndGet(), ReconcileDiffType.PROOF_ONLY, proofIdB, "WX-K");
        diffMapper.insert(handled);
        diffMapper.handle(handled.getId(), ADMIN_ID, "看过了");

        List<ReconcileDiff> rows = diffMapper.selectUnhandledByProofIds(List.of(proofIdA, proofIdB));

        assertEquals(1, rows.size(),
                "已处理的不算 —— 管理员处理完之后，同一笔再出问题就该重新报一次");
        assertEquals(ReconcileDiffType.PROOF_ONLY.name(), rows.get(0).getDiffType(),
                "去重要按 (类型, 凭证) 判定，所以类型列必须取出来");
        assertEquals(proofIdA, rows.get(0).getProofId());
    }

    @Test
    @DisplayName("按流水号取未处理差异：给 BILL_ONLY 那一类用")
    void selectUnhandledByPaymentNos() {
        diffMapper.insert(newDiff(BATCH_SEQ.incrementAndGet(), ReconcileDiffType.BILL_ONLY, null, "WX-NO-1"));
        diffMapper.insert(newDiff(BATCH_SEQ.incrementAndGet(), ReconcileDiffType.BILL_ONLY, null, "WX-NO-2"));

        List<ReconcileDiff> rows = diffMapper.selectUnhandledByPaymentNos(List.of("WX-NO-1", "WX-NO-3"));

        assertEquals(1, rows.size(),
                "BILL_ONLY 没有凭证可指，只能按流水号判重 —— 两条查询各管一半，都不完整");
        assertEquals("WX-NO-1", rows.get(0).getPaymentNo());
    }

    @Test
    @DisplayName("测试数据自检：本用例造出来的行确实落到了库里")
    void sanity() {
        long batchId = BATCH_SEQ.incrementAndGet();
        ReconcileDiff diff = newDiff(batchId, ReconcileDiffType.AMOUNT_MISMATCH, 7007L, "WX-L");
        diffMapper.insert(diff);

        assertTrue(diffMapper.selectById(diff.getId()) != null);
    }

    // ==================================================================
    // 测试数据构造
    // ==================================================================

    /**
     * 造一条差异。
     *
     * <p>{@code batchId} 直接用一个不与真实批次冲突的大数 —— 本表没有外键约束，
     * 不必先插一条批次。这也顺带说明了一件真事：<b>差异与批次的关联是应用层维护的</b>，
     * 数据库不会替我们挡「差异指向一个不存在的批次」。
     *
     * @param batchId   批次 ID
     * @param type      差异类型
     * @param proofId   凭证 ID，可为 null
     * @param paymentNo 归一化后的流水号，可为 null
     * @return 差异实体，尚未插入
     */
    private static ReconcileDiff newDiff(long batchId, ReconcileDiffType type, Long proofId, String paymentNo) {
        ReconcileDiff diff = new ReconcileDiff();
        diff.setBatchId(batchId);
        diff.setDiffType(type.name());
        diff.setProofId(proofId);
        diff.setPaymentNo(paymentNo);
        diff.setOrderNo("OD20260930000001");
        diff.setTargetType(PaymentTargetType.ORDER.name());
        diff.setBillAmount(new BigDecimal("8.00"));
        diff.setProofAmount(new BigDecimal("12.00"));
        diff.setBillTime(LocalDateTime.of(2026, 9, 30, 21, 45, 32));
        diff.setBillSummary("舞萌");
        diff.setHandled(0);
        return diff;
    }
}
