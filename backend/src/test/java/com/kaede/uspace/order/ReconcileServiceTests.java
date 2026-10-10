package com.kaede.uspace.order;

import com.kaede.uspace.common.config.UploadProperties;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.order.dto.BillFileVo;
import com.kaede.uspace.order.dto.ReconcileBatchVo;
import com.kaede.uspace.order.dto.ReconcileDiffVo;
import com.kaede.uspace.order.entity.PaymentProof;
import com.kaede.uspace.order.entity.ReconcileDiff;
import com.kaede.uspace.order.reconcile.ReconcileBillStorage;
import com.kaede.uspace.order.reconcile.ReconcileDiffType;
import com.kaede.uspace.order.reconcile.ReconcileProperties;
import com.kaede.uspace.space.FakeStoreMapper;
import com.kaede.uspace.space.entity.Store;
import com.kaede.uspace.user.FakeSysUserMapper;
import com.kaede.uspace.user.entity.SysUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReconcileService} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring、不连数据库。</b>五个协作对象全部由假实现顶替
 *（三个假 Mapper + 门店 + 用户），留档组件用一个 {@code @TempDir} 现造的目录。
 * 匹配算法本身由 {@code ReconcileMatcherTests} 单独测，这里只管<b>编排</b>：
 * 失败路径不落任何东西、认领与去重、以及几个查询接口的边界。
 *
 * <p>账单用<b>系统标准模板</b>构造 —— 它的表头最短（六列），写用例时最省事，
 * 而且它正是管理员手工整理时会用的那份格式。
 */
class ReconcileServiceTests {

    private static final Long ADMIN_ID = 9001L;

    private static final Long USER_ID = 1001L;

    /** 账单里的交易时刻，所有用例共用 */
    private static final LocalDateTime TRADED_AT = LocalDateTime.of(2026, 9, 30, 21, 45, 32);

    /** 目标 ID 连号，避免同一个用例里几条凭证撞在一起 */
    private static final AtomicLong TARGET_SEQ = new AtomicLong(7001L);

    @TempDir
    Path tempDir;

    private final FakePaymentProofMapper proofFake = new FakePaymentProofMapper();
    private final FakeReconcileBatchMapper batchFake = new FakeReconcileBatchMapper();
    private final FakeReconcileDiffMapper diffFake = new FakeReconcileDiffMapper();
    private final FakeStoreMapper storeFake = new FakeStoreMapper();
    private final FakeSysUserMapper userFake = new FakeSysUserMapper();

    private ReconcileService service;

    @BeforeEach
    void setUp() {
        ReconcileProperties properties = new ReconcileProperties();
        properties.setBillDir(tempDir.resolve("bills").toString());

        UploadProperties uploadProperties = new UploadProperties();
        uploadProperties.setDir(tempDir.resolve("uploads").toString());

        service = new ReconcileService(
                proofFake.asMapper(),
                batchFake.asMapper(),
                diffFake.asMapper(),
                storeFake.asMapper(),
                userFake.asMapper(),
                new ReconcileBillStorage(properties, uploadProperties),
                properties,
                new TradeLogService(new FakeTradeLogMapper().asMapper()));

        Store store = new Store();
        store.setId(1L);
        store.setName("测试门店");
        storeFake.seed(store);

        SysUser admin = new SysUser();
        admin.setId(ADMIN_ID);
        admin.setUsername("admin");
        admin.setNickname("管理员");
        userFake.seed(admin);
    }

    // ==================================================================
    // 正常路径
    // ==================================================================

    @Test
    @DisplayName("对账：单号金额都相符 → 批次落库、凭证被认领、零差异")
    void reconcile_matchesAndClaims() {
        PaymentProof proof = proofFake.seed(proof("4200001234", "8.00"));

        BizResult<ReconcileBatchVo> result = service.reconcile(
                billFile("2026-09-30 21:45:32,4200001234,8.00,收入,交易成功,舞萌"), ADMIN_ID);

        assertTrue(result.isSuccess(), "对账应当成功：" + result.resolveMessage());
        assertEquals(1, batchFake.size(), "批次落了库");

        ReconcileBatchVo vo = result.getData();
        assertEquals(1, vo.getMatchedCount());
        assertEquals(0, vo.getDiffCount());
        assertEquals(0, new BigDecimal("8.00").compareTo(vo.getBillAmount()));
        assertTrue(vo.isHasBillFile(), "留档成功时下载按钮可用");
        assertEquals("管理员", vo.getCreatedByName(), "执行人昵称要补上，管理员列表要认人");

        assertEquals(vo.getId(), proofFake.get(proof.getId()).getReconcileBatchId(),
                "匹配成功的凭证要回写批次 ID —— 少了这一步，重传同一份账单会产出一屏假差异");
        assertEquals(PaymentProofStatus.SUBMITTED.name(),
                proofFake.get(proof.getId()).getVerifyStatus(),
                "认领不是复核 —— 对账绝不改复核状态");
    }

    @Test
    @DisplayName("对账：账单里有一条、系统里没有 → 记一条「账单无对应凭证」")
    void reconcile_billOnly() {
        BizResult<ReconcileBatchVo> result = service.reconcile(
                billFile("2026-09-30 21:45:32,4200009999,8.00,收入,交易成功,舞萌"), ADMIN_ID);

        assertTrue(result.isSuccess());
        assertEquals(1, result.getData().getDiffCount());
        assertEquals(ReconcileDiffType.BILL_ONLY.name(), diffFake.all().get(0).getDiffType());
        assertNull(diffFake.all().get(0).getProofId(),
                "这一类说的正是「系统里没有任何凭证认领这笔钱」，没有凭证可指");
    }

    @Test
    @DisplayName("对账：系统里有凭证、账单里没有 → 记一条「凭证无对应账单」")
    void reconcile_proofOnly() {
        PaymentProof proof = proofFake.seed(proof("4200001234", "8.00"));

        BizResult<ReconcileBatchVo> result = service.reconcile(
                billFile("2026-09-30 21:45:32,4200009999,8.00,收入,交易成功,舞萌"), ADMIN_ID);

        assertTrue(result.isSuccess());
        assertEquals(2, result.getData().getDiffCount(), "两边的各一条");
        assertEquals(1, diffFake.all().stream()
                .filter(d -> ReconcileDiffType.PROOF_ONLY.name().equals(d.getDiffType()))
                .filter(d -> proof.getId().equals(d.getProofId()))
                .count());
        assertNull(proofFake.get(proof.getId()).getReconcileBatchId(),
                "没匹配上的凭证不写批次 ID —— 下次再对时它还要参与");
    }

    // ==================================================================
    // 失败路径：一条都不许落
    // ==================================================================

    @Test
    @DisplayName("认不出表头 → 报错，且库里盘上都不留痕迹")
    void reconcile_unrecognizedHeaderLeavesNothing() {
        BizResult<ReconcileBatchVo> result = service.reconcile(
                rawFile("姓名,电话,金额\n张三,13800000000,8.00"), ADMIN_ID);

        assertEquals(ErrorCode.RECONCILE_BILL_FORMAT, result.getError());
        assertEquals(0, batchFake.size(),
                "解析失败时不该留下批次记录 —— 否则会积压一批「管理员看不见、也没人会清」的孤儿数据");
        assertEquals(0, diffFake.size());
        assertTrue(billFiles().isEmpty(),
                "盘上也不该留下文件。这三步（读 / 解析 / 校验）都在落盘之前完成");
    }

    @Test
    @DisplayName("空账单 → 报「没有可对账的收款记录」，不建 0 笔的批次")
    void reconcile_emptyBill() {
        BizResult<ReconcileBatchVo> result = service.reconcile(billFile(), ADMIN_ID);

        assertEquals(ErrorCode.RECONCILE_BILL_EMPTY, result.getError(),
                "静默建成一个「0 笔、0 差异」的批次会让管理员以为「对完了、没问题」，"
                        + "而实际上他可能导错了月份");
        assertEquals(0, batchFake.size());
    }

    @Test
    @DisplayName("空账单的报错里要列出被排除的交易类型 —— 换收款账号时全靠它校准")
    void reconcile_emptyBillListsExcludedTypes() {
        // 一份「表头认得出、交易类型却一个都不在白名单里」的微信账单 ——
        // 这正是把个人零钱换成经营账户之后最可能出现的形态：
        // 文件读得出来、表头也对，只是一条记录都留不下
        BizResult<ReconcileBatchVo> result = service.reconcile(rawFile(
                "微信支付账单明细,,,,,,,,,,\n"
                        + "交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注\n"
                        + "2026-09-30 21:00:00,经营账户收款,张三,收款方备注:收款,收入,8.00,零钱,已收钱,4200001234,,/\n"
                        + "2026-09-30 21:01:00,转账,李四,转账备注:微信转账,收入,10.00,/,已存入零钱,4200009999,,/\n"),
                ADMIN_ID);

        assertEquals(ErrorCode.RECONCILE_BILL_EMPTY, result.getError());
        String message = result.resolveMessage();
        assertTrue(message.contains("经营账户收款") && message.contains("转账"),
                "要把账单里出现过的类型原样列出来：管理员把这句话发回来，"
                        + "白名单补一行就修好了。只说「没有可对账的记录」，"
                        + "他那边除了反复重试没有别的动作可做。实际文案：" + message);
    }

    @Test
    @DisplayName("账单里的交易时间一列都认不出来 → 报错，不猜一个区间")
    void reconcile_noUsableTradedAt() {
        BizResult<ReconcileBatchVo> result = service.reconcile(
                billFile("时间待定,4200001234,8.00,收入,交易成功,舞萌"), ADMIN_ID);

        assertEquals(ErrorCode.RECONCILE_BILL_FORMAT, result.getError(),
                "窗口是从账单的交易时间算出来的。一条都解析不出时随便猜一个区间，"
                        + "会算出一屏没有任何依据的差异 —— 那比报错糟得多");
        assertEquals(0, batchFake.size());
    }

    @Test
    @DisplayName("没选文件 → 报错而不是 500")
    void reconcile_noFile() {
        assertEquals(ErrorCode.RECONCILE_BILL_ENCODING,
                service.reconcile(null, ADMIN_ID).getError());
        assertEquals(ErrorCode.RECONCILE_BILL_ENCODING,
                service.reconcile(new MockMultipartFile("file", new byte[0]), ADMIN_ID).getError());
    }

    // ==================================================================
    // 差异去重
    // ==================================================================

    @Test
    @DisplayName("去重：同一条凭证身上已有未处理的同类差异 → 不再重复记")
    void reconcile_doesNotDuplicateUnhandledDiff() {
        PaymentProof proof = proofFake.seed(proof("4200001234", "8.00"));
        // 上一批已经报过「这条凭证账单里找不到」，管理员还没处理
        diffFake.seed(pendingDiff(999L, ReconcileDiffType.PROOF_ONLY, proof.getId(), "4200001234"));

        BizResult<ReconcileBatchVo> result = service.reconcile(
                billFile("2026-09-30 21:45:32,4200009999,8.00,收入,交易成功,舞萌"), ADMIN_ID);

        assertTrue(result.isSuccess());
        assertEquals(1, result.getData().getDiffCount(),
                "只记新出现的那条（账单无对应凭证）。管理员没处理完就重复堆条目，"
                        + "只会让他干脆不看了");
        assertEquals(2, diffFake.size(), "库里仍是两条：老的 + 新的");
    }

    @Test
    @DisplayName("去重：处理过的同类差异不算数 → 问题重现时重新报一次")
    void reconcile_reportsAgainAfterHandled() {
        PaymentProof proof = proofFake.seed(proof("4200001234", "8.00"));
        ReconcileDiff handled = pendingDiff(999L, ReconcileDiffType.PROOF_ONLY, proof.getId(), "4200001234");
        handled.setHandled(1);
        diffFake.seed(handled);

        BizResult<ReconcileBatchVo> result = service.reconcile(
                billFile("2026-09-30 21:45:32,4200009999,8.00,收入,交易成功,舞萌"), ADMIN_ID);

        assertEquals(2, result.getData().getDiffCount(),
                "上次处理过的那条不该永远压着同类问题 —— 同一笔又出问题时要重新报。"
                        + "去重只看 handled = 0 的那些");
    }

    // ==================================================================
    // 差异的处理
    // ==================================================================

    @Test
    @DisplayName("标记已处理：写结论，第二次返回冲突")
    void handleDiff_guardsAgainstDoubleHandling() {
        ReconcileDiff diff = diffFake.seed(pendingDiff(1L, ReconcileDiffType.BILL_ONLY, null, "4200001234"));

        assertTrue(service.handleDiff(diff.getId(), ADMIN_ID, "已联系顾客补凭证").isSuccess());
        assertEquals(1, diffFake.get(diff.getId()).getHandled());
        assertEquals("已联系顾客补凭证", diffFake.get(diff.getId()).getHandleNote());

        BizResult<Void> second = service.handleDiff(diff.getId(), ADMIN_ID, "又想改一句");
        assertEquals(ErrorCode.RECONCILE_DIFF_HANDLED, second.getError(),
                "两个管理员同时看对账结果时，后一个的结论不该覆盖前一个的");
    }

    @Test
    @DisplayName("标记已处理：备注留空是合法的")
    void handleDiff_allowsBlankNote() {
        ReconcileDiff diff = diffFake.seed(pendingDiff(1L, ReconcileDiffType.NO_PAYMENT_NO, 55L, null));

        assertTrue(service.handleDiff(diff.getId(), ADMIN_ID, "   ").isSuccess(),
                "全是空白的备注归一化成 null —— 强制填的话管理员会逐个打「已处理」然后就不用了");
        assertNull(diffFake.get(diff.getId()).getHandleNote());
    }

    @Test
    @DisplayName("标记已处理：差异不存在")
    void handleDiff_notFound() {
        assertEquals(ErrorCode.RECONCILE_DIFF_NOT_FOUND,
                service.handleDiff(404L, ADMIN_ID, null).getError());
    }

    // ==================================================================
    // 查询
    // ==================================================================

    @Test
    @DisplayName("批次详情：各类差异的条数一次查回")
    void getBatch_countsByType() {
        service.reconcile(billFile("2026-09-30 21:45:32,4200009999,8.00,收入,交易成功,舞萌"), ADMIN_ID);
        Long batchId = batchFake.all().get(0).getId();

        BizResult<ReconcileBatchVo> result = service.getBatch(batchId);

        assertTrue(result.isSuccess());
        assertEquals(1, result.getData().getDiffTypeCounts().get("BILL_ONLY"),
                "批次详情的类型筛选按钮要显示这个数");
        assertEquals(1, result.getData().getUnhandledCount(), "都还没处理");
    }

    @Test
    @DisplayName("批次详情：不存在")
    void getBatch_notFound() {
        assertEquals(ErrorCode.RECONCILE_BATCH_NOT_FOUND, service.getBatch(404L).getError());
    }

    @Test
    @DisplayName("差异列表：能按类型与处理状态筛，并补上处理人昵称")
    void listDiffs_filtersAndFillsNames() {
        ReconcileDiff pending = diffFake.seed(pendingDiff(1L, ReconcileDiffType.BILL_ONLY, null, "NO-1"));
        ReconcileDiff done = diffFake.seed(pendingDiff(1L, ReconcileDiffType.PROOF_ONLY, 7L, "NO-2"));
        service.handleDiff(done.getId(), ADMIN_ID, "看过了");

        assertEquals(2, service.listDiffs(1L, 1, 10, null, null).getTotal());
        assertEquals(1, service.listDiffs(1L, 1, 10, "BILL_ONLY", null).getTotal(), "按类型筛");
        assertEquals(1, service.listDiffs(1L, 1, 10, null, 1).getTotal(), "只看已处理");
        assertEquals(1, service.listDiffs(1L, 1, 10, null, 0).getTotal(), "只看待处理");

        ReconcileDiffVo handledVo = service.listDiffs(1L, 1, 10, "PROOF_ONLY", null).getRecords().get(0);
        assertEquals("管理员", handledVo.getHandledByName(),
                "处理人昵称由 Service 批量补 —— 逐条查会变成 N+1");
        assertTrue(handledVo.isHandled());
        assertNotNull(handledVo.getDiffTypeHint(),
                "每类差异要带一句「这意味着什么、该做什么」—— 六类的处置动作完全不同，"
                        + "让管理员在页面上现推是没必要的负担");
        assertEquals(pending.getId(), diffFake.all().get(0).getId());
    }

    @Test
    @DisplayName("下载账单原文件：留档成功时能读回原样")
    void loadBillFile_returnsContent() {
        service.reconcile(billFile("2026-09-30 21:45:32,4200009999,8.00,收入,交易成功,舞萌"), ADMIN_ID);
        Long batchId = batchFake.all().get(0).getId();

        BizResult<BillFileVo> result = service.loadBillFile(batchId);

        assertTrue(result.isSuccess());
        assertEquals("账单.csv", result.getData().fileName());
        String content = new String(result.getData().content(), StandardCharsets.UTF_8);
        assertTrue(content.contains("4200009999"), "读回来的应当就是当初传上去的那份");
    }

    @Test
    @DisplayName("下载账单原文件：批次不存在 / 没留档，分别报两个不同的码")
    void loadBillFile_missing() {
        assertEquals(ErrorCode.RECONCILE_BATCH_NOT_FOUND, service.loadBillFile(404L).getError());

        service.reconcile(billFile("2026-09-30 21:45:32,4200009999,8.00,收入,交易成功,舞萌"), ADMIN_ID);
        Long batchId = batchFake.all().get(0).getId();
        batchFake.get(batchId).setBillFilePath(null);   // 模拟留档失败

        assertEquals(ErrorCode.RECONCILE_BILL_FILE_MISSING, service.loadBillFile(batchId).getError(),
                "「批次不存在」与「文件没留档」要分开：前者刷新列表，后者说明这次对账没存档，"
                        + "但**对账本身是成功的**");
    }

    // ==================================================================
    // 测试数据构造
    // ==================================================================

    /**
     * 造一份系统标准模板格式的账单文件。
     *
     * @param dataLines 数据行，可为空（只留表头 → 空账单）
     * @return 上传用的文件
     */
    private static MultipartFile billFile(String... dataLines) {
        StringBuilder sb = new StringBuilder("交易时间,交易单号,金额(元),收/支,交易状态,备注\n");
        for (String line : dataLines) {
            sb.append(line).append('\n');
        }
        return new MockMultipartFile("file", "账单.csv", "text/csv",
                sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 造一份任意内容的文件，用于「认不出格式」那类用例。
     *
     * @param content 内容
     * @return 上传用的文件
     */
    private static MultipartFile rawFile(String content) {
        return new MockMultipartFile("file", "别的东西.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 造一条凭证，提交时刻取账单交易时刻之前一点点（落在候选窗口内）。
     *
     * @param paymentNo 流水号
     * @param amount    金额
     * @return 凭证实体
     */
    private static PaymentProof proof(String paymentNo, String amount) {
        long seq = TARGET_SEQ.incrementAndGet();
        PaymentProof proof = new PaymentProof();
        proof.setTargetType(PaymentTargetType.ORDER.name());
        proof.setTargetId(seq);
        proof.setOrderNo("OD" + seq);
        proof.setUserId(USER_ID);
        proof.setAmount(new BigDecimal(amount));
        proof.setProofUrl("/uploads/proof/it-" + seq + ".jpg");
        proof.setPaymentNo(paymentNo);
        proof.setVerifyStatus(PaymentProofStatus.SUBMITTED.name());
        proof.setCreatedAt(TRADED_AT.minusHours(1));
        return proof;
    }

    /**
     * 造一条待处理的差异，用于预置「上一批已经报过」的前置状态。
     *
     * @param batchId   批次 ID
     * @param type      类型
     * @param proofId   凭证 ID，可为 null
     * @param paymentNo 流水号，可为 null
     * @return 差异实体
     */
    private static ReconcileDiff pendingDiff(Long batchId, ReconcileDiffType type,
                                             Long proofId, String paymentNo) {
        ReconcileDiff diff = new ReconcileDiff();
        diff.setBatchId(batchId);
        diff.setDiffType(type.name());
        diff.setProofId(proofId);
        diff.setPaymentNo(paymentNo);
        diff.setHandled(0);
        return diff;
    }

    /**
     * 列出留档目录里的文件，用于验证「失败时盘上不留痕」。
     *
     * @return 文件名列表
     */
    private List<String> billFiles() {
        Path bills = tempDir.resolve("bills");
        if (!Files.isDirectory(bills)) {
            // 一次都没落过盘时目录还不存在 —— 那正是「失败时不留痕」想要的结果
            return List.of();
        }
        try (var stream = Files.list(bills)) {
            return stream.map(path -> path.getFileName().toString()).toList();
        } catch (IOException e) {
            throw new IllegalStateException("列目录失败", e);
        }
    }
}
