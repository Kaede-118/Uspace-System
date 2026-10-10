package com.kaede.uspace.order;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kaede.uspace.order.entity.PaymentProof;
import com.kaede.uspace.order.mapper.PaymentProofMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存版的 {@link PaymentProofMapper}，让凭证的单元测试不依赖数据库。
 *
 * <p>实现方式与 {@code FakePayQrMapper}、{@code FakeNoticeMapper} 一致：
 * 动态代理 + 按方法名分发，只处理被真正调用的方法，其余直接抛异常并提示补哪个 ——
 * Service 一旦用了预期之外的方法，测试会立刻告诉你，而不是静默返回 null。
 *
 * <p><b>模拟的是数据库行为的语义而非实现</b>，本表有三处特别要紧：
 * <ul>
 *   <li><b>{@code upsert} 的冲突分支</b> —— 真 SQL 走
 *       {@code ON DUPLICATE KEY UPDATE}，只更新列出来的那几列，
 *       并把复核结论整个清掉。假实现必须逐列照做，否则「驳回后重交」
 *       这类用例会在假库里通过、在真库里失败</li>
 *   <li><b>{@code confirm} / {@code reject} 的状态守卫</b> ——
 *       {@code WHERE verify_status = 'SUBMITTED'} 是并发复核的唯一防线</li>
 *   <li><b>排序</b> —— 风险优先 → 待复核优先 → 新的在前。少了它，
 *       「有风险的排最前」这条设计在单测里根本测不到</li>
 * </ul>
 * SQL 是否正确、列名映射对不对，由集成测试负责。
 */
public class FakePaymentProofMapper implements InvocationHandler {

    /** 模拟数据表。用 LinkedHashMap 保持插入顺序，便于调试时观察 */
    private final Map<Long, PaymentProof> rows = new LinkedHashMap<>();

    /** 模拟自增主键 */
    private long nextId = 1;

    /**
     * 生成接口代理，交给 Service 使用。
     *
     * @return PaymentProofMapper 的假实现
     */
    public PaymentProofMapper asMapper() {
        return (PaymentProofMapper) Proxy.newProxyInstance(
                PaymentProofMapper.class.getClassLoader(),
                new Class<?>[]{PaymentProofMapper.class},
                this);
    }

    /**
     * 预置一条凭证数据。
     *
     * @param proof 凭证，id 为空时自动分配
     * @return 同一个对象，便于链式构造
     */
    public PaymentProof seed(PaymentProof proof) {
        if (proof.getId() == null) {
            proof.setId(allocateId());
        }
        if (proof.getCreatedAt() == null) {
            proof.setCreatedAt(LocalDateTime.now());
        }
        rows.put(proof.getId(), proof);
        return proof;
    }

    /**
     * 按 ID 取出表中的当前状态，供测试断言。
     *
     * @param id 凭证 ID
     * @return 凭证；不存在时返回 null
     */
    public PaymentProof get(Long id) {
        return rows.get(id);
    }

    /**
     * 按收款目标取凭证，供测试断言。
     *
     * <p>比 {@link #get(Long)} 好用：测试手上通常只有「哪一类收款的哪一笔」，
     * 而凭证 ID 是插入时才分配的。
     *
     * @param targetType 收款类型名
     * @param targetId   目标 ID
     * @return 凭证；没有则返回 null
     */
    public PaymentProof find(String targetType, Long targetId) {
        return selectByTarget(targetType, targetId);
    }

    /** 取当前表里的记录条数 */
    public int size() {
        return rows.size();
    }

    /**
     * 方法分发。方法名唯一，所以按名字匹配即可。
     *
     * @param proxy  代理对象（未使用）
     * @param method 被调用的方法
     * @param args   调用参数
     * @return 方法返回值
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "selectById" -> selectById((Long) args[0]);
            case "selectByTarget" -> selectByTarget((String) args[0], (Long) args[1]);
            case "upsert" -> upsert((PaymentProof) args[0]);
            case "selectPageForAdmin" -> selectPageForAdmin(args);
            case "confirm" -> confirm((Long) args[0], (Long) args[1]);
            case "autoConfirm" -> autoConfirm((Long) args[0]);
            case "reject" -> reject((Long) args[0], (Long) args[1], (String) args[2]);
            case "countByPaymentNo" -> countByPaymentNo((String) args[0]);
            case "markDuplicateByPaymentNo" -> markDuplicateByPaymentNo((String) args[0]);
            case "selectCandidatesForReconcile" ->
                    selectCandidatesForReconcile((LocalDateTime) args[0], (LocalDateTime) args[1]);
            case "markReconciled" -> markReconciled((Long) args[0], asLongList(args[1]));
            case "selectByReconcileBatch" -> selectByReconcileBatch((Long) args[0]);
            default -> throw new UnsupportedOperationException(
                    "假 Mapper 未实现方法 " + method.getName()
                            + " —— 出现这个错误说明 Service 调用了预期之外的方法，"
                            + "请在 FakePaymentProofMapper 中补上对应实现");
        };
    }

    // ==================================================================
    // 各方法的模拟实现
    // ==================================================================

    /**
     * 按 ID 查询。本表<b>没有逻辑删除列</b>，所以不做任何存活过滤 ——
     * 与真实 SQL 一致。
     *
     * @param id 凭证 ID
     * @return 凭证；不存在时返回 null
     */
    private PaymentProof selectById(Long id) {
        return rows.get(id);
    }

    /**
     * 按收款目标查凭证，走的是真库里的 {@code uk_target} 唯一键。
     *
     * <p>两个参数都相等才算命中（注意 {@code targetId} 用 {@code equals} 比，
     * 不是 {@code ==} —— 那是 {@code Long} 拆箱的经典坑）。
     *
     * @param targetType 收款类型名
     * @param targetId   目标 ID
     * @return 凭证；没有则返回 null
     */
    private PaymentProof selectByTarget(String targetType, Long targetId) {
        return rows.values().stream()
                .filter(p -> targetType.equals(p.getTargetType()))
                .filter(p -> targetId.equals(p.getTargetId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 写入或更新一条凭证。
     *
     * <p>冲突分支逐列照搬真 SQL：更新 {@code proof_url} / {@code payment_no} /
     * {@code amount} / {@code pay_qr_id}，以及 {@code ocr_*} 三列
     *（换成<b>新图</b>的识别结果 —— 旧图那几个值已经不对应任何东西了），
     * 并把复核结论翻回待复核。
     * <b>{@code created_at} 不动</b> —— 它记的是第一次提交的时刻。
     *
     * @param proof 待写入的凭证
     * @return 插入记 1、冲突走更新记 2（MySQL 的约定，调用方不该拿它判业务）
     */
    private int upsert(PaymentProof proof) {
        PaymentProof existing = selectByTarget(proof.getTargetType(), proof.getTargetId());
        LocalDateTime now = LocalDateTime.now();

        if (existing == null) {
            proof.setId(allocateId());
            if (proof.getVerifyStatus() == null) {
                proof.setVerifyStatus(PaymentProofStatus.SUBMITTED.name());
            }
            proof.setCreatedAt(now);
            proof.setUpdatedAt(now);
            rows.put(proof.getId(), proof);
            return 1;
        }

        existing.setProofUrl(proof.getProofUrl());
        existing.setPaymentNo(proof.getPaymentNo());
        existing.setAmount(proof.getAmount());
        existing.setPayQrId(proof.getPayQrId());
        existing.setOcrPaymentNo(proof.getOcrPaymentNo());
        existing.setOcrAmount(proof.getOcrAmount());
        existing.setOcrText(proof.getOcrText());
        // 旧的风险标记一并清掉，随后由 Service 重新检测 —— 用户改了流水号
        // 之后不该继续背着上一轮的结论
        existing.setRiskFlag(null);
        existing.setVerifyStatus(PaymentProofStatus.SUBMITTED.name());
        existing.setConfirmedBy(null);
        existing.setConfirmedAt(null);
        existing.setRejectReason(null);
        existing.setUpdatedAt(now);
        return 2;
    }

    /**
     * 后台分页查询。
     *
     * <p>排序与真 SQL 逐字一致，三条依次生效：有风险标记的排最前、
     * 待复核的排在已处理的之前、同组内新的在前。
     * 少了任何一条，「后台一打开先看到最该处理的」这个设计就在单测里测不到。
     *
     * @param args 依次为分页参数、状态筛选（可空）
     * @return 分页结果
     */
    @SuppressWarnings("unchecked")
    private IPage<PaymentProof> selectPageForAdmin(Object[] args) {
        IPage<PaymentProof> page = (IPage<PaymentProof>) args[0];
        String status = (String) args[1];

        List<PaymentProof> matched = rows.values().stream()
                .filter(p -> status == null || status.isEmpty() || status.equals(p.getVerifyStatus()))
                .sorted(Comparator.comparingInt(FakePaymentProofMapper::isRisk).reversed()
                        .thenComparing(p -> isSubmitted(p) ? 0 : 1)
                        .thenComparing(PaymentProof::getCreatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(PaymentProof::getId, Comparator.reverseOrder()))
                .toList();
        return fillPage(page, matched);
    }

    /** 有风险标记的算 1，供排序用 */
    private static int isRisk(PaymentProof proof) {
        return proof.getRiskFlag() != null && !proof.getRiskFlag().isBlank() ? 1 : 0;
    }

    /** 待复核的算 0（排前面） */
    private static boolean isSubmitted(PaymentProof proof) {
        return PaymentProofStatus.SUBMITTED.name().equals(proof.getVerifyStatus());
    }

    /**
     * 这条凭证能不能被驳回 —— 与真 SQL 的状态守卫逐字一致（2026-10-10 放宽）。
     *
     * <p>两种可驳回：<b>待复核的</b>，以及 <b>机器自动通过的</b>
     * （已核对且复核人为空）。后者是「免人工复核」那条路的出口：机器读到的
     * 单号可能读错，管理员必须能推翻它。人工确认过的（复核人有值）不在其列。
     *
     * @param proof 凭证
     * @return 可驳回返回 true
     */
    private static boolean canReject(PaymentProof proof) {
        if (isSubmitted(proof)) {
            return true;
        }
        return PaymentProofStatus.CONFIRMED.name().equals(proof.getVerifyStatus())
                && proof.getConfirmedBy() == null;
    }

    /**
     * 复核通过。
     *
     * <p><b>公开是刻意的</b>：测试要能直接制造出「已核对」这个前置状态，
     * 而不必绕一圈去调 Service —— 那会把「制造前置状态」与「被测行为」
     * 搅在一起，用例失败时分不清是哪一边的问题。
     *
     * @param id      凭证 ID
     * @param adminId 管理员 ID
     * @return 受影响行数；0 表示已被处理过或不存在
     */
    public int confirm(Long id, Long adminId) {
        PaymentProof proof = selectById(id);
        if (proof == null || !isSubmitted(proof)) {
            return 0;
        }
        proof.setVerifyStatus(PaymentProofStatus.CONFIRMED.name());
        proof.setConfirmedBy(adminId);
        proof.setConfirmedAt(LocalDateTime.now());
        proof.setRejectReason(null);
        return 1;
    }

    /**
     * 自动通过（2026-10-10 加）：置为已核对，<b>复核人留空</b>。
     *
     * <p>与真 SQL 一致地带状态守卫（只改 SUBMITTED 的）——
     * 「并发的第二次拿到 0 行」那条路径才有意义。
     *
     * <p>{@code confirmedBy} 置空是这一步的语义本身：它不是某个管理员认下的，
     * 而是「识别到了有效交易单号」的结果（见
     * {@code PaymentProofMapper#autoConfirm}）。
     *
     * @param id 凭证 ID
     * @return 受影响行数；0 表示已被处理过或不存在
     */
    private int autoConfirm(Long id) {
        PaymentProof proof = selectById(id);
        if (proof == null || !isSubmitted(proof)) {
            return 0;
        }
        proof.setVerifyStatus(PaymentProofStatus.CONFIRMED.name());
        proof.setConfirmedBy(null);
        proof.setConfirmedAt(LocalDateTime.now());
        proof.setRejectReason(null);
        return 1;
    }

    /**
     * 复核不通过。公开的理由同 {@link #confirm(Long, Long)}。
     *
     * @param id      凭证 ID
     * @param adminId 管理员 ID
     * @param reason  未通过原因
     * @return 受影响行数；0 表示已被处理过或不存在
     */
    public int reject(Long id, Long adminId, String reason) {
        PaymentProof proof = selectById(id);
        if (proof == null || !canReject(proof)) {
            return 0;
        }
        proof.setVerifyStatus(PaymentProofStatus.REJECTED.name());
        proof.setConfirmedBy(adminId);
        proof.setConfirmedAt(LocalDateTime.now());
        proof.setRejectReason(reason);
        return 1;
    }

    /**
     * 数一数某个流水号被几条凭证引用过。
     *
     * @param paymentNo 交易流水号
     * @return 条数
     */
    private int countByPaymentNo(String paymentNo) {
        return (int) rows.values().stream()
                .filter(p -> paymentNo.equals(p.getPaymentNo()))
                .count();
    }

    /**
     * 给引用同一流水号的全部凭证打上风险标记。
     *
     * @param paymentNo 交易流水号
     * @return 受影响行数
     */
    private int markDuplicateByPaymentNo(String paymentNo) {
        int affected = 0;
        for (PaymentProof proof : rows.values()) {
            if (paymentNo.equals(proof.getPaymentNo())) {
                proof.setRiskFlag("DUPLICATE_PAYMENT_NO");
                affected++;
            }
        }
        return affected;
    }

    /**
     * 分配一个未被占用的自增主键。
     *
     * <p>必须跳过已占用的 ID：测试里常用显式 ID 预置数据，
     * 游标不跳过它的话，后续不带 ID 的插入会分配到同一个 ID
     * 并把先前的记录悄悄覆盖掉。
     *
     * @return 可用的主键
     */
    private long allocateId() {
        while (rows.containsKey(nextId)) {
            nextId++;
        }
        return nextId++;
    }

    /**
     * 截取一页，模拟 MyBatis-Plus 分页插件的行为。
     *
     * @param page 分页参数
     * @param all  过滤排序后的全量数据
     * @return 填好 total 与 records 的分页对象
     */
    private static IPage<PaymentProof> fillPage(IPage<PaymentProof> page, List<PaymentProof> all) {
        page.setTotal(all.size());
        int offset = (int) Math.min((page.getCurrent() - 1) * page.getSize(), all.size());
        offset = Math.max(offset, 0);
        int end = (int) Math.min(offset + page.getSize(), all.size());
        page.setRecords(new ArrayList<>(all.subList(offset, end)));
        return page;
    }

    // ==================================================================
    // 对账（Phase 5）
    // ==================================================================

    /**
     * 取窗口内的全部凭证。
     *
     * <p>⚠️ <b>刻意不筛 {@code verify_status}、也不筛 {@code reconcile_batch_id}</b>，
     * 与真 SQL 逐字一致。</b>
     *
     * <p>假实现「照着真 SQL 写」这件事在这里格外要紧：如果图省事写成
     * 「只取未认领的」，而真 SQL 也犯同样的错，那么两边一起错、测试一起绿 ——
     * 而线上表现为「重传同一份账单产出一屏假差异」。
     * 这也是为什么真库那边另有一条集成测试专门验「已认领的凭证也要被取到」。
     *
     * <p>两端都是<b>闭区间</b>，与真 SQL 的 {@code >=} / {@code <=} 一致。
     *
     * @param windowStart 窗口下界（含）
     * @param windowEnd   窗口上界（含）
     * @return 候选凭证，按提交时间升序
     */
    private List<PaymentProof> selectCandidatesForReconcile(LocalDateTime windowStart,
                                                            LocalDateTime windowEnd) {
        return rows.values().stream()
                .filter(p -> p.getCreatedAt() != null)
                .filter(p -> !p.getCreatedAt().isBefore(windowStart))
                .filter(p -> !p.getCreatedAt().isAfter(windowEnd))
                .sorted(Comparator.comparing(PaymentProof::getCreatedAt)
                        .thenComparing(PaymentProof::getId))
                .toList();
    }

    /**
     * 把一批凭证认领到某个批次上，带「未被认领」守卫。
     *
     * <p>只改 {@code reconcile_batch_id} —— <b>绝不动 {@code verify_status}</b>，
     * 与真 SQL 一致。集成测试里另有一条专门断言这一点。
     *
     * @param batchId  批次 ID
     * @param proofIds 凭证 ID 列表
     * @return 受影响行数；已被认领的不会计入
     */
    private int markReconciled(Long batchId, List<Long> proofIds) {
        int affected = 0;
        for (Long id : proofIds) {
            PaymentProof proof = rows.get(id);
            if (proof != null && proof.getReconcileBatchId() == null) {
                proof.setReconcileBatchId(batchId);
                affected++;
            }
        }
        return affected;
    }

    /**
     * 取某个批次认领下来的凭证。
     *
     * @param batchId 批次 ID
     * @return 该批次认领的凭证
     */
    private List<PaymentProof> selectByReconcileBatch(Long batchId) {
        return rows.values().stream()
                .filter(p -> batchId.equals(p.getReconcileBatchId()))
                .toList();
    }

    /**
     * 把反射拿到的参数转成 ID 列表。
     *
     * @param arg {@code markReconciled} 的第二个参数
     * @return ID 列表
     */
    @SuppressWarnings("unchecked")
    private static List<Long> asLongList(Object arg) {
        return (List<Long>) arg;
    }
}
