package com.kaede.uspace.order;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kaede.uspace.common.config.UploadProperties;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.common.trade.TradeSource;
import com.kaede.uspace.order.dto.AdminProofVo;
import com.kaede.uspace.order.dto.PaymentTarget;
import com.kaede.uspace.order.dto.ProofSubmitRequest;
import com.kaede.uspace.order.dto.ProofSubmitVo;
import com.kaede.uspace.order.dto.RejectedProofVo;
import com.kaede.uspace.order.entity.PayQr;
import com.kaede.uspace.order.entity.PaymentProof;
import com.kaede.uspace.order.event.PaymentProofPendingEvent;
import com.kaede.uspace.order.event.PaymentProofRejectedEvent;
import com.kaede.uspace.order.mapper.PayQrMapper;
import com.kaede.uspace.order.mapper.PaymentProofMapper;
import com.kaede.uspace.order.ocr.OcrTextParser;
import com.kaede.uspace.user.entity.SysUser;
import com.kaede.uspace.user.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 付款凭证的提交与复核（模块 8 的支付能力）。
 *
 * <p>2026-09-30 起扫码转账是 12 月投产时唯一的收款方式，本类因此是
 * <b>「钱到没到账」这个判断的唯一入口</b>：用户扫店内的收款码付款 → 上传截图 →
 * 管理员复核。三条线上支付通道（微信 JSAPI / 微信 H5 / 支付宝 WAP）
 * 都因资质门槛走不通，见 {@code docs/开发约定与设计说明.md} 第九章。
 *
 * <h3>交付时机分两层，由处理器声明</h3>
 * 提交凭证之后会发生什么，<b>四类收款并不一样</b>：
 * <table border="1">
 *   <caption>提交之后</caption>
 *   <tr><th>类型</th><th>提交时</th><th>管理员复核时</th></tr>
 *   <tr><td>订单 / 商品</td><td><b>识别到有效交易单号时当场落账</b>
 *       （订单转已支付、库存当场扣减），并自动置为已核对</td>
 *       <td>没自动通过的那些才轮到人 —— 复核通过时才落账</td></tr>
 *   <tr><td>包场 / 月卡</td><td>只落凭证</td>
 *       <td><b>此刻才落账</b>（生成邀请令牌、发卡）</td></tr>
 * </table>
 * 这条差异不是这里硬编码的，而是问 {@link PaymentTargetHandler#deliverOnSubmit} ——
 * 对包场与月卡来说「放行」与「落账」是同一件事（邀请令牌与卡只在
 * {@code markPaid} 里产生），所以它们必须等复核。
 *
 * <h3>小额收款的自动通过（2026-10-10 加）</h3>
 *
 * <p>扫码转账是投产时唯一的收款方式，管理员就是唯一的对账环节。
 * 把「逐条看」降成「只看对不上的」，靠的是两条：
 * <ol>
 *   <li><b>自动通过</b> —— 订单与商品（{@code deliverOnSubmit} 为 true 的两类）
 *       在识别到有效交易单号时当场结清并置为已核对，不打扰任何人。
 *       三道闸见 {@link #canAutoApprove}：有单号、单号未被别的凭证引用、
 *       识别金额与应付一致（识别不出金额不拦）</li>
 *   <li><b>进队列就播报</b> —— 没自动通过的一律落到人工复核，同时推一条到
 *       店主群（{@link PaymentProofPendingEvent}），把「记得去后台看一眼」
 *       交给播报 —— 人工复核最大的风险不是看错，是没人记得看</li>
 * </ol>
 *
 * <p>⚠️ <b>「没识别到交易单号就不算结清」是刻意的</b>：只有一串能对得上账的
 * 单号才证明「钱真的进了这个收款账号」；没有它的时候，用户交上来的
 * 可能只是一张随手拍的图。代价是那一笔要等人 —— 由上面第二条兜住。
 *
 * <p><b>由此带来的风险要说清楚</b>：订单与商品是「先交付后复核」，
 * 若复核不通过，系统<b>不能自动回退</b>（订单已 PAID、库存已扣、累计消费已加），
 * 只能人工处置。所以 {@link AdminProofVo#isDelivered()} 会把这个事实带给后台，
 * 由后台显著标出。
 *
 * <h3>提交的五条分支</h3>
 * 不能用一句 upsert 糊过去 —— 用户提交错图、重复提交、被驳回后重交都是高频事件：
 * <table border="1">
 *   <caption>分支表</caption>
 *   <tr><th>目标当前态</th><th>已有凭证</th><th>行为</th></tr>
 *   <tr><td rowspan="3">待支付</td><td>无</td>
 *       <td rowspan="3">写凭证 + 按类型决定是否当场落账</td></tr>
 *   <tr><td>待复核 / 未通过</td></tr>
 *   <tr><td colspan="2">（未通过的要翻回待复核并清掉驳回原因，见 upsert）</td></tr>
 *   <tr><td rowspan="3">已支付</td><td>待复核</td>
 *       <td>只更新凭证（换图 / 改流水号），<b>不重复落账</b></td></tr>
 *   <tr><td>已核对</td><td>幂等返回，不覆盖</td></tr>
 *   <tr><td>未通过</td><td>拒绝并附上驳回原因</td></tr>
 *   <tr><td>已支付（线上通道）</td><td>无</td><td>拒绝 —— 线上付的款没有凭证可言</td></tr>
 * </table>
 */
@Slf4j
@Service
public class PaymentProofService {

    /**
     * 识别出的单号能有多长，与 {@code ocr_payment_no VARCHAR(64)} 的列宽一致。
     *
     * <p>这个值不该被触发：识别侧的规则限定最多 40 位（见 {@code OcrTextParser}）。
     * 它挡的是「有人直接构造请求体绕过前端」—— 那种情况下截断一个辅助字段，
     * 远好过让整条提交报 {@code Data too long}。
     */
    private static final int MAX_OCR_PAYMENT_NO_LENGTH = 64;

    private final PaymentProofMapper proofMapper;
    private final PayQrMapper payQrMapper;
    private final SysUserMapper userMapper;
    private final UploadProperties uploadProperties;
    private final List<PaymentTargetHandler> handlers;
    private final PaymentService paymentService;

    /**
     * 事件发布器。
     *
     * <p>驳回之后要<b>把结论送回用户那里</b>：订单与商品是「先交付后复核」，
     * 驳回不回退订单状态，于是用户看到的仍是「已支付」—— 少了这条通路，
     * 复核环节等于白设。发布走 Spring 事件（{@code qqbot} 监听后在群里 @ 本人），
     * 与到店 / 离店播报同一套机制。
     */
    private final ApplicationEventPublisher events;

    /**
     * 交易流水。提交与驳回各记一行 ——（2026-10-10 加）
     *
     * <p>到账那一行不在这里：它由 {@link #paymentService} 的落账方法写
     *（两条到账路径都汇到那里）。
     */
    private final TradeLogService tradeLogService;

    /**
     * 构造器注入。
     *
     * @param proofMapper      凭证数据访问
     * @param payQrMapper      收款码数据访问，用于校验并回显「扫的是哪张码」
     * @param userMapper       用户数据访问，后台列表里补提交人昵称
     * @param uploadProperties 上传配置，用于校验截图路径前缀
     * @param handlers         全部支付目标处理器，按类型找对应的那一个
     * @param paymentService   支付服务，落账走它的 {@code settleByProof}
     * @param events           事件发布器，驳回之后通知本人
     * @param tradeLogService  交易流水
     */
    public PaymentProofService(PaymentProofMapper proofMapper,
                               PayQrMapper payQrMapper,
                               SysUserMapper userMapper,
                               UploadProperties uploadProperties,
                               List<PaymentTargetHandler> handlers,
                               PaymentService paymentService,
                               ApplicationEventPublisher events,
                               TradeLogService tradeLogService) {
        this.proofMapper = proofMapper;
        this.payQrMapper = payQrMapper;
        this.userMapper = userMapper;
        this.uploadProperties = uploadProperties;
        this.handlers = handlers;
        this.paymentService = paymentService;
        this.events = events;
        this.tradeLogService = tradeLogService;
    }

    // ==================================================================
    // 用户端：提交凭证
    // ==================================================================

    /**
     * 用户提交付款凭证。
     *
     * <p><b>执行顺序是刻意的：先读目标 → 再 upsert 凭证 → 最后落账。</b>
     * 反过来（先落账再 upsert）的话，手机上连点两下提交时并发的第二次会撞
     * {@code uk_target} 抛 {@code DuplicateKeyException}，用户重试时目标已转
     * 已支付、{@code loadForPay} 失败，看到的是一句「当前状态不支持该操作」——
     * 完全指错方向的提示。按现在这个顺序，第二次走的是 UPDATE 分支，
     * 落账那一步则命中 {@code markPaid} 的状态守卫返回 0 行，表现为幂等成功。
     *
     * @param userId  当前登录用户 ID
     * @param request 凭证内容
     * @param source  来源渠道（网页端 / 群内），只进交易流水，不参与任何判断
     * @return 成功时返回提交结果（含「是否已交付」）；目标不存在或不属于该用户、
     *         图片路径不合法、已被驳回时返回对应的失败码
     */
    @Transactional
    public BizResult<ProofSubmitVo> submit(Long userId, ProofSubmitRequest request,
                                           TradeSource source) {
        PaymentTargetHandler handler = handlerOf(request.getTargetType());
        if (handler == null) {
            return BizResult.fail(ErrorCode.PARAM_INVALID, "不支持的收款类型");
        }

        // 图片路径先校验：它不合法就没必要去查库。
        // 请求体是客户端给的，不校验就能塞外链（管理员的浏览器会去加载别人的
        // 服务器，等于泄露「谁在什么时候付款」），也能塞别的 kind 的图
        if (!isProofImageUrl(request.getProofUrl())) {
            return BizResult.fail(ErrorCode.PAYMENT_PROOF_IMAGE_INVALID);
        }

        BizResult<PaymentTarget> loaded = handler.loadForProof(request.getTargetId(), userId);
        if (!loaded.isSuccess()) {
            return BizResult.fail(loaded.getError(), loaded.getMessage());
        }
        PaymentTarget target = loaded.getData();

        PaymentProof existing = proofMapper.selectByTarget(target.getType().name(), target.getId());
        boolean targetPaid = target.isPaid();

        if (targetPaid) {
            // ① 已支付但一条凭证都没有 —— 线上通道付的，或 0 元自动结清的。
            //    这两种情形都不该有凭证
            if (existing == null) {
                log.info("[凭证] 拒绝：该笔已通过线上通道完成 target={}#{}",
                        target.getType(), target.getId());
                return BizResult.fail(ErrorCode.PAYMENT_ALREADY_PAID);
            }
            /*
             * ② 已核对 —— 分两种，这是 2026-10-10 特意分开的（由用户提出要区分）：
             *
             *   · 人工确认过（confirmed_by 有值）：幂等返回，绝不覆盖 ——
             *     覆盖会把一个管理员的结论打回待复核，而用户看不出任何异常
             *   · 机器自动通过（confirmed_by 为空）：**放行重交**。
             *     「免人工复核」本来就不该等于「人工确认过」：用户发现自己
             *     流水号填错了一个字，就该能自己改回来，而不是非等管理员推翻。
             *     重交之后凭证落回待复核（upsert 那一步会翻状态），
             *     由人再看一眼 —— 这正是「机器放行的可以被人推翻」那条规矩
             *     在提交侧的镜像
             */
            if (PaymentProofStatus.CONFIRMED.name().equals(existing.getVerifyStatus())
                    && existing.getConfirmedBy() != null) {
                log.info("[凭证] 重复提交，该笔已由管理员核对，幂等返回 target={}#{}",
                        target.getType(), target.getId());
                return BizResult.ok(ProofSubmitVo.of(target.getType().name(), target.getId(),
                        PaymentProofStatus.CONFIRMED, true));
            }
            /*
             * ③ 已支付 + 已驳回：**放行重交**，与下面「已支付 + 待复核」同路。
             *
             * 这个组合是**历史遗留** —— 交付回退机制（revertDelivery）上线之前，
             * 驳回只改凭证结论、不碰订单状态，于是订单停在已支付上。
             * 新流程根本不会产生它（驳回时状态就被退成 REJECTED 了）。
             *
             * ⚠️ **放行而不是继续拒绝**：继续拒绝的话，那几笔老单子是个死结 ——
             * 用户既不能再付（系统认为不需要）、也不能重交凭证（这里拒绝），
             * 而管理员那边同样没有重开复核的入口。重交之后凭证翻回待复核，
             * 订单停在已支付上 —— 那正是「提交即交付 + 等复核」的正常组合，
             * 状态自洽，管理员复核通过这事就了了。
             */
            if (PaymentProofStatus.REJECTED.name().equals(existing.getVerifyStatus())) {
                log.info("[凭证] 该笔已支付且凭证曾被驳回（回退机制上线前的历史数据），放行重交 "
                                + "target={}#{}", target.getType(), target.getId());
                // 刻意不 return，落到下面的 upsert
            }
            // ④ 待复核（或认不出的取值）：只更新凭证，不重复落账 —— 落到下面的 upsert
        } else if (!target.isPendingPayment() && !target.isRejected()) {
            // ⑤ 既没付款、又不处于待支付（如包场已取消）—— 没有可付的款
            return BizResult.fail(ErrorCode.PAYMENT_PROOF_TARGET_INVALID,
                    "该笔当前状态不支持上传付款凭证，请刷新页面查看最新状态");
        }

        if (request.getPayQrId() != null && payQrMapper.selectById(request.getPayQrId()) == null) {
            return BizResult.fail(ErrorCode.PARAM_INVALID, "收款码不存在，请刷新页面重新扫码");
        }

        // 用户没填流水号时，用识别到的那一串补上（2026-10-10 加）：
        // 群内传图那条路根本没有填单号的表单，不补的话它的 payment_no 恒为空 ——
        // 而对账正是靠这个号把账单与凭证勾稽起来（没号就是一屏「没填流水号」的差异）。
        // ocr_payment_no 那一列照旧保留：它记的是「机器读到什么」，
        // payment_no 记的是「我们照它填了什么」，事后核对时两者分得开。
        // 截断长度与列宽一致（VARCHAR(64)），与 applyOcrFields 用的是同一个常量
        String paymentNo = trimToNull(request.getPaymentNo());
        if (paymentNo == null) {
            paymentNo = truncate(trimToNull(request.getOcrPaymentNo()),
                    MAX_OCR_PAYMENT_NO_LENGTH);
        }
        PaymentProof proof = new PaymentProof();
        proof.setTargetType(target.getType().name());
        proof.setTargetId(target.getId());
        proof.setOrderNo(target.getOutTradeNo());
        proof.setUserId(userId);
        proof.setAmount(target.getAmount());
        proof.setPayQrId(request.getPayQrId());
        proof.setProofUrl(request.getProofUrl().trim());
        proof.setPaymentNo(paymentNo);
        applyOcrFields(proof, request);
        proofMapper.upsert(proof);

        boolean duplicateRisk = flagDuplicatePaymentNo(paymentNo, target);

        // 取回凭证 ID：upsert 是手写 SQL，MyBatis 不回填主键（见 Mapper 的注释），
        // 而下面的自动通过与播报都要用它
        PaymentProof saved = proofMapper.selectByTarget(target.getType().name(), target.getId());
        Long proofId = saved == null ? null : saved.getId();
        if (proofId == null) {
            // 到不了这里：上面那条 upsert 刚写过。真出现说明有人改了写入路径
            log.error("[凭证] 写完凭证却查不回 ID，自动通过与播报都会跳过 单号={}",
                    target.getOutTradeNo());
        }

        // 流水：提交凭证这一笔。四类收款都记 ——「提交过、后来被驳回」
        // 与「压根没提交过」在事后是两件完全不同的事
        tradeLogService.recordProofSubmitted(target.getType(), target.getOutTradeNo(),
                userId, target.getAmount(), paymentNo, source);

        /*
         * 自动通过（2026-10-10 加）：小额的两类（订单与商品，即 deliverOnSubmit 为 true 的）
         * 在「识别到有效交易单号」时当场结清、无须人工复核。
         *
         * 放在 upsert 之后：凭证已经在库里了，落账若失败整个事务回滚，
         * 不会出现「钱记了、凭证没有」。
         */
        boolean delivered = targetPaid;
        boolean autoApproved = false;
        if (!targetPaid && handler.deliverOnSubmit()
                && canAutoApprove(paymentNo, duplicateRisk, request.getOcrAmount(), target)) {
            paymentService.settleByProof(target, PaymentChannel.QR_UPLOAD, paymentNo, null, source);
            delivered = true;
            autoApproved = confirmAutomatically(proofId, target);
        }

        /*
         * 没自动通过的一律进了人工复核队列 —— 推一条到店主群，
         * 把「记得去后台看一眼」这件事交给播报（人工复核最大的风险是没人记得看）。
         *
         * 已支付后换图重交的也在其列：钱早到账了，但这次改的是「这条凭证长什么样」，
         * 仍要人看一眼。
         */
        if (!autoApproved) {
            events.publishEvent(new PaymentProofPendingEvent(proofId, target.getType().name(),
                    target.getOutTradeNo(), target.getAmount(),
                    pendingReasonOf(paymentNo, duplicateRisk, request.getOcrAmount(), target)));
        }

        log.info("[凭证] 用户提交付款凭证 target={}#{} 单号={} 金额={} 流水号={} 已交付={} 自动通过={}",
                target.getType(), target.getId(), target.getOutTradeNo(),
                target.getAmount(), paymentNo, delivered, autoApproved);
        return BizResult.ok(ProofSubmitVo.of(target.getType().name(), target.getId(),
                autoApproved ? PaymentProofStatus.CONFIRMED : PaymentProofStatus.SUBMITTED,
                delivered));
    }

    // ==================================================================
    // 后台：查询与复核
    // ==================================================================

    /**
     * 后台分页查询凭证。
     *
     * <p>排序在 SQL 里定死了三条（风险优先 → 待复核优先 → 新的在前），
     * 见 {@code PaymentProofMapper#selectPageForAdmin} 的注释。
     *
     * <p>昵称与收款码名<b>批量查一次</b>而不是逐条查：一页 20 条逐条查
     * 就是 40 次查询，而这里只需两次。
     *
     * @param page         页码，从 1 开始
     * @param size         每页条数
     * @param verifyStatus 状态筛选，为 null 或空串时不过滤（后台默认视图）
     * @return 分页结果
     */
    public PageResult<AdminProofVo> listForAdmin(int page, int size, String verifyStatus) {
        IPage<PaymentProof> result = proofMapper.selectPageForAdmin(
                new Page<>(page, size), trimToNull(verifyStatus));

        List<PaymentProof> rows = result.getRecords();
        if (rows.isEmpty()) {
            // 空页也要走同一个映射函数，不为它单写一条返回路径 ——
            // 两条路径迟早会在某个字段上分岔
            return PageResult.of(result, r -> AdminProofVo.from(r, null, null, false));
        }

        Map<Long, String> nicknames = nicknameMapOf(rows);
        Map<Long, String> qrNames = payQrNameMapOf(rows);
        return PageResult.of(result, proof -> AdminProofVo.from(proof,
                nicknames.get(proof.getUserId()),
                proof.getPayQrId() == null ? null : qrNames.get(proof.getPayQrId()),
                isDeliverOnSubmit(proof.getTargetType())));
    }

    /**
     * 复核通过。
     *
     * <p>「通过」有两层含义，取决于收款类型：
     * <ul>
     *   <li>订单 / 商品 —— <b>纯登记</b>。钱在提交那一刻就算收到了，
     *       这里只留下「管理员看过并认可」的结论</li>
     *   <li>包场 / 月卡 —— <b>此刻才交付</b>。邀请令牌与月卡都产生在这一步，
     *       所以这一步必须成功，失败要让人看见</li>
     * </ul>
     *
     * @param proofId 凭证 ID
     * @param adminId 操作的管理员 ID
     * @return 成功返回空数据；凭证不存在或已被别人复核过时返回对应失败码
     */
    @Transactional
    public BizResult<Void> confirm(Long proofId, Long adminId) {
        PaymentProof proof = proofMapper.selectById(proofId);
        if (proof == null) {
            return BizResult.fail(ErrorCode.PAYMENT_PROOF_NOT_FOUND);
        }
        // 状态守卫在 SQL 里：两个管理员同时点「确认」时只有一个能改成功，
        // 另一个拿到 0 行。复核是资金结论，重复处理不能当成成功悄悄放过
        if (proofMapper.confirm(proofId, adminId) == 0) {
            return BizResult.fail(ErrorCode.PAYMENT_PROOF_STATUS_INVALID,
                    "这条凭证已被处理过，请刷新列表查看最新状态");
        }

        settleIfNeeded(proof, adminId);

        log.info("[凭证] 复核通过 proofId={} 类型={} 单号={} 金额={} 管理员={}",
                proofId, proof.getTargetType(), proof.getOrderNo(), proof.getAmount(), adminId);
        return BizResult.ok(null);
    }

    /**
     * 复核不通过。
     *
     * <p><b>驳回会把已经发生的交付退回去</b>：订单与商品在提交那刻就落账了
     * （订单转已支付、商品扣库存），而驳回意味着「这笔钱我不认」——
     * 目标却还停在已支付上，那笔单子于是成了死结：用户既不能再付、
     * 也不能重交凭证（{@link #submit} 对「已支付 + 已驳回」明确拒绝），
     * 管理员那边也没有重开复核的入口。
     *
     * <p>所以这里退三步：
     * <ol>
     *   <li>目标的交付 —— {@code PaymentTargetHandler#revertDelivery}
     *       （订单退回待支付并清支付字段、商品退回并还库存）</li>
     *   <li>已经累加的累计消费 —— {@code PaymentService#revertByProof}</li>
     *   <li>把结论送回当事人 —— 发 {@code PaymentProofRejectedEvent}，
     *       {@code qqbot} 监听后在群里 @ 本人</li>
     * </ol>
     * 退完之后目标回到<b>待支付</b>，用户既能重新上传截图、也能换一条通道付款。
     *
     * <p><b>包场与月卡什么都不用退</b>：它们 {@code deliverOnSubmit} 返回 false，
     * 提交凭证那一刻并没有交付任何东西，目标本来就停在待支付上。
     *
     * @param proofId 凭证 ID
     * @param adminId 操作的管理员 ID
     * @param reason  未通过原因，会展示给用户
     * @return 成功返回空数据；凭证不存在或已被别人复核过时返回对应失败码
     */
    @Transactional
    public BizResult<Void> reject(Long proofId, Long adminId, String reason) {
        PaymentProof proof = proofMapper.selectById(proofId);
        if (proof == null) {
            return BizResult.fail(ErrorCode.PAYMENT_PROOF_NOT_FOUND);
        }
        if (proofMapper.reject(proofId, adminId, reason.trim()) == 0) {
            return BizResult.fail(ErrorCode.PAYMENT_PROOF_STATUS_INVALID,
                    "这条凭证已被处理过，请刷新列表查看最新状态");
        }

        boolean delivered = isDeliverOnSubmit(proof.getTargetType());
        if (delivered) {
            log.warn("[凭证] 复核未通过，该笔已交付，正在退回 proofId={} 类型={} 单号={} "
                            + "金额={} 用户={} 原因={}",
                    proofId, proof.getTargetType(), proof.getOrderNo(),
                    proof.getAmount(), proof.getUserId(), reason);
            revertDelivery(proof);
        } else {
            log.info("[凭证] 复核未通过 proofId={} 类型={} 单号={} 原因={}",
                    proofId, proof.getTargetType(), proof.getOrderNo(), reason);
        }

        /*
         * 把结论送回用户那里 —— 这一步不能省。
         *
         * 订单与商品是「先交付后复核」，驳回**不回退订单状态**，所以用户端
         * 看到的仍然是「已支付」。没有这条提醒，他会一直以为这笔账结了，
         * 而管理员那边已经判它不成立 —— 复核环节等于白设。
         *
         * 发布在本方法的事务里，监听器取 AFTER_COMMIT 相位
         *（与到店 / 离店播报同一套，理由见 QqBroadcastListener 的类注释）。
         * 用的是 reject 里已经 trim 过的那份原因：落库与播报必须是同一句话。
         */
        events.publishEvent(new PaymentProofRejectedEvent(
                proofId, proof.getUserId(), proof.getOrderNo(),
                proof.getTargetType(), reason.trim()));

        // 流水：驳回这一笔（来源恒为 ADMIN，记在 TradeLogService 里）。
        // 类型认不出时只记 error 不中断 —— 与 settleIfNeeded 同一套处置：
        // 复核结论已经写进库了，为一条流水把它回滚掉只会让管理员白点一次
        PaymentTargetHandler rejectedHandler = handlerOfName(proof.getTargetType());
        if (rejectedHandler == null) {
            log.error("[凭证] 驳回后要记流水，但收款类型认不出，未记 proofId={} targetType={}",
                    proofId, proof.getTargetType());
        } else {
            tradeLogService.recordProofRejected(rejectedHandler.type(), proof.getOrderNo(),
                    proof.getUserId(), proof.getAmount(), proof.getPaymentNo(),
                    adminId, reason.trim());
        }

        return BizResult.ok(null);
    }

    // ==================================================================
    // 用户端：查被驳回的凭证
    // ==================================================================

    /**
     * 查当前用户被驳回、尚未重交的凭证。
     *
     * <p><b>这是「先交付后复核」那道缺口在用户端的补法</b>：订单与商品提交即落账，
     * 管理员事后驳回只改凭证结论、<b>不回退订单状态</b>，所以用户端看到的仍是
     * 「已支付」。没有这个查询，那个结论就永远到不了当事人那里 ——
     * 而首页的提醒条与订单详情的标记都读它。
     *
     * <p>「尚未重交」不需要额外判断：重交会把状态翻回待复核并清掉原因
     *（见 {@code PaymentProofMapper#upsert}），所以 {@code REJECTED} 本身就
     * 意味着「还等着他处理」。
     *
     * @param userId 当前登录用户 ID
     * @return 被驳回的凭证，按驳回时刻倒序；一条都没有时返回空列表（不是失败）
     */
    public BizResult<List<RejectedProofVo>> listRejected(Long userId) {
        List<RejectedProofVo> rejected = proofMapper.selectRejectedByUser(userId)
                .stream()
                .map(RejectedProofVo::from)
                .toList();
        return BizResult.ok(rejected);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /**
     * 复核通过之后按需落账。
     *
     * <p>对包场与月卡而言这一步就是<b>交付本身</b>（邀请令牌、月卡都产生在
     * {@code markPaid} 里）；对订单与商品则是空操作 —— 它们在提交那一刻就落过账了，
     * {@code settleByProof} 内部的幂等判断会把这一支挡掉。
     *
     * <p>目标找不到（单号认不出、记录已被删）时<b>只记 error 不抛异常</b>：
     * 凭证的复核结论已经写进库了，把它回滚掉只会让管理员白点一次；
     * 而这件事必须让人看见，所以是一条 error 日志。
     *
     * @param proof   凭证
     * @param adminId 复核管理员 ID
     */
    /**
     * 把「提交即交付」的那一步退回去。
     *
     * <p>订单与商品在用户提交凭证那一刻就落账了（订单转已支付、商品扣库存），
     * 而管理员驳回意味着「这笔钱我不认」。不回退的话，那笔单子会永远停在
     * 已支付上 —— <b>用户既不能再付、也不能重交凭证</b>，管理员那边也没有
     * 重开复核的入口，一个谁都动不了的死结。
     *
     * <p>目标找不到（单号认不出、记录已被删）时<b>只记 error 不抛异常</b>：
     * 凭证的复核结论已经写进库了，把它回滚掉只会让管理员白点一次；
     * 而这件事必须让人看见，所以是一条 error 日志。与 {@link #settleIfNeeded}
     * 同一套处置。
     *
     * @param proof 被驳回的凭证
     */
    private void revertDelivery(PaymentProof proof) {
        PaymentTargetHandler handler = handlerOfName(proof.getTargetType());
        if (handler == null) {
            log.error("[凭证] 复核未通过但收款类型认不出，未能冲销 proofId={} targetType={}",
                    proof.getId(), proof.getTargetType());
            return;
        }
        PaymentTarget target = handler.loadByOutTradeNo(proof.getOrderNo());
        if (target == null) {
            log.error("[凭证] 复核未通过但按单号找不到目标，未能冲销 proofId={} orderNo={} 类型={}",
                    proof.getId(), proof.getOrderNo(), proof.getTargetType());
            return;
        }
        paymentService.revertByProof(target, proof);
    }

    private void settleIfNeeded(PaymentProof proof, Long adminId) {
        PaymentTargetHandler handler = handlerOfName(proof.getTargetType());
        if (handler == null) {
            log.error("[凭证] 复核通过但收款类型认不出，未能落账 proofId={} targetType={}",
                    proof.getId(), proof.getTargetType());
            return;
        }
        PaymentTarget target = handler.loadByOutTradeNo(proof.getOrderNo());
        if (target == null) {
            log.error("[凭证] 复核通过但按单号找不到目标，未能落账 proofId={} orderNo={} 类型={}",
                    proof.getId(), proof.getOrderNo(), proof.getTargetType());
            return;
        }
        paymentService.settleByProof(target, PaymentChannel.QR_UPLOAD,
                proof.getPaymentNo(), adminId, TradeSource.ADMIN);
    }

    /**
     * 检查同一流水号有没有被别的凭证用过，有就给全部相关凭证打上风险标记。
     *
     * <p><b>一张截图付两单</b>是纯信任制下最省事的作弊手法，而它留下的痕迹
     * 只有一个 —— 同一个流水号出现两次。走 {@code idx_payment_no} 是一次索引扫描，
     * 代价远低于它挡下的东西。
     *
     * <p><b>刻意不拦提交</b>：真要发生也应当让人看见并判断。
     * 用户重传时抄错一位、同一笔钱在两个页面都提交了一次，都是常见情形，
     * 系统静默拒绝一笔可能的正常付款，比让管理员多看一眼糟糕得多。
     *
     * @param paymentNo 本次提交的交易流水号，可为 null（没填就无从查起）
     * @param target    收款目标，仅用于日志
     * @return 检测到重复返回 true —— 调用方据此把它挡在「自动通过」之外
     */
    private boolean flagDuplicatePaymentNo(String paymentNo, PaymentTarget target) {
        if (paymentNo == null) {
            return false;
        }
        if (proofMapper.countByPaymentNo(paymentNo) > 1) {
            proofMapper.markDuplicateByPaymentNo(paymentNo);
            log.warn("[凭证] ⚠️ 同一流水号被多笔凭证引用，已标记待人工判断 paymentNo={} 本笔={}#{}",
                    paymentNo, target.getType(), target.getId());
            return true;
        }
        return false;
    }

    /**
     * 这笔凭证能不能自动通过 —— 三道闸缺一不可。
     *
     * <p>把闸设在「识别到有效交易单号」上，是因为那正是用户交错了图时
     * 最直接的反证：随手拍的表情包、隔壁店的收款截图，都读不出一串
     * 能对得上账的交易单号。金额那一道是补强 —— 单号抄得对、金额明显不符，
     * 说明这张图不是这一笔的。
     *
     * @param paymentNo     用户填的、或识别到替他填上的交易单号，可为 null
     * @param duplicateRisk 该单号是否已被别的凭证引用
     * @param ocrAmount     识别到的金额，可为 null（没识别出）
     * @param target        收款目标（金额以它为准）
     * @return 可以自动通过返回 true
     */
    private static boolean canAutoApprove(String paymentNo, boolean duplicateRisk,
                                          BigDecimal ocrAmount, PaymentTarget target) {
        if (paymentNo == null) {
            return false;
        }
        if (duplicateRisk) {
            return false;
        }
        // 识别不出金额时不拦：那只是「没读到」，不是「对不上」
        return ocrAmount == null || target.getAmount() == null
                || ocrAmount.compareTo(target.getAmount()) == 0;
    }

    /**
     * 自动通过之后把凭证置成已核对（复核人留空 = 系统自动）。
     *
     * @param proofId 凭证 ID，可为 null（拿不到时跳过并记 error）
     * @param target  收款目标，仅用于日志
     * @return 真的改成功了返回 true；守卫没放行（并发的那一次）返回 false
     */
    private boolean confirmAutomatically(Long proofId, PaymentTarget target) {
        if (proofId == null) {
            log.error("[凭证] 自动通过时拿不到凭证 ID，未置为已核对 单号={}",
                    target.getOutTradeNo());
            return false;
        }
        if (proofMapper.autoConfirm(proofId) == 0) {
            log.info("[凭证] 自动通过未改动任何记录，视为已被并发处理 proofId={}", proofId);
            return false;
        }
        log.info("[凭证] 已自动通过（识别到交易单号）proofId={} 单号={} 金额={}",
                proofId, target.getOutTradeNo(), target.getAmount());
        return true;
    }

    /**
     * 为什么这笔要人工复核 —— 一句短语，给播报用。
     *
     * <p>四种原因按「哪一道闸没过」给，管理员看一眼就知道该重点核对什么。
     *
     * @param paymentNo     交易单号，可为 null
     * @param duplicateRisk 该单号是否已被别的凭证引用
     * @param ocrAmount     识别到的金额，可为 null
     * @param target        收款目标
     * @return 原因短语
     */
    private static String pendingReasonOf(String paymentNo, boolean duplicateRisk,
                                          BigDecimal ocrAmount, PaymentTarget target) {
        if (paymentNo == null) {
            return "未识别到交易单号";
        }
        if (duplicateRisk) {
            return "该交易单号与另一笔凭证重复";
        }
        if (ocrAmount != null && target.getAmount() != null
                && ocrAmount.compareTo(target.getAmount()) != 0) {
            return "识别到的金额与应付不一致";
        }
        return "该类收款需人工复核";
    }

    /**
     * 按类型找处理器。
     *
     * <p>不手工维护处理器清单 —— 实现类标 {@code @Component} 就会被 Spring
     * 收进 {@link #handlers}，加一类收款不必改这里。
     *
     * @param type 目标类型，可为 null
     * @return 处理器；找不到时返回 null（调用方应记日志）
     */
    private PaymentTargetHandler handlerOf(PaymentTargetType type) {
        if (type == null) {
            return null;
        }
        return handlers.stream().filter(h -> h.type() == type).findFirst().orElse(null);
    }

    /**
     * 按类型名找处理器（用于读库读出来的字符串，如凭证上的 {@code target_type}）。
     *
     * @param targetType 类型名，可为 null
     * @return 处理器；认不出时返回 null
     */
    private PaymentTargetHandler handlerOfName(String targetType) {
        if (targetType == null) {
            return null;
        }
        return handlers.stream().filter(h -> h.type().name().equals(targetType))
                .findFirst().orElse(null);
    }

    /**
     * 该类收款是不是「提交即交付」。
     *
     * <p>认不出类型时返回 false（当作「要等复核」）—— 这个方向的误判最多让人
     * 多看一眼，反过来的误判会让一条其实没有交付的凭证被标成「已交付」。
     *
     * @param targetType 类型名，可为 null
     * @return 提交即交付返回 true
     */
    private boolean isDeliverOnSubmit(String targetType) {
        PaymentTargetHandler handler = handlerOfName(targetType);
        return handler != null && handler.deliverOnSubmit();
    }

    /**
     * 批量取提交人昵称。
     *
     * @param rows 本页凭证
     * @return 用户 ID → 昵称；昵称为空时回落成用户名
     */
    private Map<Long, String> nicknameMapOf(List<PaymentProof> rows) {
        Set<Long> ids = rows.stream().map(PaymentProof::getUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(SysUser::getId, PaymentProofService::displayNameOf,
                        (a, b) -> a));
    }

    /**
     * 批量取收款码显示名。
     *
     * <p>已被删除的码查不出来（逻辑删除），那样 {@code payQrName} 就是空 ——
     * 这是可接受的：凭证上真正锚定的是 {@code pay_qr_id}，名字只是给人看的。
     *
     * @param rows 本页凭证
     * @return 收款码 ID → 显示名
     */
    private Map<Long, String> payQrNameMapOf(List<PaymentProof> rows) {
        Set<Long> ids = rows.stream().map(PaymentProof::getPayQrId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return payQrMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(PayQr::getId, PayQr::getName, (a, b) -> a));
    }

    /**
     * 用户在后台显示成什么。
     *
     * <p>昵称优先、用户名为兜底 —— 昵称在库里可空，为空时显示一片空白
     * 比显示用户名还糟（管理员会以为数据坏了）。
     *
     * @param user 用户实体
     * @return 展示名
     */
    private static String displayNameOf(SysUser user) {
        return user.getNickname() == null || user.getNickname().isBlank()
                ? user.getUsername() : user.getNickname();
    }

    /**
     * 判断一个路径是不是本服务上传的付款截图。
     *
     * <p><b>这道校验挡的是「用户可控的字符串直接落库」</b>：请求体是客户端给的，
     * 不校验的话可以填一个外链 —— 管理员的浏览器会去加载别人的服务器，
     * 等于把「谁在什么时候付款」泄露给第三方；也可以填
     * {@code /uploads/avatar/xxx.jpg} 把别人的头像当成付款凭证展示出来。
     *
     * <p>三条检查缺一不可：
     * <ol>
     *   <li>以 {@code {url-prefix}/proof/} 开头 —— 前缀来自配置，与
     *       {@code PaymentProofImageService} 落盘时用的是同一个值</li>
     *   <li>剩下那段不含路径分隔符 —— 截图文件名就是「UUID.扩展名」，
     *       出现分隔符说明有人想指向别的子目录</li>
     *   <li>不含 {@code ..} —— 字符串前缀挡不住 {@code /uploads/proof/../avatar/x.png}，
     *       与 {@code ImageStorage.deleteByUrl} 上那条注释是同一条纪律</li>
     * </ol>
     *
     * @param url 待校验的图片路径
     * @return 是本服务上传的付款截图返回 true
     */
    private boolean isProofImageUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String prefix = uploadProperties.getUrlPrefix() + "/" + PaymentProofImageService.KIND_PROOF + "/";
        if (!url.startsWith(prefix)) {
            return false;
        }
        String fileName = url.substring(prefix.length());
        return !fileName.contains("/") && !fileName.contains("\\") && !fileName.contains("..");
    }

    /**
     * 把请求体里回传的识别结果写进凭证，<b>越界的一律钳制掉</b>。
     *
     * <p>三个值都来自客户端，所以这里是钳制而不是校验：超长的截掉、
     * 不合理的金额丢掉。理由是<b>这些字段不是用户填的</b> —— 他既改不了它们，
     * 也不该为它们的格式负责。因为它们没对上格式而让「提交付款凭证」整个失败，
     * 是用户完全无法自救的一种报错。
     *
     * <p>⚠️ <b>截断长度必须与列宽一致</b>（{@code ocr_payment_no VARCHAR(64)}、
     * {@code ocr_text VARCHAR(1000)}），否则截了也白截 —— MySQL 会再报一次
     * {@code Data too long}，而且那次是在插入时才炸的。
     *
     * @param proof   待写入的凭证
     * @param request 请求体
     */
    private static void applyOcrFields(PaymentProof proof, ProofSubmitRequest request) {
        proof.setOcrPaymentNo(truncate(trimToNull(request.getOcrPaymentNo()),
                MAX_OCR_PAYMENT_NO_LENGTH));
        proof.setOcrAmount(saneOcrAmount(request.getOcrAmount()));
        proof.setOcrText(truncate(trimToNull(request.getOcrText()),
                OcrTextParser.MAX_TEXT_LENGTH));
    }

    /**
     * 按长度截断，null 原样返回。
     *
     * @param value 原值，可为 null
     * @param max   上限（字符数）
     * @return 截断后的值
     */
    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    /**
     * 把识别金额收进合理区间，不合理的直接丢掉。
     *
     * <p><b>丢掉而不是钳到边界</b>：{@code ocr_amount} 只是个参考值，
     * 把 {@code 999999999} 钳成 {@code 99999.99} 等于凭空编一个数字出来 ——
     * 而 {@code null}（「没识别出金额」）是诚实且无害的。
     *
     * <p>上限取自 {@link OcrTextParser#MAX_AMOUNT}：与 {@code DECIMAL(10,2)}
     * 的容量、以及识别侧用的是同一个值，只有一处定义。
     *
     * @param amount 请求体里的金额，可为 null
     * @return 合理金额，或 null
     */
    private static BigDecimal saneOcrAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0
                || amount.compareTo(OcrTextParser.MAX_AMOUNT) > 0) {
            return null;
        }
        return amount;
    }

    /**
     * 去掉首尾空白，空串归一成 null。
     *
     * @param value 原始值，可为 null
     * @return 处理后的值；空白串返回 null
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
