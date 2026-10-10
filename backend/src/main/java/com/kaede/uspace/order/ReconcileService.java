package com.kaede.uspace.order;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.order.dto.BillFileVo;
import com.kaede.uspace.order.dto.ReconcileBatchVo;
import com.kaede.uspace.order.dto.ReconcileDiffVo;
import com.kaede.uspace.order.entity.PaymentProof;
import com.kaede.uspace.order.entity.ReconcileBatch;
import com.kaede.uspace.order.entity.ReconcileDiff;
import com.kaede.uspace.order.mapper.PaymentProofMapper;
import com.kaede.uspace.order.mapper.ReconcileBatchDiffCount;
import com.kaede.uspace.order.mapper.ReconcileBatchMapper;
import com.kaede.uspace.order.mapper.ReconcileDiffMapper;
import com.kaede.uspace.order.mapper.ReconcileDiffTypeCount;
import com.kaede.uspace.order.reconcile.BillParseException;
import com.kaede.uspace.order.reconcile.BillParseResult;
import com.kaede.uspace.order.reconcile.BillParserDispatcher;
import com.kaede.uspace.order.reconcile.ReconcileBillStorage;
import com.kaede.uspace.order.reconcile.ReconcileMatcher;
import com.kaede.uspace.order.reconcile.ReconcilePlan;
import com.kaede.uspace.order.reconcile.ReconcileProperties;
import com.kaede.uspace.order.reconcile.ReconcileTexts;
import com.kaede.uspace.space.mapper.StoreMapper;
import com.kaede.uspace.user.entity.SysUser;
import com.kaede.uspace.user.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 对账（支付改造 Phase 5）。
 *
 * <p>把收款账号导出的账单与系统里的付款凭证勾稽起来，输出差异 ——
 * 让管理员从「逐条看 100%」降到「只看差异」。它是「钱到没到账」
 *（{@code PaymentProofService}）之后的第二问：「这一天到底收到了多少、
 * 跟系统里记的对不对得上」。
 *
 * <h3>一次对账的执行顺序，每一步都有理由</h3>
 *
 * <ol>
 *   <li><b>读字节 → 解析 → 校验非空</b>。
 *       这三步<b>不落任何东西</b>：解析失败时库里、盘上都不该留下痕迹，
 *       否则会积压一批「管理员看不见、也没人会清」的孤儿文件</li>
 *   <li><b>算窗口 → 取候选 → 匹配</b>。候选查询只按时间窗口取，
 *       <b>不筛状态、不筛认领</b> —— 理由见 {@code PaymentProofMapper#selectCandidatesForReconcile}，
 *       那是整个功能最容易写错的一处</li>
 *   <li><b>落盘留档</b>（失败只记 warn，不阻断）</li>
 *   <li><b>插批次</b> —— 匹配已经算完，所以计数能一次写进去，
 *       不必「先插一条空批次再更新」</li>
 *   <li><b>认领凭证</b>（写 {@code reconcile_batch_id}）</li>
 *   <li><b>差异去重后插入</b></li>
 * </ol>
 *
 * <h3>为什么不做「先预览再确认」，也不做撤销批次</h3>
 *
 * <p>对账的副作用面极小：它不改任何业务状态（不碰订单、不碰 {@code verify_status}）。
 * 误传一份「已经对过的」账单，走的是「已被认领」那一支，零差异；
 * 误传一份「日期不对的」，可能产生几条「凭证无对应账单」，而那几类
 * <b>不写</b> {@code reconcile_batch_id}，同样零副作用。
 * 为一个小概率的误操作引入「待确认」中间态、临时文件清理、防重复确认，
 * 不值得。降险靠的是「上传完直接跳进详情页 + 列表上把未处理差异数做成显眼列」。
 *
 * @see com.kaede.uspace.order.reconcile.ReconcileMatcher 匹配算法本身
 */
@Slf4j
@Service
public class ReconcileService {

    private final PaymentProofMapper proofMapper;
    private final ReconcileBatchMapper batchMapper;
    private final ReconcileDiffMapper diffMapper;
    private final StoreMapper storeMapper;
    private final SysUserMapper userMapper;
    private final ReconcileBillStorage billStorage;
    private final ReconcileProperties properties;

    /**
     * 交易流水。对账认下来的每一笔记一行「账单对账确认」（2026-10-10 加）——
     * 那是「钱确实进了口袋」的唯一凭据，与「有没有人复核过」分开记。
     */
    private final TradeLogService tradeLogService;

    /**
     * 构造器注入。
     *
     * @param proofMapper     凭证数据访问，用于取候选与回写认领
     * @param batchMapper     批次数据访问
     * @param diffMapper      差异数据访问
     * @param storeMapper     取当前门店（批次要记在哪个店上）
     * @param userMapper      批量补管理员昵称
     * @param billStorage     账单原文件的留档与读取
     * @param properties      窗口天数与候选数预警阈值
     * @param tradeLogService 交易流水，记「账单对账确认」
     */
    public ReconcileService(PaymentProofMapper proofMapper,
                            ReconcileBatchMapper batchMapper,
                            ReconcileDiffMapper diffMapper,
                            StoreMapper storeMapper,
                            SysUserMapper userMapper,
                            ReconcileBillStorage billStorage,
                            ReconcileProperties properties,
                            TradeLogService tradeLogService) {
        this.proofMapper = proofMapper;
        this.batchMapper = batchMapper;
        this.diffMapper = diffMapper;
        this.storeMapper = storeMapper;
        this.userMapper = userMapper;
        this.billStorage = billStorage;
        this.properties = properties;
        this.tradeLogService = tradeLogService;
    }

    // ==================================================================
    // 上传并执行对账
    // ==================================================================

    /**
     * 把「被交易类型挡下的那些类型」拼成一句提示。
     *
     * <p>只给管理员看，所以是中文顿号分隔的一串，不排序、不去重
     *（去重已经在解析层做过了，且保留出现顺序，读起来与账单本身一致）。
     *
     * @param parsed 解析结果
     * @return 提示片段；没有被排除的类型时返回空串（不占地方）
     */
    private static String excludedTypesHint(BillParseResult parsed) {
        List<String> types = parsed.excludedTypes();
        if (types.isEmpty()) {
            return "";
        }
        return "；账单里被排除的交易类型有：" + String.join("、", types);
    }

    /**
     * 上传一份账单并跑一次对账。
     *
     * @param file    账单文件（CSV / xlsx）
     * @param adminId 执行对账的管理员 ID
     * @return 新批次的视图；解析失败时返回对应的失败码
     */
    @Transactional
    public BizResult<ReconcileBatchVo> reconcile(MultipartFile file, Long adminId) {
        if (file == null || file.isEmpty()) {
            return BizResult.fail(ErrorCode.RECONCILE_BILL_ENCODING, "没有选择文件，或文件是空的");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.warn("[对账] 账单文件读取失败", e);
            return BizResult.fail(ErrorCode.RECONCILE_BILL_ENCODING, "文件读取失败，请重新上传");
        }

        // 解析：编码、切分、挑格式、逐行清洗。全部失败路径都在这一步，且都不落任何东西
        BillParseResult parsed;
        try {
            parsed = BillParserDispatcher.parse(bytes);
        } catch (BillParseException e) {
            return BizResult.fail(e.getError(), e.getMessage());
        }
        if (parsed.isEmpty()) {
            /*
             * 文案里要带上**被排除的交易类型**。
             *
             * 这个分支最常见的成因不是传错文件，而是收款账号换了一类
             *（个人零钱 → 经营账户）之后，交易类型的名字跟着变了 ——
             * 那时账单读得出来、表头也认得，只是一条记录都留不下。
             * 把那几个名字列出来，管理员发回来照着往白名单里补一行就好；
             * 只说一句「没有可对账的记录」，他那边除了反复重试没有别的动作可做。
             * 这与 BillParserDispatcher 把认不出的表头原样带出去是同一套做法。
             */
            return BizResult.fail(ErrorCode.RECONCILE_BILL_EMPTY,
                    "这个文件里没有可对账的收款记录（" + parsed.excludedCount()
                            + " 笔未参与对账、跳过 " + parsed.skippedRows() + " 行）"
                            + excludedTypesHint(parsed)
                            + "，请检查导出的日期范围");
        }

        Long storeId = storeMapper.selectCurrentId();
        if (storeId == null) {
            return BizResult.fail(ErrorCode.STORE_NOT_FOUND, "还没有门店记录，无法对账");
        }

        // 窗口：账单内容里的交易时间区间，两端各按配置放宽。
        // 一条带时间的记录都没有时不能猜一个区间 —— 那会算出一屏没有依据的差异
        LocalDateTime periodStart = parsed.minTradedAt();
        LocalDateTime periodEnd = parsed.maxTradedAt();
        if (periodStart == null || periodEnd == null) {
            return BizResult.fail(ErrorCode.RECONCILE_BILL_FORMAT,
                    "账单里的交易时间一列都认不出来，无法确定对账范围。"
                            + "请确认时间列是「2026-09-30 21:45:32」这样的格式");
        }
        LocalDateTime windowStart = periodStart.minusDays(properties.getWindowBeforeDays());
        LocalDateTime windowEnd = periodEnd.plusDays(properties.getWindowAfterDays());

        List<PaymentProof> candidates = proofMapper.selectCandidatesForReconcile(windowStart, windowEnd);
        if (candidates.size() > properties.getCandidateWarnThreshold()) {
            log.warn("[对账] ⚠️ 候选凭证偏多 count={} 窗口=[{} ~ {}] —— "
                            + "管理员可能传了跨度很大的账单，匹配会变慢",
                    candidates.size(), windowStart, windowEnd);
        }

        ReconcilePlan plan = ReconcileMatcher.match(parsed.records(), candidates);

        // 留档。失败只记 warn（store 内部已记），billFilePath 留空，对账照常完成
        String billFilePath = billStorage.store(bytes, file.getOriginalFilename());

        ReconcileBatch batch = new ReconcileBatch();
        batch.setStoreId(storeId);
        batch.setChannel(parsed.channel().name());
        batch.setFileName(fileNameOf(file.getOriginalFilename()));
        batch.setBillFilePath(billFilePath);
        batch.setFileSha256(sha256Of(bytes));
        batch.setPeriodStart(periodStart);
        batch.setPeriodEnd(periodEnd);
        batch.setWindowStart(windowStart);
        batch.setWindowEnd(windowEnd);
        batch.setBillCount(parsed.records().size());
        batch.setBillAmount(parsed.totalAmount());
        batch.setBillExcludedCount(parsed.excludedCount());
        batch.setBillSkippedCount(plan.billSkipped());
        batch.setBillUnclaimedCount(plan.billUnclaimedCount());
        batch.setBillUnclaimedAmount(plan.billUnclaimedAmount());
        batch.setProofCount(plan.activeCount());
        batch.setProofAmount(plan.activeAmount());
        batch.setProofSkippedCount(plan.proofSkipped());
        batch.setMatchedCount(plan.matched().size());
        batch.setMatchedAmount(plan.matchedAmount());
        batch.setDiffCount(0);
        batch.setCreatedBy(adminId);
        batchMapper.insert(batch);

        claimMatched(batch, plan);

        // 流水：认下来的每一笔各记一行「账单对账确认」（2026-10-10 加）。
        // 它与「收款到账」分开记 —— 那一条只说明系统按自己的记录认了这笔钱
        //（小额收款甚至是机器读到一个单号就放行的），而这一条才说明
        //「收款账单里也真有这一笔」。免人工复核不产生这条确认，见 TradeEventType#RECONCILED
        for (PaymentProof proof : proofMapper.selectByReconcileBatch(batch.getId())) {
            tradeLogService.recordReconciled(proof.getTargetType(), proof.getOrderNo(),
                    proof.getUserId(), proof.getAmount(), proof.getPaymentNo(),
                    adminId, "对账批次 #" + batch.getId());
        }

        List<ReconcilePlan.DiffDraft> kept = dropDuplicatedDiffs(plan.diffs());
        for (ReconcilePlan.DiffDraft draft : kept) {
            diffMapper.insert(toEntity(batch.getId(), draft));
        }
        batch.setDiffCount(kept.size());

        log.info("[对账] 完成 batchId={} 渠道={} 账单 {} 笔 / 匹配 {} 笔 / 跳过 {} 笔 / 差异 {} 条",
                batch.getId(), batch.getChannel(), batch.getBillCount(),
                batch.getMatchedCount(), batch.getBillSkippedCount(), kept.size());

        String createdByName = nicknameOf(adminId);
        return BizResult.ok(ReconcileBatchVo.from(batch, kept.size(), createdByName));
    }

    /**
     * 把匹配成功的凭证认领到本批次上，并处理并发抢占。
     *
     * @param batch 已落库的批次（需要它的 ID）
     * @param plan  匹配结果
     */
    private void claimMatched(ReconcileBatch batch, ReconcilePlan plan) {
        List<Long> proofIds = plan.matchedProofIds();
        if (proofIds.isEmpty()) {
            // 空列表会拼出 IN () 语法错误 —— 这一条不能靠 SQL 兜
            return;
        }
        int affected = proofMapper.markReconciled(batch.getId(), proofIds);
        if (affected != proofIds.size()) {
            /*
             * 有凭证被并发的另一个批次先认领走了（markReconciled 带
             * 「reconcile_batch_id IS NULL」守卫，所以这种情况会表现为行数变少）。
             *
             * 只把批次上的计数改成实际值：matched_amount 不跟着改 ——
             * 要精确修正它就得知道具体是哪几条没认上，而那是又一次查询。
             * 并发抢占是罕见情况，且金额偏差只影响报表观感，不值当。
             */
            log.warn("[对账] ⚠️ 有凭证被并发的另一个批次抢先认领，按实际行数修正计数 "
                            + "batchId={} 预期={} 实际={}",
                    batch.getId(), proofIds.size(), affected);
            batchMapper.updateMatchedCount(batch.getId(), affected);
            batch.setMatchedCount(affected);
        }
    }

    /**
     * 去掉「已经有一条未处理的同类差异」的草稿。
     *
     * <p>重传同一份账单、或者连着几天对同一批账时，同一处不一致会被反复发现。
     * 每发现一次就堆一条的话，差异列表会迅速变成一屏重复项 ——
     * 而管理员的反应不会是把它们逐条看完，而是干脆不看了。
     *
     * <p>去重键是 <b>(类型, 凭证)</b>，没有凭证的（{@code BILL_ONLY}）用
     * <b>(类型, 流水号)</b>。跨类型不去重：同一笔凭证身上发现的是「金额不符」
     * 还是「账单里没有」，那是两件事，都该报。
     *
     * <p><b>并发上传同一份文件的竞态接受</b>：两个人同时传，两边都没查到对方的记录，
     * 于是各插一条。单门店下管理员就一个人，而后果只是多一条差异条目，
     * 不是数据损坏。
     *
     * @param drafts 匹配算出来的全部草稿
     * @return 该写入库的那些
     */
    private List<ReconcilePlan.DiffDraft> dropDuplicatedDiffs(List<ReconcilePlan.DiffDraft> drafts) {
        List<Long> proofIds = drafts.stream()
                .map(ReconcilePlan.DiffDraft::proofId)
                .filter(Objects::nonNull).distinct().toList();
        List<String> paymentNos = drafts.stream()
                .map(ReconcilePlan.DiffDraft::paymentNo)
                .filter(Objects::nonNull).distinct().toList();

        Set<String> existing = new HashSet<>();
        // 空列表会让 SQL 拼出 IN () —— 两条查询各自判空跳过
        if (!proofIds.isEmpty()) {
            for (ReconcileDiff row : diffMapper.selectUnhandledByProofIds(proofIds)) {
                existing.add(key(row.getDiffType(), "#", row.getProofId()));
            }
        }
        if (!paymentNos.isEmpty()) {
            for (ReconcileDiff row : diffMapper.selectUnhandledByPaymentNos(paymentNos)) {
                existing.add(key(row.getDiffType(), "@", row.getPaymentNo()));
            }
        }

        List<ReconcilePlan.DiffDraft> kept = new ArrayList<>(drafts.size());
        Set<String> seen = new HashSet<>();
        for (ReconcilePlan.DiffDraft draft : drafts) {
            String dedupeKey = dedupeKeyOf(draft);
            if (dedupeKey == null) {
                kept.add(draft);
                continue;
            }
            // 库里已有的跳过；本批次内重复的也跳过（防的是匹配算法将来出岔子）
            if (existing.contains(dedupeKey) || !seen.add(dedupeKey)) {
                continue;
            }
            kept.add(draft);
        }
        return kept;
    }

    /**
     * 算一条草稿的去重键。
     *
     * @param draft 草稿
     * @return 去重键；既没有凭证也没有流水号时返回 null（那就不去重）
     */
    private static String dedupeKeyOf(ReconcilePlan.DiffDraft draft) {
        if (draft.proofId() != null) {
            return key(draft.type().name(), "#", draft.proofId());
        }
        if (draft.paymentNo() != null) {
            return key(draft.type().name(), "@", draft.paymentNo());
        }
        return null;
    }

    /**
     * 拼去重键。
     *
     * @param type  差异类型名
     * @param sign  分隔符，{@code #} 表示按凭证、{@code @} 表示按流水号
     * @param value 凭证 ID 或流水号
     * @return 键
     */
    private static String key(String type, String sign, Object value) {
        return type + sign + value;
    }

    /**
     * 草稿 → 实体。
     *
     * @param batchId 批次 ID（匹配时还不知道，所以在这里补上）
     * @param draft   草稿
     * @return 差异实体
     */
    private static ReconcileDiff toEntity(Long batchId, ReconcilePlan.DiffDraft draft) {
        ReconcileDiff entity = new ReconcileDiff();
        entity.setBatchId(batchId);
        entity.setDiffType(draft.type().name());
        entity.setProofId(draft.proofId());
        entity.setPaymentNo(draft.paymentNo());
        entity.setOrderNo(draft.orderNo());
        entity.setTargetType(draft.targetType());
        entity.setBillAmount(draft.billAmount());
        entity.setProofAmount(draft.proofAmount());
        entity.setBillTime(draft.billTime());
        entity.setBillSummary(draft.billSummary());
        entity.setHandled(0);
        return entity;
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 后台分页查询对账批次。
     *
     * @param page 页码，从 1 起
     * @param size 每页条数
     * @return 分页结果，每项带「还有几条差异没处理」
     */
    public PageResult<ReconcileBatchVo> listBatches(int page, int size) {
        Long storeId = storeMapper.selectCurrentId();
        IPage<ReconcileBatch> result = storeId == null
                ? new Page<>(page, size)
                : batchMapper.selectPageForAdmin(new Page<>(page, size), storeId);

        List<ReconcileBatch> rows = result.getRecords();
        if (rows.isEmpty()) {
            // 空页也走同一个映射函数，不为它单写一条返回路径 ——
            // 两条路径迟早会在某个字段上分岔（与 PaymentProofService 同一套做法）
            return PageResult.of(result, r -> ReconcileBatchVo.from(r, 0, null));
        }

        Map<Long, Integer> unhandled = new HashMap<>();
        for (ReconcileBatchDiffCount count : diffMapper.countUnhandledByBatchIds(
                rows.stream().map(ReconcileBatch::getId).toList())) {
            unhandled.put(count.getBatchId(), count.getCnt());
        }
        Map<Long, String> names = nicknameMapOf(rows.stream().map(ReconcileBatch::getCreatedBy).toList());

        return PageResult.of(result, batch -> ReconcileBatchVo.from(batch,
                unhandled.getOrDefault(batch.getId(), 0),
                names.get(batch.getCreatedBy())));
    }

    /**
     * 取一个批次的详情，含各类差异的条数。
     *
     * @param batchId 批次 ID
     * @return 批次视图；不存在时返回 {@code RECONCILE_BATCH_NOT_FOUND}
     */
    public BizResult<ReconcileBatchVo> getBatch(Long batchId) {
        ReconcileBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            return BizResult.fail(ErrorCode.RECONCILE_BATCH_NOT_FOUND);
        }

        Map<String, Integer> typeCounts = new HashMap<>();
        int unhandled = 0;
        for (ReconcileDiffTypeCount count : diffMapper.countByBatchGroupByType(batchId)) {
            typeCounts.put(count.getDiffType(), count.getCnt());
        }
        for (ReconcileBatchDiffCount count : diffMapper.countUnhandledByBatchIds(List.of(batchId))) {
            unhandled = count.getCnt();
        }

        ReconcileBatchVo vo = ReconcileBatchVo.from(batch, unhandled, nicknameOf(batch.getCreatedBy()));
        vo.setDiffTypeCounts(typeCounts);
        return BizResult.ok(vo);
    }

    /**
     * 分页查询某个批次的差异明细。
     *
     * @param batchId  批次 ID
     * @param page     页码，从 1 起
     * @param size     每页条数
     * @param diffType 类型筛选，可为空
     * @param handled  处理状态筛选（0 / 1），可为 null（不过滤）
     * @return 分页结果
     */
    public PageResult<ReconcileDiffVo> listDiffs(Long batchId, int page, int size,
                                                 String diffType, Integer handled) {
        IPage<ReconcileDiff> result = diffMapper.selectPageForAdmin(
                new Page<>(page, size), batchId, ReconcileTexts.trimToNull(diffType), handled);

        List<ReconcileDiff> rows = result.getRecords();
        if (rows.isEmpty()) {
            return PageResult.of(result, r -> ReconcileDiffVo.from(r, null));
        }
        Map<Long, String> names = nicknameMapOf(rows.stream().map(ReconcileDiff::getHandledBy).toList());
        return PageResult.of(result, diff -> ReconcileDiffVo.from(diff,
                diff.getHandledBy() == null ? null : names.get(diff.getHandledBy())));
    }

    /**
     * 标记一条差异已处理。
     *
     * <p><b>只写结论，不动任何业务数据</b>：不改订单状态、不代提交凭证、不撤批次。
     * 差异只是给人看的线索，钱怎么处置由人决定 —— 与「资金动作入口越少越好」一致。
     *
     * @param diffId  差异 ID
     * @param adminId 处理人管理员 ID
     * @param note    处理备注，可为空
     * @return 成功或 {@code RECONCILE_DIFF_HANDLED} / {@code RECONCILE_DIFF_NOT_FOUND}
     */
    @Transactional
    public BizResult<Void> handleDiff(Long diffId, Long adminId, String note) {
        ReconcileDiff diff = diffMapper.selectById(diffId);
        if (diff == null) {
            return BizResult.fail(ErrorCode.RECONCILE_DIFF_NOT_FOUND);
        }

        int affected = diffMapper.handle(diffId, adminId, ReconcileTexts.trimToNull(note));
        if (affected == 0) {
            // 状态守卫生效：另一个管理员已经处理过了
            return BizResult.fail(ErrorCode.RECONCILE_DIFF_HANDLED);
        }
        return BizResult.ok(null);
    }

    /**
     * 取账单原文件。
     *
     * @param batchId 批次 ID
     * @return 文件名与内容；批次不存在、或文件已不在时返回对应的失败码
     */
    public BizResult<BillFileVo> loadBillFile(Long batchId) {
        ReconcileBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            return BizResult.fail(ErrorCode.RECONCILE_BATCH_NOT_FOUND);
        }
        byte[] content = billStorage.load(batch.getBillFilePath());
        if (content == null) {
            return BizResult.fail(ErrorCode.RECONCILE_BILL_FILE_MISSING);
        }
        return BizResult.ok(new BillFileVo(batch.getFileName(), content));
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    /**
     * 批量取用户展示名。
     *
     * <p>一次查回全部，避免 N+1（与 {@code PaymentProofService#nicknameMapOf}
     * 同一套做法）。{@code ids} 里的 null 要滤掉 —— 未处理差异的
     * {@code handled_by} 是空的。
     *
     * @param ids 用户 ID，可含 null
     * @return 用户 ID → 展示名
     */
    private Map<Long, String> nicknameMapOf(List<Long> ids) {
        Set<Long> distinct = new HashSet<>();
        for (Long id : ids) {
            if (id != null) {
                distinct.add(id);
            }
        }
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (SysUser user : userMapper.selectBatchIds(distinct)) {
            names.put(user.getId(), displayNameOf(user));
        }
        return names;
    }

    /**
     * 取单个用户的展示名。
     *
     * @param userId 用户 ID，可为 null
     * @return 昵称（没有则用户名）；查不到时返回 null
     */
    private String nicknameOf(Long userId) {
        return userId == null ? null : nicknameMapOf(List.of(userId)).get(userId);
    }

    /**
     * 昵称优先，没有就退回用户名。
     *
     * @param user 用户实体
     * @return 展示名
     */
    private static String displayNameOf(SysUser user) {
        return user.getNickname() == null || user.getNickname().isBlank()
                ? user.getUsername() : user.getNickname();
    }

    /**
     * 取一个安全的文件展示名。
     *
     * <p>截断到列宽 255，空则给一个占位 —— {@code file_name} 是 {@code NOT NULL}。
     * 这个值只用于展示与下载时的文件名，不参与任何判断。
     *
     * @param originalFilename 上传时的文件名，可为 null
     * @return 非空的展示名
     */
    private static String fileNameOf(String originalFilename) {
        String name = ReconcileTexts.trimToNull(originalFilename);
        return name == null ? "账单文件" : ReconcileTexts.truncate(name, 255);
    }

    /**
     * 算文件内容的 SHA-256（小写十六进制）。
     *
     * <p>给「这两个批次是不是同一份文件」用 —— 文件名可以随便改，内容不会。
     * 算不出来时返回 null 而不是抛异常：它是个辅助指纹，
     * 为它让整次对账失败是不划算的。
     *
     * @param bytes 文件内容
     * @return 64 位十六进制串；算法不可用时返回 null
     */
    private static String sha256Of(byte[] bytes) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须提供的算法，走不到这里；留着是为了不让一个
            // 「理论上不会发生」的受检异常逼着调用方写 try-catch
            return null;
        }
    }
}
