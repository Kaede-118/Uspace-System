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
     * 本处理器收的钱算哪一类累计消费。
     *
     * <p>{@code ORDER}（订单、包场）累加进 {@code sys_user.order_paid}，
     * {@code CARD}（月卡）累加进 {@code card_paid}。
     *
     * <p><b>刻意声明成抽象方法而不是给 {@link PaymentTarget} 加一个可空字段</b>：
     * 漏实现会编译不过，而漏设字段只会在运行期静默按订单口径累加 ——
     * 卡费被永久记进 {@code order_paid}，没有任何报错。
     *
     * @return 记账品类
     */
    PaidCategory paidCategory();

    /**
     * 用户提交付款凭证时是否立即交付资产；{@code false} 表示等管理员复核后才交付。
     *
     * <p><b>为什么这条差异必须显式声明，而不是让调用方按类型判断</b>：
     * 对本系统里的一半收款来说，<b>「放行」与「落账」是同一件事</b> ——
     * 包场付款成功会生成邀请令牌、月卡付款成功会插入一张卡、
     * 商品付款成功会扣减库存，三件事都写在各自的 {@link #markPaid} 里。
     * 所以「提交凭证后能不能立刻放行」不能只改一个查询条件，
     * 它决定了要不要在提交那一刻就把 {@code markPaid} 走完。
     *
     * <p>取值的依据是<b>这笔钱买的东西能不能当场交付</b>：
     * <ul>
     *   <li>{@code true} —— <b>订单与商品</b>。用户提交凭证就当作钱收到了：
     *       订单转 {@code PAID}（欠费拦截随之解除、可以再开新单）、
     *       商品当场扣库存</li>
     *   <li>{@code false} —— <b>包场与月卡</b>。它们交付的是「一项权益」
     *       （一场包场的排他性、一张 30 天的卡），而权益一旦发出就收不回来，
     *       所以宁可等管理员扫一眼</li>
     * </ul>
     *
     * <p><b>代价要说清楚</b>：返回 true 的处理器是「先交付后复核」，
     * 若复核不通过，系统<b>不会自动回退</b>（订单已支付、库存已扣、累计消费已加），
     * 只能人工处置。所以后台会把这类凭证显著标出来，见 {@code AdminProofVo}。
     *
     * <p>与 {@link #paidCategory()} 同理，<b>刻意声明成抽象方法而不是给默认值</b>：
     * 漏实现要编译不过。给默认值的话，将来新增一类收款时忘了声明，
     * 就会静默按「等复核」处理 —— 而用户以为已经付完了，且没有任何报错。
     *
     * @return 提交凭证即交付返回 true
     */
    boolean deliverOnSubmit();

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
     * 载入目标用于<b>提交付款凭证</b>，并校验它属于该用户。
     *
     * <p>与 {@link #loadForPay} 的<b>唯一区别是不限制目标状态</b> ——
     * 提交凭证这件事在目标已支付之后仍然会发生：用户换一张更清楚的截图、
     * 或者先提交了一次又发现了流水号想补上。<b>而这是常态，不是异常</b>：
     * 订单与商品「提交即交付」，用户提交完的那一刻目标就已经是已支付了
     * （见 {@link #deliverOnSubmit()}），他若想再改一次流水号，
     * {@code loadForPay} 会直接以「该订单当前不需要支付」拒掉。
     *
     * <p>归属校验同样必须有：少了它，任何登录用户都能往别人的单子上传凭证 ——
     * 而凭证是管理员核销的依据，这等于替别人伪造了付款证明。
     *
     * <p>「目标处于什么状态才允许提交」的最终判断<b>不在这里</b>，而在
     * {@code PaymentProofService#submit}：它要区分「待支付」「已支付 + 待复核」
     * 「已支付 + 已核对」等五种情形，各有各的去向。这里只负责
     * 「找到它、并确认它是你的」。
     *
     * @param id     目标 ID
     * @param userId 提交凭证的用户 ID
     * @return 成功时返回目标；不存在或不属于该用户时返回对应的失败码
     */
    BizResult<PaymentTarget> loadForProof(Long id, Long userId);

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

    /**
     * 本处理器是否受理某个支付通道。
     *
     * <p><b>四个处理器当前都受理全部通道</b>，所以没有任何一处覆写它 ——
     * 保留这个钩子是因为它守着一类真实存在过的故障：
     *
     * <p>2026-09-30 之前，人工核销接口是<b>订单专用</b>的，于是月卡与商品
     * 各自覆写本方法挡掉了 {@link PaymentChannel#QR_UPLOAD}。少了那道守卫，
     * 用户对月卡选「传截图」会拿到一个没有任何支付参数的「成功」，
     * 卡则永远停在待支付 —— 表现为「买不了卡」，且线上零报错。
     *
     * <p>凭证流程建成之后「传截图」对四类收款都成立了（统一的
     * {@code biz_payment_proof}，见 {@code PaymentProofService}），
     * 覆写因此全部删掉 —— 通道准入改由 {@code uspace.payment.enabled-channels}
     * 一处把关。但钩子留着：将来若加一条「现金」通道（管理员线下收款），
     * 它可能只对订单开放，那时需要覆写的是这里。
     *
     * @param channel 待校验的支付通道
     * @return 受理返回 true
     */
    default boolean supportsChannel(PaymentChannel channel) {
        return true;
    }
}
