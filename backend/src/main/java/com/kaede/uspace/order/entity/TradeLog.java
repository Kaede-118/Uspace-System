package com.kaede.uspace.order.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 交易流水实体，对应 {@code biz_trade_log} 表（模块 8 的支付能力，2026-10-10 加）。
 *
 * <p><b>它存在的理由：把「钱这件事发生过什么」收在一处。</b>
 * 在此之前，一笔收款的痕迹散在四张表上（订单 / 商品购买单 / 包场 / 月卡购买单
 * 各自记自己的支付字段），而「取消了一笔没付款的单」这种<b>没有钱发生、
 * 但同样是交易事实</b>的动作则完全没有记录。要回答「这一天到底发生了什么」，
 * 得把四张表都翻一遍，还得知道去哪张表翻。
 *
 * <p>四类事件的取值见 {@link com.kaede.uspace.order.TradeEventType}。
 * 每一条都带着<b>对外单号</b>（{@code targetNo}）：运营与顾客都只认单号，
 * 而单号前缀本身已经能区分类型（{@code OD} 订单 / {@code PD} 商品 /
 * {@code BK} 包场 / 月卡自己的前缀）。
 *
 * <p><b>本表是 append-only 的流水</b>：写进去就不再改、也不适用逻辑删除 ——
 * 「某时某刻发生过什么」是既成事实，改它等于篡改账目。
 * 与 {@code biz_payment_proof}、{@code biz_reconcile_batch} 同一条纪律。
 *
 * <p><b>刻意不继承 {@code BaseEntity}</b>：基类带 {@code updated_at} 与
 * {@code deleted} 两列，而本表只有 {@code created_at}。继承的话 MyBatis-Plus
 * 会往 INSERT 里塞两个库里不存在的列，直接 {@code Unknown column}。
 * 理由与 {@code AccessRecord} 一字不差地相同 —— 那边也是 append-only 的事件表。
 *
 * <p>⚠️ <b>写这笔账与业务动作同事务</b>（{@code TradeLogService} 里不吞异常）：
 * 绝不能出现「钱收了、流水没有」或「单取消了、流水没有」。这条纪律写在
 * {@code TradeLogService} 的类注释里，改动前先读那一段。
 */
@Data
@TableName("biz_trade_log")
public class TradeLog {

    /** 主键。策略取 application.properties 里配的数据库自增 */
    @TableId
    private Long id;

    /**
     * 事件类型，取值见 {@link com.kaede.uspace.order.TradeEventType}。
     *
     * <p>存枚举名（{@code VARCHAR(24)}，无库级约束）。
     */
    private String eventType;

    /**
     * 收款目标类型，取值与 {@link com.kaede.uspace.order.PaymentTargetType} 一致
     * （{@code ORDER} / {@code PRODUCT} / {@code BOOKING} / {@code MONTHLY_CARD}）。
     *
     * <p>这里存字符串而不是直接引用那个枚举：流水的写入方里有三个住在别的包里
     * （见 {@code TradeLogService} 的类注释），它们说这个词汇的方式与支付侧一致、
     * 但不必因此把这个枚举搬去公共层。
     */
    private String targetType;

    /**
     * 对外单号（订单号 / 商品购买单号 / 包场单号 / 月卡购买单号），不为空。
     *
     * <p><b>它是流水与业务单据之间唯一的锚</b>：没有外键、也不存目标主键，
     * 事后核对拿它去对应模块查即可 —— 存主键反而会在「单据被删」时留下死指针。
     */
    private String targetNo;

    /** 相关用户 ID。系统自动发生的动作（线上回调）也必有其人，故不为空 */
    private Long userId;

    /**
     * 操作人（审核人）ID，可为空 ——「代劳的人」。
     *
     * <p>管理员复核通过、驳回、在后台取消时会记其 ID；<b>为空表示这一行不是管理员
     * 代劳的</b>：用户自己的提交与取消、以及系统自动的到账（线上回调、
     * 提交即落账）都为空 —— 那两种情形靠 {@link #source} 区分
     * （{@code WEB} / {@code QQ} 是用户自己，{@code SYSTEM} 是系统）。
     *
     * <p><b>为什么要有这一列</b>（2026-10-10 由用户提出）：凭证表上虽然另存着
     * {@code verified_by}，但那是「这条凭证的最终结论是谁下的」；而流水要回答的是
     * 「这一笔动作是谁做的」—— 两者在重复复核、并发驳回这些情形下并不是一回事，
     * 而这本账既然要能独立看懂，就不该逼着读的人再去别的表里拼。
     */
    private Long operatorId;

    /**
     * 金额（元），可为空。
     *
     * <p>取消未付款单时记的是<b>它原本的应付额</b> —— 钱一分没动，但这个数
     * 说明了「关掉的是一笔多大的单」，运营复盘时看的就是它。
     * 允许为空是给将来可能出现的「金额不明」留的余地（如历史数据回填）。
     */
    private BigDecimal amount;

    /**
     * 交易流水号，可为空。
     *
     * <p>用户填的，或（2026-10-10 起）用户在群内传图没有填写环节时、
     * 由 OCR 识别到并替他填上的那一串。取不到就为空 ——
     * 对账时会因此报一条「没填流水号」，那是诚实的。
     */
    private String paymentNo;

    /**
     * 来源渠道，取值见 {@link com.kaede.uspace.common.trade.TradeSource}
     * （{@code WEB} / {@code QQ} / {@code ADMIN} / {@code SYSTEM}）。
     */
    private String source;

    /** 备注。驳回原因、复核人这类「这一行额外要说明的话」写在这里 */
    private String remark;

    /**
     * 发生时刻。插入时由 {@code AuditMetaObjectHandler} 自动填充，
     * 业务代码不要赋值 —— 与 {@code AccessRecord#createdAt} 同一套做法。
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
