package com.kaede.uspace.order.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kaede.uspace.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单实体，对应 {@code biz_order} 表。
 *
 * <p>一条订单 = 某人一次到店。它同时承担三件事：<b>准入凭证</b>（下发的限时密码）、
 * <b>计费区间</b>（{@code startTime} 到 {@code endTime}）、<b>收款依据</b>（各项金额）。
 *
 * <p><b>⚠️ 金额列的口径</b>：{@code dayAmount} / {@code nightAmount} 与
 * {@code totalAmount} 存的都是<b>实收</b>（已按分段封顶、已含优惠、已扣月卡免除），
 * {@code discountAmount} 与 {@code cardFreeAmount} <b>都只是说明性字段</b> ——
 * 前者已包含在 {@code totalAmount} 里，后者已从中扣除，
 * 两者都不可再用「合计 − 优惠」减第二次。这条在建表脚本里也有标注。
 *
 * <p>{@code payableAmount} 目前恒等于 {@code totalAmount}，是预留的独立列 ——
 * 将来若有优惠券、押金这类不进入计费规则的费用，加在这里而不污染计费结果。
 * 发起支付时取的是它，不是 {@code totalAmount}。
 *
 * @see com.kaede.uspace.order.OrderStatus 状态取值与流转
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_order")
public class Order extends BaseEntity {

    /** 主键。策略取 application.properties 里配的数据库自增 */
    @TableId
    private Long id;

    /**
     * 业务单号，对外展示，唯一索引 {@code uk_order_no}。
     *
     * <p>格式 {@code OD + yyyyMMddHHmmss + 4 位随机}，共 20 位。
     * 它同时是支付平台的<b>商户订单号</b>（{@code out_trade_no}），
     * 回调按它定位订单 —— 所以前缀 {@code OD} 不只是给人看的，
     * 也是回调路由包场（{@code BK}）与订单的依据。
     */
    private String orderNo;

    /** 下单用户 ID */
    private Long userId;

    /** 使用门店 ID */
    private Long storeId;

    /** 门锁 ID，点击开门时快照。此后换锁也不影响这张历史订单 */
    private Long lockId;

    /**
     * 关联的包场 ID。进店时命中包场才记，结算时据此把包场时段从计费区间剪掉。
     *
     * <p><b>为 null 不代表这个用户没有包场</b>：包场人可能提前到店，
     * 那时包场还没开始、三层准入判断落到「普通」分支，订单就没挂包场 ID。
     * 所以结算时还要按 {@code hostUserId} 回查一次，否则他会被重复计费。
     */
    private Long bookingId;

    /** 下发的限时密码。仅模拟/真实门锁使用，不对外暴露在订单视图里 */
    private String passcode;

    /** 密码生效时间 */
    private LocalDateTime passcodeStart;

    /** 密码失效时间。过期后用户点「查看密码」会自动续期（不换密码，只推有效期） */
    private LocalDateTime passcodeEnd;

    /**
     * 群指令（{@code fw开门}）下发的一次性密码，<b>用一次即焚</b>。
     *
     * <p>它与上面的 {@link #passcode} 是<b>两条独立的进门路径</b>：那一串是可反复使用的
     * 限时密码（网页端可查、私聊发一份，用于兜底）；这一串只在群里发、只能开一次门。
     * 每次 {@code fw开门} 都会重新取一串 —— 不做复用，理由见
     * {@code OneTimePasscodeService} 的类注释。
     *
     * <p><b>⚠️ 它绝不能出现在任何订单视图里</b>（{@code OrderVo} / {@code OrderOpenVo}
     * 都不带它），只在 {@code qqbot} 包内部消费。结算时会被撤销，但列值不清空
     * （与 passcode 三列同构，保留为历史痕迹）。
     */
    private String oneTimePasscode;

    /** 一次性密码的失效时刻（生成起 6 小时，通通锁单次密码的固有规则） */
    private LocalDateTime oneTimePasscodeEnd;

    /** 计费起点 = 用户点击「开门」的时刻。与订单创建同一时刻 */
    private LocalDateTime startTime;

    /** 离场时刻。使用中为 null */
    private LocalDateTime endTime;

    /**
     * 在店时长（分钟）= {@code endTime − startTime}，向下取整。
     *
     * <p><b>它不参与计费</b>，只是「这个人在店里待了多久」这一事实的记录，
     * 供「我的」页展示与累计时长统计使用（{@code GET /api/orders/me/stats}）。
     *
     * <p><b>⚠️ 与 {@link #dayMinutes} + {@link #nightMinutes} 的区别，两者不可互相替代</b>：
     * 后两者是<b>计费时长</b> —— 包场时段被剪掉了（见 {@link #bookingId}），
     * 宽限的那 5 分钟也不计入。所以「包场 2 小时、扣掉后计费 0 分钟」的订单，
     * 用计费口径算出来是「在店 0 分钟」，而人明明待了 2 小时。
     *
     * <p>使用中（{@code IN_USE}）时为 null —— 那时没有离场时刻，算不出定值。
     * 要展示「已经待了多久」得实时算（{@code Duration.between(startTime, now)}），
     * 但口径要与本字段一致：同样截断到整分钟。
     */
    private Integer stayMinutes;

    /** 日场时长（分钟） */
    private Integer dayMinutes;

    /** 日场费用（实收，已封顶、已含优惠） */
    private BigDecimal dayAmount;

    /** 夜场时长（分钟） */
    private Integer nightMinutes;

    /** 夜场费用（实收，已封顶、已含优惠） */
    private BigDecimal nightAmount;

    /** 实收合计 = 日场 + 夜场。各段已分别封顶 */
    private BigDecimal totalAmount;

    /** 本单优惠金额（月度累计优惠）。说明性字段，已包含在 {@link #totalAmount} 中，不可再减第二次 */
    private BigDecimal discountAmount;

    /**
     * 本单因月卡免掉的金额。说明性字段，<b>已从 {@link #totalAmount} 中扣除</b>，
     * 同样不可再减第二次。
     *
     * <p>与 {@link #discountAmount} 是两回事：那是月度累计优惠为本单省下的钱，
     * 这是月卡覆盖的时段本来要收的钱。两种优惠并行存在、互不重叠 ——
     * 混在一起记的话，统计里「优惠活动的效果」会虚高，且事后拆不开。
     *
     * <p>口径是「<b>不持卡时本单应付的金额</b>」，已含月度优惠价。
     * 无卡或卡未覆盖任何段时为 0。
     */
    private BigDecimal cardFreeAmount;

    /**
     * 免费活动为本单免掉的金额（元）。不在活动区间内时为 0。
     *
     * <p>与 {@link #cardFreeAmount} 同为说明性字段、已从 {@link #totalAmount} 扣除。
     *
     * <p>⚠️ <b>不含被月卡覆盖的段</b>：月卡用户本来就免费，活动并没有为他省下什么。
     * 两个口径混在一起的话，复盘一场活动「送出去多少钱」会虚高 ——
     * 而那种错不会有任何报错，只会让运营以为活动比实际更划算。
     */
    private BigDecimal activityFreeAmount;

    /** 应付金额。当前等于 {@link #totalAmount}，预留独立列供将来的优惠券、押金等 */
    private BigDecimal payableAmount;

    /**
     * 结算那一刻的分段账单快照（JSON），内容为 {@code BillingResult} + freeByBooking 的序列化结果。
     *
     * <p><b>为什么要有快照</b>：上面那两对日场/夜场列只是汇总投影 —— 段的边界、档数、
     * 单价、封顶与免单标记都不是「合计」能还原出来的，而订单详情页要把它们逐段展示出来。
     * 存快照而不是详情时现算，是因为计费规则与价格会调整：已结算的订单必须永远按
     * 当时那份账单显示（现算只能用来给快照之前的老订单兜底）。
     *
     * <p>NULL = 尚未结算，或快照机制（2026-10-03）上线前结算的老订单。
     * 读取侧不必区分这两种情形：解析失败与为空走的是同一条回落路径。
     *
     * <p>本字段以 JSON 字符串形态读写（列类型 JSON），序列化与解析都在
     * {@code OrderService} 里，且一律 fail-soft —— 快照是【展示用的副本】，
     * 任何一环坏掉都不该让订单本身读不出来。
     */
    private String billSnapshot;

    /** 状态名，取值见 {@link com.kaede.uspace.order.OrderStatus} */
    private String status;

    /** 支付通道名，取值见 {@link com.kaede.uspace.order.PaymentChannel}。0 元自动结清时为空 */
    private String paymentMethod;

    /*
     * 支付截图（payment_proof）没有对应的字段，这不是遗漏。
     *
     * 2026-09-30 起付款凭证改由 biz_payment_proof 表承载（见 PaymentProof）——
     * 四类收款共用一个入口，而且「复核状态」「交易流水号索引」都不是一列装得下的。
     * 库里 biz_order.payment_proof 那一列还留着（DROP 不可逆，历史数据不该丢），
     * 但【刻意不给它映射字段】：映射了就会有人写它，而 updateById 跳过 null
     * 的语义会让一次普通的订单更新把凭证静默清空。
     */

    /** 支付平台交易号：微信 transaction_id / 支付宝 trade_no。凭证路径下为用户填写的流水号 */
    private String paymentNo;

    /** 支付完成时刻。0 元自动结清时也写，便于对账时区分「不用付」与「没记录」 */
    private LocalDateTime paidAt;

    /** 核销管理员 ID。为空表示系统自动确认（线上回调或 0 元结清） */
    private Long confirmedBy;

    /** 时长是否经人工调整：0=否 1=是 */
    private Integer adjusted;

    /** 调整人（管理员 ID） */
    private Long adjustedBy;

    /** 调整时间 */
    private LocalDateTime adjustedAt;

    /** 调整原因，如「用户忘记结束，监控核实 21:30 已离场」 */
    private String adjustReason;

    /** 备注 */
    private String remark;
}
