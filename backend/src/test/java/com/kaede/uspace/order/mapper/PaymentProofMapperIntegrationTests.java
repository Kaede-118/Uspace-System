package com.kaede.uspace.order.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kaede.uspace.order.PaymentProofStatus;
import com.kaede.uspace.order.PaymentTargetType;
import com.kaede.uspace.order.entity.PaymentProof;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PaymentProofMapper} 的集成测试，连本机真实 MySQL。
 *
 * <p><b>三重保护不污染开发库</b>：{@code @Transactional} 每个用例结束后自动回滚；
 * {@code @EnabledIfEnvironmentVariable} 让没配 {@code MYSQL_PASSWORD} 的机器整个跳过；
 * 目标 ID 取 {@link #TARGET_SEQ} 起的连号，万一回滚失效也一眼能认出来。
 *
 * <p><b>为什么假 Mapper 不够、必须连真库</b>，本表有三处只有真库才验证得到：
 * <ul>
 *   <li><b>{@code ON DUPLICATE KEY UPDATE}</b> —— 提交凭证是 upsert，
 *       冲突分支里哪几列被更新、哪几列被清空，写错不会有任何报错，
 *       只表现为「用户改不了自己传错的图」或「驳回结论被静默清掉」</li>
 *   <li><b>{@code uk_target} 唯一键真的存在</b> —— 假 Mapper 靠手写查找模拟判重，
 *       建表时漏了这个键，真库里就会出现同一个目标的多条凭证，
 *       而后台列表看起来完全正常</li>
 *   <li><b>本表没有 {@code deleted} 列</b> —— 照抄别的 Mapper 加一句
 *       {@code deleted = 0} 会直接 {@code Unknown column}，这一条也必须真库才暴露</li>
 * </ul>
 *
 * <p><b>断言都盯着自己插入的那几条</b>：{@code biz_payment_proof} 没有门店那样的
 * 隔离维度，所以不去断言「总数是多少」—— 那会把测试绑死在「库是空的」这个前提上
 *（公告那组用例踩过这个坑）。改为验证相对性质：顺序、筛选、字段的增删。
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+",
        disabledReason = "未配置 MYSQL_PASSWORD，跳过需要真实数据库的集成测试")
class PaymentProofMapperIntegrationTests {

    /**
     * 目标 ID 的起点，取大数以免与真实数据撞上。
     *
     * <p>每个用例内部再往后连号分配 —— 同一批插入若共用同一个
     * {@code (target_type, target_id)}，会全部撞到 {@code uk_target} 上，
     * 表现为「明明插了三条却只查得到一条」，而那是唯一的正常行为。
     */
    private static final AtomicLong TARGET_SEQ = new AtomicLong(999901L);

    /** 测试用的管理员 ID，同理取大数 */
    private static final Long ADMIN_ID = 999901L;

    @Autowired
    private PaymentProofMapper proofMapper;

    // ==================================================================
    // 落库与判重
    // ==================================================================

    @Test
    @DisplayName("插入：审计字段由 SQL 自己填，复核状态落成默认的待复核")
    void upsert_insertsRow() {
        PaymentProof saved = save(newProof(PaymentTargetType.ORDER));

        assertNotNull(saved, "插入之后应当查得到");
        assertNotNull(saved.getId(), "主键由数据库自增分配");
        assertNotNull(saved.getCreatedAt(), "created_at 是 NOT NULL 且由 SQL 里的 NOW() 填");
        assertNotNull(saved.getUpdatedAt());
        assertEquals(PaymentProofStatus.SUBMITTED.name(), saved.getVerifyStatus(),
                "新凭证默认待复核 —— 建表脚本里那个 DEFAULT 是第二道防线");
        assertEquals(0, new BigDecimal("22.00").compareTo(saved.getAmount()),
                "金额原样落库，DECIMAL 不丢精度");
    }

    @Test
    @DisplayName("判重：同一目标再写一次走更新，不产生第二行")
    void upsert_updatesOnConflict() {
        PaymentProof first = save(newProof(PaymentTargetType.ORDER));

        PaymentProof second = newProof(PaymentTargetType.ORDER);
        second.setTargetType(first.getTargetType());
        second.setTargetId(first.getTargetId());
        second.setProofUrl("/uploads/proof/replaced.jpg");
        second.setPaymentNo("WX-TX-2");
        int affected = proofMapper.upsert(second);

        assertEquals(2, affected, "MySQL 对「冲突走更新」记的是 2 —— 调用方不该拿它判业务");
        PaymentProof reloaded = proofMapper.selectByTarget(first.getTargetType(), first.getTargetId());
        assertEquals(first.getId(), reloaded.getId(), "是同一行被更新，不是插了第二条");
        assertEquals("/uploads/proof/replaced.jpg", reloaded.getProofUrl());
        assertEquals("WX-TX-2", reloaded.getPaymentNo());
    }

    @Test
    @DisplayName("判重：重交会把复核结论整个清掉，并保留创建时刻")
    void upsert_resetsReviewConclusion() {
        PaymentProof saved = save(newProof(PaymentTargetType.BOOKING));
        proofMapper.reject(saved.getId(), ADMIN_ID, "看不清");
        PaymentProof rejected = proofMapper.selectById(saved.getId());
        assertEquals(PaymentProofStatus.REJECTED.name(), rejected.getVerifyStatus());
        LocalDateTime createdAt = rejected.getCreatedAt();

        PaymentProof again = newProof(PaymentTargetType.BOOKING);
        again.setTargetType(rejected.getTargetType());
        again.setTargetId(rejected.getTargetId());
        again.setProofUrl("/uploads/proof/newer.jpg");
        proofMapper.upsert(again);

        PaymentProof after = proofMapper.selectById(rejected.getId());
        assertEquals(PaymentProofStatus.SUBMITTED.name(), after.getVerifyStatus(),
                "重交之后要回到待复核，不然管理员再也看不到它");
        assertNull(after.getRejectReason(), "旧原因要清掉，否则会误导下一次复核");
        assertNull(after.getConfirmedBy(), "旧的复核人也要清掉");
        assertNull(after.getConfirmedAt());
        assertEquals(createdAt, after.getCreatedAt(),
                "created_at 记的是第一次提交的时刻，重交不该刷新它");
    }

    @Test
    @DisplayName("判重：不同目标各占一行，互不影响")
    void upsert_keepsTargetsApart() {
        PaymentProof a = save(newProof(PaymentTargetType.ORDER));
        PaymentProof b = save(newProof(PaymentTargetType.ORDER));
        PaymentProof c = save(newProof(PaymentTargetType.BOOKING));

        assertNotNull(proofMapper.selectByTarget(PaymentTargetType.ORDER.name(), a.getTargetId()));
        assertNotNull(proofMapper.selectByTarget(PaymentTargetType.ORDER.name(), b.getTargetId()),
                "同一类型的两个目标各占一行 —— uk_target 是 (类型, ID) 两列一起比");
        assertNotNull(proofMapper.selectByTarget(PaymentTargetType.BOOKING.name(), c.getTargetId()),
                "类型不同但 ID 相同，也是两行");
    }

    // ==================================================================
    // OCR 识别结果的写入与覆盖
    // ==================================================================

    /*
     * 这一组是「只有真库才验证得到」的典型：{@code upsert} 的冲突分支是一长串
     * ON DUPLICATE KEY UPDATE 赋值，写漏一列、或者把某列写成 NULL，
     * 都不报任何错 —— 只有再查一次才知道值对不对。
     * 而识别结果这一组还有它自己的风险：它们是【旧图】的数据，
     * 换图之后若不覆盖，管理员就会拿第一张图的金额去核第二张图。
     */

    @Test
    @DisplayName("识别结果：插入时三列一起写进去")
    void upsert_persistsOcrFields() {
        PaymentProof proof = newProof(PaymentTargetType.ORDER);
        proof.setOcrPaymentNo("4200001234202609301234567890");
        proof.setOcrAmount(new BigDecimal("8.00"));
        proof.setOcrText("支付成功\n¥8.00\n交易单号\n4200001234202609301234567890");

        PaymentProof saved = save(proof);

        assertEquals("4200001234202609301234567890", saved.getOcrPaymentNo(),
                "识别出的单号是「用户为什么填成这个」的解释，也是识别质量的观测数据");
        assertEquals(0, saved.getOcrAmount().compareTo(new BigDecimal("8.00")),
                "金额要落库 —— 后台靠它跟应付额比对");
        assertTrue(saved.getOcrText().contains("支付成功"), "原文要落库，供管理员复核时参考");
    }

    @Test
    @DisplayName("识别结果：换图重交时被新值整个覆盖")
    void upsert_replacesOcrFieldsOnConflict() {
        PaymentProof first = newProof(PaymentTargetType.ORDER);
        first.setOcrPaymentNo("4200001111111111111111111111");
        first.setOcrAmount(new BigDecimal("22.00"));
        first.setOcrText("第一张图的原文");
        save(first);

        PaymentProof second = newProof(PaymentTargetType.ORDER);
        second.setTargetType(first.getTargetType());
        second.setTargetId(first.getTargetId());
        second.setProofUrl("/uploads/proof/another.jpg");
        second.setOcrPaymentNo("4200002222222222222222222222");
        second.setOcrAmount(new BigDecimal("8.00"));
        second.setOcrText("第二张图的原文");
        proofMapper.upsert(second);

        PaymentProof reloaded = proofMapper.selectByTarget(first.getTargetType(), first.getTargetId());
        assertEquals("4200002222222222222222222222", reloaded.getOcrPaymentNo(),
                "旧图的识别结果已经不对应任何东西了 —— 留着它，管理员会拿第一张图的数据"
                        + "去核第二张图，而页面上看不出这个错");
        assertEquals(0, reloaded.getOcrAmount().compareTo(new BigDecimal("8.00")), "金额同理");
        assertEquals("第二张图的原文", reloaded.getOcrText(), "原文同理");
    }

    @Test
    @DisplayName("识别结果：新图什么都没认出时，旧值被清成 null")
    void upsert_clearsOcrFieldsWhenNewImageRecognizesNothing() {
        PaymentProof first = newProof(PaymentTargetType.ORDER);
        first.setOcrPaymentNo("4200001111111111111111111111");
        first.setOcrAmount(new BigDecimal("22.00"));
        first.setOcrText("第一张图的原文");
        save(first);

        PaymentProof second = newProof(PaymentTargetType.ORDER);
        second.setTargetType(first.getTargetType());
        second.setTargetId(first.getTargetId());
        second.setProofUrl("/uploads/proof/unreadable.jpg");
        // 刻意不设 ocr 三列 —— 新图没识别出任何东西，传上来的就是 null
        proofMapper.upsert(second);

        PaymentProof reloaded = proofMapper.selectByTarget(first.getTargetType(), first.getTargetId());
        assertNull(reloaded.getOcrPaymentNo(),
                "「新图没识别出」必须写成 null，而不是留着旧图的值 —— "
                        + "后者的表现是后台显示着一份与当前截图毫无关系的数据");
        assertNull(reloaded.getOcrAmount());
        assertNull(reloaded.getOcrText());
    }

    // ==================================================================
    // 复核的状态守卫
    // ==================================================================

    @Test
    @DisplayName("复核：状态守卫挡住第二次处理")
    void confirmAndReject_guardedByStatus() {
        PaymentProof proof = save(newProof(PaymentTargetType.ORDER));

        assertEquals(1, proofMapper.confirm(proof.getId(), ADMIN_ID), "第一次确认应当成功");
        assertEquals(0, proofMapper.confirm(proof.getId(), ADMIN_ID),
                "第二次确认拿到 0 行 —— 两位管理员同时点击时，后点的那个要被告知已被处理");
        assertEquals(0, proofMapper.reject(proof.getId(), ADMIN_ID, "已确认的不能再驳回"),
                "已确认的凭证也驳回不了 —— 复核是资金结论，不能翻来覆去");
        assertEquals(PaymentProofStatus.CONFIRMED.name(),
                proofMapper.selectById(proof.getId()).getVerifyStatus(), "状态没有被后两次调用改动");
    }

    @Test
    @DisplayName("复核：驳回写下原因，确认清掉原因")
    void reject_recordsReason() {
        PaymentProof proof = save(newProof(PaymentTargetType.MONTHLY_CARD));

        assertEquals(1, proofMapper.reject(proof.getId(), ADMIN_ID, "金额对不上"));
        PaymentProof rejected = proofMapper.selectById(proof.getId());
        assertEquals(PaymentProofStatus.REJECTED.name(), rejected.getVerifyStatus());
        assertEquals("金额对不上", rejected.getRejectReason());
        assertEquals(ADMIN_ID, rejected.getConfirmedBy());
        assertNotNull(rejected.getConfirmedAt());

        // 反过来：驳回之后再确认也要被挡住
        assertEquals(0, proofMapper.confirm(proof.getId(), ADMIN_ID));
    }

    // ==================================================================
    // 流水号重复检测
    // ==================================================================

    @Test
    @DisplayName("流水号：同号被两笔引用时能数出来，并给两条都打上标记")
    void duplicatePaymentNo_detectedAndFlagged() {
        String paymentNo = "WX-TX-" + System.nanoTime();
        PaymentProof first = newProof(PaymentTargetType.ORDER);
        first.setPaymentNo(paymentNo);
        PaymentProof second = newProof(PaymentTargetType.ORDER);
        second.setPaymentNo(paymentNo);
        PaymentProof savedFirst = save(first);
        PaymentProof savedSecond = save(second);

        assertEquals(2, proofMapper.countByPaymentNo(paymentNo),
                "走 idx_payment_no 的一次索引扫描 —— 这是「一张截图付两单」唯一的痕迹");
        assertEquals(2, proofMapper.markDuplicateByPaymentNo(paymentNo),
                "两条都要标 —— 冲突是双向的，谁先谁后没有意义");
        assertEquals("DUPLICATE_PAYMENT_NO",
                proofMapper.selectById(savedFirst.getId()).getRiskFlag());
        assertEquals("DUPLICATE_PAYMENT_NO",
                proofMapper.selectById(savedSecond.getId()).getRiskFlag());
    }

    @Test
    @DisplayName("重交：旧的风险标记被清掉，随后由 Service 重新检测")
    void upsert_clearsRiskFlag() {
        PaymentProof proof = newProof(PaymentTargetType.ORDER);
        proof.setPaymentNo("WX-TX-DUP-" + System.nanoTime());
        PaymentProof saved = save(proof);
        proofMapper.markDuplicateByPaymentNo(saved.getPaymentNo());
        assertEquals("DUPLICATE_PAYMENT_NO",
                proofMapper.selectById(saved.getId()).getRiskFlag(), "先确认标记确实打上了");

        PaymentProof again = newProof(PaymentTargetType.ORDER);
        again.setTargetType(saved.getTargetType());
        again.setTargetId(saved.getTargetId());
        again.setProofUrl("/uploads/proof/corrected.jpg");
        again.setPaymentNo("WX-TX-CORRECTED");
        proofMapper.upsert(again);

        assertNull(proofMapper.selectById(saved.getId()).getRiskFlag(),
                "改了流水号之后不该继续背着上一轮的结论 —— "
                        + "这套 upsert 的列清单里少了 risk_flag 就是一列的事，且不报任何错");
    }

    @Test
    @DisplayName("流水号：没填的单子不该被误标")
    void duplicatePaymentNo_ignoresBlank() {
        PaymentProof a = save(newProof(PaymentTargetType.ORDER));
        PaymentProof b = save(newProof(PaymentTargetType.ORDER));

        assertEquals(0, proofMapper.countByPaymentNo("不存在的流水号"), "查不到就是 0");
        assertNull(proofMapper.selectById(a.getId()).getRiskFlag(), "没填流水号的单子不该被标风险");
        assertNull(proofMapper.selectById(b.getId()).getRiskFlag());
    }

    // ==================================================================
    // 后台列表
    // ==================================================================

    @Test
    @DisplayName("后台列表：有风险的排最前，其次待复核，最后已处理")
    void selectPageForAdmin_ordersByRiskThenPending() {
        PaymentProof pending = save(newProof(PaymentTargetType.ORDER));

        PaymentProof risky = newProof(PaymentTargetType.ORDER);
        risky.setRiskFlag("DUPLICATE_PAYMENT_NO");
        PaymentProof savedRisky = save(risky);

        PaymentProof confirmed = save(newProof(PaymentTargetType.ORDER));
        proofMapper.confirm(confirmed.getId(), ADMIN_ID);

        // 只挑本用例这三条 —— 库里可能还有别的凭证
        List<Long> mine = proofMapper.selectPageForAdmin(new Page<>(1, 500), null)
                .getRecords().stream()
                .map(PaymentProof::getId)
                .filter(id -> id.equals(pending.getId())
                        || id.equals(savedRisky.getId())
                        || id.equals(confirmed.getId()))
                .toList();

        assertEquals(3, mine.size(), "三条都要在");
        assertEquals(savedRisky.getId(), mine.get(0),
                "有风险的排最前 —— 那是纯信任制下最该先看一眼的一类");
        assertEquals(pending.getId(), mine.get(1), "其次是待复核的（待办）");
        assertEquals(confirmed.getId(), mine.get(2), "处理完的沉到最后");
    }

    @Test
    @DisplayName("后台列表：按状态筛选只返回那一种")
    void selectPageForAdmin_filtersByStatus() {
        PaymentProof pending = save(newProof(PaymentTargetType.PRODUCT));
        PaymentProof done = save(newProof(PaymentTargetType.PRODUCT));
        proofMapper.confirm(done.getId(), ADMIN_ID);

        List<Long> pendingIds = proofMapper.selectPageForAdmin(
                        new Page<>(1, 500), PaymentProofStatus.SUBMITTED.name())
                .getRecords().stream().map(PaymentProof::getId).toList();
        List<Long> confirmedIds = proofMapper.selectPageForAdmin(
                        new Page<>(1, 500), PaymentProofStatus.CONFIRMED.name())
                .getRecords().stream().map(PaymentProof::getId).toList();

        assertTrue(pendingIds.contains(pending.getId()), "待复核的该出现在待复核里");
        assertTrue(!pendingIds.contains(done.getId()), "已核对的不该混进待办");
        assertTrue(confirmedIds.contains(done.getId()));
        assertTrue(!confirmedIds.contains(pending.getId()));
    }

    @Test
    @DisplayName("后台列表：状态传空串时不过滤（后台默认视图）")
    void selectPageForAdmin_blankStatusMeansAll() {
        PaymentProof pending = save(newProof(PaymentTargetType.PRODUCT));

        List<Long> ids = proofMapper.selectPageForAdmin(new Page<>(1, 500), "")
                .getRecords().stream().map(PaymentProof::getId).toList();

        assertTrue(ids.contains(pending.getId()),
                "空串要走「不过滤」那一支 —— 前端不传筛选时就是这个值");
    }

    // ==================================================================
    // 测试数据构造
    // ==================================================================

    /**
     * 写库并取回（连带主键）。
     *
     * <p>⚠️ <b>不能 upsert 之后直接读实体上的 {@code id}</b>：那是个手写的
     * {@code @Insert}，MyBatis 不回填主键（理由见 {@link PaymentProofMapper#upsert}）。
     * 需要 ID 时一律重新查一次 —— 这也是生产代码的做法。
     *
     * @param proof 凭证（不必先设 id）
     * @return 库里的那一条
     */
    private PaymentProof save(PaymentProof proof) {
        proofMapper.upsert(proof);
        return proofMapper.selectByTarget(proof.getTargetType(), proof.getTargetId());
    }

    /**
     * 造一条凭证（还没落库）。
     *
     * <p>目标 ID 走连号分配，避免同一用例内的几条互相撞 {@code uk_target}。
     *
     * @param type 收款类型
     * @return 凭证实体
     */
    private static PaymentProof newProof(PaymentTargetType type) {
        long seq = TARGET_SEQ.incrementAndGet();
        PaymentProof proof = new PaymentProof();
        proof.setTargetType(type.name());
        proof.setTargetId(seq);
        proof.setOrderNo("IT" + seq);
        proof.setUserId(999901L);
        proof.setAmount(new BigDecimal("22.00"));
        proof.setProofUrl("/uploads/proof/it-" + seq + ".jpg");
        proof.setVerifyStatus(PaymentProofStatus.SUBMITTED.name());
        return proof;
    }

    // ==================================================================
    // 对账（Phase 5）
    // ==================================================================

    @Test
    @DisplayName("候选：窗口是闭区间 —— 恰好落在边界上的凭证算在内")
    void candidates_windowIsInclusive() {
        PaymentProof proof = save(newProof(PaymentTargetType.ORDER));
        LocalDateTime createdAt = proof.getCreatedAt();

        List<PaymentProof> hit = proofMapper.selectCandidatesForReconcile(createdAt, createdAt);
        List<PaymentProof> beforeWindow = proofMapper.selectCandidatesForReconcile(
                createdAt.minusDays(2), createdAt.minusDays(1));

        assertTrue(hit.stream().anyMatch(p -> p.getId().equals(proof.getId())),
                "两端都用 >= 与 <= 而不是 > 与 < —— 恰好落在边界上的那一笔不该被漏掉，"
                        + "而它会变成一条「系统有、账单无」的假差异");
        assertTrue(beforeWindow.stream().noneMatch(p -> p.getId().equals(proof.getId())),
                "窗口之外的不该出现，反过来验证前一条不是因为查询压根没生效");
    }

    @Test
    @DisplayName("候选：已被别的批次认领的凭证仍然要取到 —— 这是最容易写错的一处")
    void candidates_includeClaimedProofs() {
        PaymentProof claimed = save(newProof(PaymentTargetType.ORDER));
        proofMapper.markReconciled(999801L, List.of(claimed.getId()));
        LocalDateTime createdAt = claimed.getCreatedAt();

        List<PaymentProof> candidates = proofMapper.selectCandidatesForReconcile(createdAt, createdAt);

        assertTrue(candidates.stream().anyMatch(p -> p.getId().equals(claimed.getId())),
                "候选查询刻意不筛 reconcile_batch_id。筛了的话，「这个单号已被之前的批次认领过」"
                        + "这个信息就丢了 —— 重传同一份账单时账单里每一笔都找不到「在等的」凭证，"
                        + "全部变成「账单有、系统无」的假差异，一屏假警报。"
                        + "而且这个 bug 单测测不出来：假 Mapper 会照着实现写，两边一起错");
    }

    @Test
    @DisplayName("候选：被驳回的凭证也要取到 —— 匹配器靠它报「驳回后有款」")
    void candidates_includeRejectedProofs() {
        PaymentProof rejected = save(newProof(PaymentTargetType.PRODUCT));
        proofMapper.reject(rejected.getId(), ADMIN_ID, "截图看不清");
        LocalDateTime createdAt = rejected.getCreatedAt();

        List<PaymentProof> candidates = proofMapper.selectCandidatesForReconcile(createdAt, createdAt);

        assertTrue(candidates.stream().anyMatch(p -> p.getId().equals(rejected.getId())),
                "「账单收到了钱、而系统里那条凭证被驳回了」是最紧急的一类差异，"
                        + "它的原料正是这些被驳回的凭证。候选查询因此在 SQL 里对状态一律不筛，"
                        + "由匹配器在内存里分三组");
    }

    @Test
    @DisplayName("认领：只写第一次，第二次返回 0")
    void markReconciled_onlyFirstWins() {
        PaymentProof proof = save(newProof(PaymentTargetType.ORDER));

        int first = proofMapper.markReconciled(999802L, List.of(proof.getId()));
        int second = proofMapper.markReconciled(999803L, List.of(proof.getId()));

        assertEquals(1, first, "第一次认领生效");
        assertEquals(0, second,
                "两个批次并发跑时，同一笔凭证只会被先到的那个认领走 ——"
                        + "调用方拿这个行数与预期一比就知道有没有被抢");
        assertEquals(999802L,
                proofMapper.selectByTarget(proof.getTargetType(), proof.getTargetId()).getReconcileBatchId(),
                "批次 ID 只写第一次，于是「这笔是哪一批第一次对出来的」始终查得到。"
                        + "这一条同时让重传同一份账单变成幂等的");
    }

    @Test
    @DisplayName("认领：绝不改 verify_status —— 对账不是复核")
    void markReconciled_doesNotTouchVerifyStatus() {
        PaymentProof proof = save(newProof(PaymentTargetType.ORDER));
        assertEquals(PaymentProofStatus.SUBMITTED.name(), proof.getVerifyStatus(), "前提：新凭证是待复核");

        proofMapper.markReconciled(999804L, List.of(proof.getId()));

        assertEquals(PaymentProofStatus.SUBMITTED.name(),
                proofMapper.selectByTarget(proof.getTargetType(), proof.getTargetId()).getVerifyStatus(),
                "「认领了顺便确认一下」看起来自然，但那样对账就成了第二个资金结论入口，"
                        + "管理员再也分不清「这笔是我看过截图认下的」还是「系统自己对上的」");
    }

    @Test
    @DisplayName("认领：一批多条一次写完，已认领的那些被跳过")
    void markReconciled_bulkSkipsClaimed() {
        PaymentProof already = save(newProof(PaymentTargetType.ORDER));
        PaymentProof fresh = save(newProof(PaymentTargetType.BOOKING));
        proofMapper.markReconciled(999805L, List.of(already.getId()));

        int affected = proofMapper.markReconciled(999806L, List.of(already.getId(), fresh.getId()));

        assertEquals(1, affected, "两条里只有一条真的被改 —— 调用方据此把批次上的计数改回实际值");
        assertEquals(999805L,
                proofMapper.selectByTarget(already.getTargetType(), already.getTargetId()).getReconcileBatchId(),
                "已被认领的保持原批次不变");
        assertEquals(999806L,
                proofMapper.selectByTarget(fresh.getTargetType(), fresh.getTargetId()).getReconcileBatchId());
    }
}
