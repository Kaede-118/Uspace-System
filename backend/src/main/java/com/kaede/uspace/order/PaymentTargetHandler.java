package com.kaede.uspace.order;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.order.dto.PaymentTarget;

import java.time.LocalDateTime;

/**
 * 支付目标处理器 —— 把「订单」与「包场」的差异收在这一层。
 *
 * <p><b>为什么需要它</b>：本系统里需要收钱的东西不止一种，它们在两张表、两套字段上，
 * 但走的是同一套支付通道、同一个回调链路、同一套幂等规则。若支付代码里到处
 * {@code if (是订单) ... else if (是包场) ...}，每加一种收款场景就要改一遍回调逻辑。
 * 抽成处理器之后，回调只认 {@link PaymentTarget} 这个统一结构，
 * 加月卡（模块 9）只需新增一个实现类，回调代码一行都不动。
 *
 * <p>实现类标 {@code @Component} 即可，{@code PaymentService} 用
 * {@code List<PaymentTargetHandler>} 注入 —— Spring 会自动把所有实现收集进来。
 * <b>不需要、也不应该手工维护一张处理器清单</b>：那正是「加了实现却忘了注册」
 * 这类问题的来源。
 */
public interface PaymentTargetHandler {

    /**
     * 本处理器负责哪种支付目标。
     *
     * @return 目标类型
     */
    PaymentTargetType type();

    /**
     * 载入待支付的目标，并校验它属于该用户。
     *
     * <p>供发起支付时使用。校验归属是<b>必须的</b>：少了它，
     * 任何登录用户都能拿到别人订单的支付参数，把钱付到别人的单上
     * （虽然损失的是付款人自己，但足以造成对账混乱）。
     *
     * @param id     目标 ID
     * @param userId 发起支付的用户 ID
     * @return 成功时返回统一结构的目标；不存在、不属于该用户、
     *         或当前状态不允许支付时返回对应的失败码
     */
    BizResult<PaymentTarget> loadForPay(Long id, Long userId);

    /**
     * 按商户订单号载入目标（不做归属校验）。
     *
     * <p>供支付回调使用 —— 回调来自支付平台，没有「当前用户」这个概念，
     * 身份由<b>验签</b>保证而不是由归属校验保证。
     *
     * @param outTradeNo 商户订单号
     * @return 统一结构的目标；单号不存在时返回 null
     */
    PaymentTarget loadByOutTradeNo(String outTradeNo);

    /**
     * 标记目标已支付。
     *
     * <p><b>实现必须带状态守卫</b>（{@code WHERE status = 'PENDING_PAYMENT'}），
     * 并返回「本次是否真的改动了」—— 这是回调幂等的关键一道：
     * 支付平台会重推通知，两条回调同时进来时只有一个能改成功，
     * 另一个拿到 false，调用方据此跳过累加用户消费额那一步。
     *
     * <p>实现抛出的异常会让整个回调事务回滚、并让平台继续重推，
     * 这是有意的：那意味着「钱收了但记录不了」，必须让人看见。
     *
     * @param target        目标
     * @param channel       实际支付通道
     * @param transactionNo 平台交易号
     * @param paidAt        支付完成时刻
     * @param confirmedBy   核销管理员 ID；线上回调与 0 元结清传 null
     * @return 本次是否成功标记（false 表示已被并发处理过，应视为幂等成功）
     */
    boolean markPaid(PaymentTarget target, PaymentChannel channel,
                     String transactionNo, LocalDateTime paidAt, Long confirmedBy);
}
