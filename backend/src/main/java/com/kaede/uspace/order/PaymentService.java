package com.kaede.uspace.order;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.order.dto.CreatePaymentRequest;
import com.kaede.uspace.order.dto.PaymentCreateCommand;
import com.kaede.uspace.order.dto.PaymentCreateResult;
import com.kaede.uspace.order.dto.PaymentCreateVo;
import com.kaede.uspace.order.dto.PaymentNotifyRequest;
import com.kaede.uspace.order.dto.PaymentNotifyResult;
import com.kaede.uspace.order.dto.PaymentQueryResult;
import com.kaede.uspace.order.dto.PaymentStatusVo;
import com.kaede.uspace.order.dto.PaymentTarget;
import com.kaede.uspace.user.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 支付服务（模块 8）。
 *
 * <p>职责：统一发起支付、处理支付平台回调、主动查单补偿、付款凭证落账。
 *
 * <p><b>它不认识订单，也不认识包场</b> —— 所有与具体实体的打交道都通过
 * {@link PaymentTargetHandler} 完成。这样回调链路只写一遍，
 * 加一类收款（如模块 9 的月卡）只需新增一个处理器实现类。
 *
 * <h3>三条必须设计进去的约束</h3>
 * <ol>
 *   <li><b>回调会重推，必须幂等</b> —— 以商户订单号为幂等键。
 *       四道防线：状态预检、条件 UPDATE 的受影响行数、用户累计用相对更新、
 *       单号上的唯一索引</li>
 *   <li><b>回调可能延迟或丢失</b> —— 提供 {@link #queryPayment} 主动查单补偿。
 *       支付成功而回调未达在生产环境是必然事件，不是意外</li>
 *   <li><b>三个通道共用一套回调处理与状态机</b> —— 微信的 JSAPI 与 H5
 *       共用一个端点，支付宝因协议不同单独一个端点，但三者验签通过后
 *       走的是同一段 {@link #applyNotify}。加通道不必动订单模块</li>
 * </ol>
 */
@Slf4j
@Service
public class PaymentService {

    private final PaymentGateway paymentGateway;
    private final PaymentProperties properties;
    private final List<PaymentTargetHandler> handlers;
    private final SysUserMapper userMapper;

    public PaymentService(PaymentGateway paymentGateway,
                          PaymentProperties properties,
                          List<PaymentTargetHandler> handlers,
                          SysUserMapper userMapper) {
        this.paymentGateway = paymentGateway;
        this.properties = properties;
        this.handlers = handlers;
        this.userMapper = userMapper;
    }

    // ==================================================================
    // 发起支付
    // ==================================================================

    /**
     * 发起支付：校验目标、调网关下单、返回各通道的支付参数。
     *
     * <p><b>这个方法不写库</b>：发起支付没有需要落库的状态 ——
     * 用户拉起支付又放弃是常事，为此在订单上记一笔「发起过」只会产生噪音。
     * 真正改变状态的是回调。
     *
     * <p>注意 {@link PaymentChannel#QR_UPLOAD} 不走网关：它表示用户打算
     * 走「传截图给管理员核销」的降级路径，那条路上没有线上支付可言，
     * 调用网关只会白白失败一次。
     *
     * @param userId   当前登录用户 ID
     * @param request  支付请求
     * @param clientIp 用户端 IP，微信 H5 支付的风控参数；模拟实现忽略
     * @return 成功时返回支付参数；目标不存在/不属于该用户/状态不对时返回对应失败码，
     *         网关调用失败时返回 {@link ErrorCode#PAYMENT_GATEWAY_UNAVAILABLE}
     */
    public BizResult<PaymentCreateVo> createPayment(Long userId, CreatePaymentRequest request,
                                                    String clientIp) {
        PaymentTargetHandler handler = handlerOf(request.getTargetType());
        if (handler == null) {
            return BizResult.fail(ErrorCode.PARAM_INVALID, "不支持的支付目标类型");
        }

        // 本店收不收这条通道。放在查目标之前 —— 通道没开放时不必去查库，
        // 而且这个判断与结果无关，与给前端展示选项用的是同一个
        // （PaymentChannelService#listFor 也调它），两处必须一致：
        // 判得不一样就会出现「页面上选得到、点下去报错」
        PaymentChannel channel = request.getChannel();
        if (channel == null || !properties.isChannelEnabled(channel.name())) {
            log.warn("[支付] 拒绝使用未开放的通道 targetType={} channel={}",
                    request.getTargetType(), channel);
            return BizResult.fail(ErrorCode.PAYMENT_CHANNEL_DISABLED);
        }

        BizResult<PaymentTarget> loaded = handler.loadForPay(request.getTargetId(), userId);
        if (!loaded.isSuccess()) {
            return BizResult.fail(loaded.getError(), loaded.getMessage());
        }
        PaymentTarget target = loaded.getData();

        // 这类收款受不受理这条通道。有些收款方式没有人工核销的降级路径（月卡就是），
        // 而那条路径不调网关，会返回一个「成功」却没有任何支付参数 ——
        // 用户以为付得了款，单据却永远停在待支付，且线上没有任何报错。
        // 宁可在这一步就拒掉
        if (!handler.supportsChannel(channel)) {
            return BizResult.fail(ErrorCode.PARAM_INVALID, "该支付方式暂不支持此类收款，请选择线上支付");
        }

        PaymentCreateVo vo = new PaymentCreateVo();
        vo.setOutTradeNo(target.getOutTradeNo());
        vo.setTargetType(target.getType());
        vo.setTargetId(target.getId());
        vo.setChannel(channel.name());
        vo.setChannelLabel(channel.getLabel());
        vo.setAmount(target.getAmount());

        // 人工核销路径不调网关 —— 没有线上支付可发起
        if (!PaymentChannel.isOnline(channel.name())) {
            return BizResult.ok(vo);
        }

        PaymentCreateCommand command = new PaymentCreateCommand();
        command.setOutTradeNo(target.getOutTradeNo());
        command.setAmount(target.getAmount());
        command.setDescription(target.getDescription());
        command.setChannel(channel);
        command.setClientIp(clientIp);

        PaymentCreateResult result = paymentGateway.createPayment(command);
        if (!result.isSuccess()) {
            log.warn("[支付] 网关下单失败 outTradeNo={} 通道={} errmsg={}",
                    target.getOutTradeNo(), channel, result.getErrmsg());
            return BizResult.fail(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE,
                    "发起支付失败：" + result.getErrmsg());
        }

        vo.setPrepayId(result.getPrepayId());
        vo.setH5Url(result.getH5Url());
        vo.setFormHtml(result.getFormHtml());
        vo.setMockPayUrl(result.getMockPayUrl());
        vo.setExpireHint(expireHintOf(channel));

        log.info("[支付] 发起支付 outTradeNo={} 通道={} 金额={}",
                target.getOutTradeNo(), channel, target.getAmount());
        return BizResult.ok(vo);
    }

    // ==================================================================
    // 主动查单（回调丢失的补偿路径）
    // ==================================================================

    /**
     * 主动向支付平台查询支付结果，若平台已收款而本地未更新则补上。
     *
     * <p><b>补偿的做法是「包装成一次等价的回调」</b>，走同一个
     * {@link #applyNotify} —— 而不是另写一段「直接把订单改成已支付」的代码。
     * 后者会绕过金额核对与状态检查，两套逻辑也迟早漂移。
     *
     * <p>本方法加了事务（虽然它主要是查询）：补偿路径会写库，
     * 而它内部是自调用 {@code applyNotify}，不经过 Spring 代理，
     * 靠的正是这里的 {@code @Transactional}。
     * 代价是调网关期间持有数据库连接 —— 单店规模下可以接受。
     *
     * @param userId     当前登录用户 ID，用于归属校验
     * @param outTradeNo 商户订单号
     * @param channel    当时使用的通道，决定查哪个平台
     * @return 查询后的支付状态
     */
    @Transactional
    public BizResult<PaymentStatusVo> queryPayment(Long userId, String outTradeNo,
                                                   PaymentChannel channel) {
        if (channel == null) {
            return BizResult.fail(ErrorCode.PARAM_INVALID, "查询支付状态需要指明支付通道");
        }
        PaymentTargetHandler handler = handlerOfOrderNo(outTradeNo);
        if (handler == null) {
            return BizResult.fail(ErrorCode.NOT_FOUND, "支付单号不存在");
        }
        PaymentTarget target = handler.loadByOutTradeNo(outTradeNo);
        // 归属不符与不存在返回同一个结果 —— 否则可以靠错误码枚举出别人的单号
        if (target == null || !target.getUserId().equals(userId)) {
            return BizResult.fail(ErrorCode.NOT_FOUND, "支付单号不存在");
        }

        if (target.isPaid()) {
            return BizResult.ok(statusVoOf(target, "已支付完成"));
        }

        PaymentQueryResult query = paymentGateway.queryPayment(outTradeNo, channel);
        if (!query.isSuccess()) {
            log.warn("[支付] 主动查单失败 outTradeNo={} errmsg={}", outTradeNo, query.getErrmsg());
            return BizResult.fail(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE,
                    "查询支付状态失败：" + query.getErrmsg());
        }

        if (!query.isPaid()) {
            return BizResult.ok(statusVoOf(target, "支付尚未完成，若已付款请稍候片刻再查"));
        }

        // 平台说已支付、本地还没更新 —— 走与回调完全相同的处理逻辑补上
        applyNotify(toNotifyResult(query, channel));

        PaymentTarget refreshed = handler.loadByOutTradeNo(outTradeNo);
        if (refreshed.isPaid()) {
            return BizResult.ok(statusVoOf(refreshed, "支付已到账"));
        }
        // 补写失败（金额不符、状态冲突等）会在 applyNotify 里记 error 日志
        return BizResult.ok(statusVoOf(target, "平台已收款但本地状态未能更新，请联系管理员核对"));
    }

    // ==================================================================
    // 回调
    // ==================================================================

    /**
     * 处理微信支付回调（JSAPI 与 H5 共用）。
     *
     * <p>微信的两个通道是同一个 API v3 协议、同一个报文结构，所以只有一个端点、
     * 一个处理方法。通道的区分落在 {@code payment_method} 列上，由报文里的
     * {@code trade_type} 判定。
     *
     * @param request 原始回调报文（含签名头）
     * @return true 表示本次处理完成（含幂等与「单号不存在」），平台无需重推；
     *         false 表示应当让平台继续重推并引起人工注意
     */
    @Transactional
    public boolean handleWxpayNotify(PaymentNotifyRequest request) {
        PaymentNotifyResult notify = paymentGateway.verifyAndParseWxpay(request);
        if (!notify.isSuccess()) {
            log.error("[支付] 微信回调验签失败：{}", notify.getErrmsg());
            return false;
        }
        return applyNotify(notify);
    }

    /**
     * 处理支付宝异步通知。
     *
     * <p>协议与微信完全不同（表单验签 vs. RSA 验签 + AES 解密），所以端点与
     * 解析方法都单独一个 —— 但验签通过之后，走的是与微信完全相同的
     * {@link #applyNotify}。
     *
     * @param request 原始回调报文（表单参数）
     * @return 同 {@link #handleWxpayNotify}
     */
    @Transactional
    public boolean handleAlipayNotify(PaymentNotifyRequest request) {
        PaymentNotifyResult notify = paymentGateway.verifyAndParseAlipay(request);
        if (!notify.isSuccess()) {
            log.error("[支付] 支付宝回调验签失败：{}", notify.getErrmsg());
            return false;
        }
        return applyNotify(notify);
    }

    /**
     * 三个通道共用的回调处理：幂等 → 金额核对 → 流转状态 → 累加用户消费额。
     *
     * <p><b>返回值的含义是「这次通知处理完了吗」，不是「成功了吗」</b>，
     * 它直接决定给支付平台什么应答：
     * <table border="1">
     *   <caption>应答规则</caption>
     *   <tr><th>情形</th><th>应答</th><th>理由</th></tr>
     *   <tr><td>处理成功 / 重复回调 / 单号不存在</td><td>成功</td><td>重推无意义</td></tr>
     *   <tr><td>金额不符 / 状态冲突 / 验签失败</td><td>失败</td>
     *       <td>让平台持续重推，同时 error 日志引起人工注意</td></tr>
     * </table>
     *
     * @param notify 验签后的回调内容
     * @return true 表示处理完成，平台无需重推
     */
    private boolean applyNotify(PaymentNotifyResult notify) {
        // 验签通过但用户没付钱 —— 支付宝会推交易关闭、退款等通知，
        // 它们都是真的、但都不该把订单标成已支付。
        // 这里返回 true 让平台别再推：这条通知我们收到了、也认了，只是不需要动作。
        if (!notify.isPaid()) {
            log.info("[支付] 收到非成功通知，不改变状态 outTradeNo={}", notify.getOutTradeNo());
            return true;
        }

        PaymentTargetHandler handler = handlerOfOrderNo(notify.getOutTradeNo());
        if (handler == null) {
            // 单号前缀认不出，说明要么是我们改了单号规则而漏改路由，
            // 要么是有人往这个端点发了构造的报文。两种情况重推都无意义，
            // 但都必须留下 error 日志让人看见。
            log.error("[支付] 回调的单号前缀无法识别，需人工核对：{}", notify.getOutTradeNo());
            return true;
        }

        PaymentTarget target = handler.loadByOutTradeNo(notify.getOutTradeNo());
        if (target == null) {
            log.error("[支付] 回调的单号在本系统不存在，需人工核对：{}", notify.getOutTradeNo());
            return true;
        }

        // 幂等第一道：已支付直接返回。支付平台会重推，这条是最常走到的分支
        if (target.isPaid()) {
            log.info("[支付] 重复回调，订单已支付，幂等返回 outTradeNo={}", notify.getOutTradeNo());
            return true;
        }

        if (!target.isPendingPayment()) {
            // 钱收了，但单据处于不该收款的状态（如包场被取消、订单被调整）。
            // 这属于「钱与单不一致」，必须让人看见而不是静默吞掉
            log.error("[支付] 单号 {} 处于 {} 状态却收到支付成功回调 —— 钱与单不一致，必须人工介入",
                    notify.getOutTradeNo(), target.getStatus());
            return false;
        }

        // 金额核对。回调端点匿名可达，验签是主要防线，这里的是第二道 ——
        // 无论报文怎么来的，金额对不上就绝不入账
        if (notify.getAmount() == null || target.getAmount() == null
                || notify.getAmount().compareTo(target.getAmount()) != 0) {
            log.error("[支付] 金额不符：回调 {} 元，本地 {} 元，单号 {}",
                    notify.getAmount(), target.getAmount(), notify.getOutTradeNo());
            return false;
        }

        LocalDateTime paidAt = notify.getPaidAt() == null
                ? LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
                : notify.getPaidAt();

        // 幂等第二、三道：带状态守卫的 UPDATE（受影响行数）与相对更新累加
        boolean changed = handler.markPaid(target, notify.getChannel(),
                notify.getTransactionNo(), paidAt, null);
        if (!changed) {
            log.info("[支付] 本次回调未改动任何记录，视为已被并发处理 outTradeNo={}",
                    notify.getOutTradeNo());
            return true;
        }

        // 累加用户的终生消费额。用相对更新（xxx_paid = xxx_paid + x），
        // 并发下不会丢更新 —— 这是幂等的第三道防线。
        //
        // 累加到哪一列由处理器声明：订单与包场记 order_paid，月卡卡费记 card_paid。
        // 这里是唯一一处「所有收款共用」的写库点，写错列不会有任何报错，
        // 只会让两个累计口径悄悄错位
        accumulatePaid(handler, target);
        log.info("[支付] 支付完成并已累加用户{} outTradeNo={} userId={} 金额={}",
                handler.paidCategory().getLabel(), notify.getOutTradeNo(),
                target.getUserId(), target.getAmount());
        return true;
    }

    /**
     * 按处理器声明的品类累加用户的累计消费。
     *
     * <p>用穷尽 switch：将来新增品类时漏处理会<b>编译不过</b>，
     * 而不是静默地什么都不记（那意味着用户的钱花了、累计却没涨）。
     *
     * @param handler 处理本次收款的处理器
     * @param target  支付目标
     */
    private void accumulatePaid(PaymentTargetHandler handler, PaymentTarget target) {
        switch (handler.paidCategory()) {
            case CARD -> userMapper.addCardPaidAmount(target.getUserId(), target.getAmount());
            case ORDER -> userMapper.addOrderPaidAmount(target.getUserId(), target.getAmount());
        }
    }

    // ==================================================================
    // 凭证落账（扫码转账，2026-09-30 起投产的唯一收款方式）
    // ==================================================================

    /**
     * 用户提交付款凭证后的落账 —— 与线上回调写的是同一批字段、
     * 走的是同一个处理器的 {@code markPaid}、同一套累计分派。
     *
     * <p><b>两处调用它，时机不同</b>：
     * <ul>
     *   <li><b>订单与商品</b>在用户提交凭证那一刻调（「提交即交付」）——
     *       订单当场转 {@code PAID}，欠费拦截随之解除</li>
     *   <li><b>包场与月卡</b>在管理员复核通过时调 ——
     *       邀请令牌与月卡都产生在这一步</li>
     * </ul>
     * 哪一类走哪条路由由 {@code PaymentTargetHandler#deliverOnSubmit} 说了算，
     * 本方法不判断类型。
     *
     * <p><b>传播行为取 MANDATORY</b>：它必须跑在调用方的事务里 ——
     * 否则会出现「凭证落了库、钱没落账」或反过来的半成品状态，
     * 而这两种状态都没人盯着（凭证的复核状态会显示成正常，钱却没进来）。
     * 取 MANDATORY 而不是 REQUIRED：后者在没有事务时会自己开一个，
     * 那正是要避免的情形；MANDATORY 在没有事务时直接抛异常，
     * 把问题暴露在开发期而不是生产期。
     *
     * <p>与 {@code applyNotify} 的关系：那是<b>回调</b>的入口（带验签、幂等、
     * 金额核对），这是<b>凭证</b>的入口。两者最终都落到 {@code markPaid} +
     * {@code accumulatePaid}，所以「钱到账」这件事只有一套写法。
     *
     * @param target      支付目标
     * @param channel     实际收款通道，凭证路径恒为 {@link PaymentChannel#QR_UPLOAD}
     * @param paymentNo   用户填写的交易流水号，可空
     * @param confirmedBy 确认人：系统自动落账传 null，管理员复核传其 ID
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void settleByProof(PaymentTarget target, PaymentChannel channel,
                              String paymentNo, Long confirmedBy) {
        // 已经落过账的直接返回。走到这里有两种情形，都是正常的：
        // 并发双击的第二次；或者管理员复核一笔「提交即交付」的凭证 ——
        // 那笔在用户提交那一刻就已经落过账了，复核只是登记
        if (target.isPaid()) {
            log.info("[支付] 该笔已支付，凭证落账跳过 outTradeNo={}", target.getOutTradeNo());
            return;
        }

        PaymentTargetHandler handler = handlerOf(target.getType());
        if (handler == null) {
            // 到不了这里：target 是由某个处理器造出来的。真出现说明有人改了
            // 处理器的注册方式，必须让人看见而不是静默不落账
            log.error("[支付] 凭证落账时收款类型认不出，未落账 target={} 单号={}",
                    target.getType(), target.getOutTradeNo());
            return;
        }

        boolean changed = handler.markPaid(target, channel, paymentNo,
                LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS), confirmedBy);
        if (!changed) {
            // markPaid 带状态守卫，返回 0 说明这一瞬间状态被改动了 ——
            // 与回调那条路上的处理一致：视为已被并发处理，不再累加消费额
            log.info("[支付] 凭证落账未改动任何记录，视为已被并发处理 outTradeNo={}",
                    target.getOutTradeNo());
            return;
        }

        accumulatePaid(handler, target);
        log.info("[支付] 凭证落账完成 outTradeNo={} 通道={} 类型={} 金额={} 累计={} 确认人={}",
                target.getOutTradeNo(), channel, target.getType(), target.getAmount(),
                handler.paidCategory().getLabel(),
                confirmedBy == null ? "系统自动" : confirmedBy);
    }

    // ==================================================================
    // 供模拟收银台使用
    // ==================================================================

    /**
     * 判断某笔支付单是否属于该用户。
     *
     * <p>供模拟收银台在「模拟支付」之前做归属校验。这一步不能省：
     * 少了它，任何登录用户只要知道别人的订单号，就能把别人的订单标记成已支付 ——
     * 这是演示系统里最容易漏掉的一个真漏洞。
     *
     * <p>不做成「查单」是因为那个方法会真的去调支付平台，而这里只需要一个
     * 「是不是你的」的答案。
     *
     * @param userId     用户 ID
     * @param outTradeNo 商户订单号
     * @return 属于该用户返回 true；单号认不出或不存在时返回 false
     */
    public boolean isOwnedBy(Long userId, String outTradeNo) {
        PaymentTargetHandler handler = handlerOfOrderNo(outTradeNo);
        if (handler == null) {
            return false;
        }
        PaymentTarget target = handler.loadByOutTradeNo(outTradeNo);
        return target != null && target.getUserId().equals(userId);
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /**
     * 按类型找处理器。
     *
     * <p>不手工维护处理器清单 —— 实现类标 {@code @Component} 就会被 Spring
     * 收进 {@link #handlers}，加一类收款不必改这里。
     *
     * @param type 目标类型，可为 null
     * @return 处理器；找不到时返回 null
     */
    private PaymentTargetHandler handlerOf(PaymentTargetType type) {
        if (type == null) {
            return null;
        }
        return handlers.stream().filter(h -> h.type() == type).findFirst().orElse(null);
    }

    /**
     * 按商户订单号前缀找处理器。
     *
     * @param outTradeNo 商户订单号
     * @return 处理器；前缀认不出时返回 null
     */
    private PaymentTargetHandler handlerOfOrderNo(String outTradeNo) {
        return handlerOf(PaymentTargetType.fromOrderNo(outTradeNo));
    }

    /**
     * 把查单结果包装成与回调等价的处理入参。
     *
     * <p>这样补偿路径可以复用 {@link #applyNotify} 的全部逻辑
     * （幂等、金额核对、状态守卫），不必另写一套。
     *
     * @param query   查单结果（已知 {@code paid=true}）
     * @param channel 通道
     * @return 与回调同构的结果对象
     */
    private static PaymentNotifyResult toNotifyResult(PaymentQueryResult query, PaymentChannel channel) {
        PaymentNotifyResult notify = new PaymentNotifyResult();
        notify.setSuccess(true);
        notify.setPaid(true);
        notify.setOutTradeNo(query.getOutTradeNo());
        notify.setTransactionNo(query.getTransactionNo());
        notify.setAmount(query.getAmount());
        notify.setChannel(channel);
        notify.setPaidAt(query.getPaidAt());
        return notify;
    }

    /**
     * 组装支付状态视图。
     *
     * @param target  支付目标
     * @param message 给用户看的一句话
     * @return 状态视图
     */
    private static PaymentStatusVo statusVoOf(PaymentTarget target, String message) {
        PaymentStatusVo vo = new PaymentStatusVo();
        vo.setOutTradeNo(target.getOutTradeNo());
        vo.setPaid(target.isPaid());
        vo.setStatus(target.getStatus());
        vo.setStatusText(target.isPaid() ? "已支付" : "待支付");
        vo.setPaymentMethod(target.getPaymentMethod());
        vo.setPaymentNo(target.getPaymentNo());
        vo.setPaidAt(target.getPaidAt());
        vo.setAmount(target.getAmount());
        vo.setMessage(message);
        return vo;
    }

    /**
     * 各通道的有效期提示。
     *
     * <p>由后端给而不是前端写死：三个通道的有效期差别极大
     * （JSAPI 2 小时、H5 只有 5 分钟），前端按通道写死文案，
     * 一旦调参就会对不上。
     *
     * @param channel 通道
     * @return 中文提示
     */
    private static String expireHintOf(PaymentChannel channel) {
        return switch (channel) {
            case WXPAY_JSAPI -> "支付凭据 2 小时内有效，未完成前可重复发起";
            case WXPAY_H5 -> "支付链接 5 分钟内有效，请立即完成支付";
            case ALIPAY_WAP -> "请在打开的支付宝页面中完成支付";
            case QR_UPLOAD -> "请上传付款截图，管理员核对后为你确认";
        };
    }
}
