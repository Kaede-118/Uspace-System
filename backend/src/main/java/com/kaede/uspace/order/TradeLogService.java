package com.kaede.uspace.order;

import com.kaede.uspace.common.trade.TradeSource;
import com.kaede.uspace.order.entity.TradeLog;
import com.kaede.uspace.order.mapper.TradeLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 交易流水的落账（模块 8）。
 *
 * <p>把四类收款与四类「取消未付款单」的动作记进 {@code biz_trade_log} 一张表，
 * 让「这一天发生过什么」有一处可查。事件的取值见 {@link TradeEventType}。
 *
 * <h3>规矩一：写流水与业务动作同事务，绝不吞异常</h3>
 *
 * <p>本类<b>不</b>像公告（{@code NoticeService#publishAuto}）那样把异常吞掉 ——
 * 公告是次要信息，而这是一本账。插入失败时让整个业务事务回滚是刻意的：
 * <b>「钱收了、流水没有」比「这次操作失败、请重试」难查得多</b>，
 * 而失败的原因（表不存在、库连不上）本来就该当场暴露。
 *
 * <p>由此推出对调用方的要求：<b>调用点必须在事务里</b>。
 * 三个取消路径（商品 / 月卡 / 包场）各自带 {@code @Transactional}，
 * 落账那几个点（凭证提交、复核、线上回调）也都在事务里。
 * 谁把本类挪到事务外调用，上面那条保证就没了。
 *
 * <h3>规矩二：取消那三类由事件送进来</h3>
 *
 * <p>商品、月卡、包场三个模块<b>不 import 本类</b>（它们是单向依赖的另一侧），
 * 而是各自发布一个「未付款单被取消」的事件，由 {@link TradeLogListener}
 * 监听后落到本类。这是本项目处理跨模块动作的既有模式
 * （与 qqbot 的播报、驳回提醒同一套，见 {@code QqBroadcastListener} 的类注释）。
 *
 * @see TradeLogListener 事件 → 流水的翻译处
 */
@Slf4j
@Service
public class TradeLogService {

    private final TradeLogMapper tradeLogMapper;

    /**
     * 构造器注入。
     *
     * @param tradeLogMapper 流水数据访问
     */
    public TradeLogService(TradeLogMapper tradeLogMapper) {
        this.tradeLogMapper = tradeLogMapper;
    }

    /**
     * 记一条「提交付款凭证」。
     *
     * <p>提交是收款的起点（对订单与商品来说还是落账那一刻），值得单独留一行：
     * 「凭证提交过、后来被驳回」与「压根没提交过」在事后是两件完全不同的事。
     *
     * @param targetType 收款目标类型
     * @param targetNo   对外单号
     * @param userId     提交人
     * @param amount     应付金额（元）
     * @param paymentNo  交易流水号；用户填的，或由识别结果替他填上的；可为空
     * @param source     来源渠道
     */
    public void recordProofSubmitted(PaymentTargetType targetType, String targetNo,
                                     Long userId, BigDecimal amount, String paymentNo,
                                     TradeSource source) {
        // 操作人恒为空：这一行就是用户本人做的（提交人就是 user_id），不是谁代劳
        write(TradeEventType.PROOF_SUBMITTED, targetType, targetNo, userId, amount,
                paymentNo, source, null, null);
    }

    /**
     * 记一条「收款到账」。
     *
     * <p>三个调用点，对应三种到账方式：提交即落账（订单 / 商品）、
     * 管理员复核通过（包场 / 月卡）、线上通道回调（来源传 {@code SYSTEM}）。
     * 三者最终都落在一处 —— {@code PaymentService#settleByProof}
     * 与 {@code applyNotify}，所以「到账」这件事只有一套写法。
     *
     * @param targetType 收款目标类型
     * @param targetNo   对外单号
     * @param userId     付款人
     * @param amount     实收金额（元）
     * @param paymentNo  交易流水号，可为空
     * @param source     来源渠道
     * @param operatorId 操作人（审核人）：管理员复核通过时是他的 ID；
     *                   「提交即落账」与线上回调都传 null（没有人代劳），可空
     * @param remark     备注（如「管理员复核通过」），可为空
     */
    public void recordPayReceived(PaymentTargetType targetType, String targetNo,
                                  Long userId, BigDecimal amount, String paymentNo,
                                  TradeSource source, Long operatorId, String remark) {
        write(TradeEventType.PAY_RECEIVED, targetType, targetNo, userId, amount,
                paymentNo, source, operatorId, remark);
    }

    /**
     * 记一条「凭证驳回」。
     *
     * <p>来源恒为 {@code ADMIN}：驳回是管理员在后台点的，没有别的入口。
     *
     * @param targetType 收款目标类型
     * @param targetNo   对外单号
     * @param userId     提交人
     * @param amount     凭证上的金额（元）
     * @param paymentNo  凭证上的交易流水号，可为空
     * @param operatorId 驳回的管理员 ID —— 这一列存在的意义就在这里：
     *                   事后要查「这笔是谁判的不认」
     * @param reason     驳回原因，会展示给用户
     */
    public void recordProofRejected(PaymentTargetType targetType, String targetNo,
                                    Long userId, BigDecimal amount, String paymentNo,
                                    Long operatorId, String reason) {
        write(TradeEventType.PROOF_REJECTED, targetType, targetNo, userId, amount,
                paymentNo, TradeSource.ADMIN, operatorId, reason);
    }

    /**
     * 记一条「取消未付款单」。
     *
     * <p><b>钱一分没动，但这是一笔交易事实</b>：它回答的是「那笔挂着的单后来怎么了」——
     * 没有这一行，一笔待支付单从列表上消失之后就再也说不清是被取消了、
     * 还是被别的东西改没了。
     *
     * @param targetType 收款目标类型
     * @param targetNo   对外单号
     * @param userId     单子的主人
     * @param amount     单子原本的应付额（元），可为空
     * @param source     来源渠道：网页端 / 群内 / 管理后台
     * @param operatorId 操作人：管理员在后台取消时是他的 ID；
     *                   用户自己取消（网页端 / 群内）传 null，可空
     */
    public void recordCancelUnpaid(PaymentTargetType targetType, String targetNo,
                                   Long userId, BigDecimal amount, TradeSource source,
                                   Long operatorId) {
        write(TradeEventType.CANCEL_UNPAID, targetType, targetNo, userId, amount,
                null, source, operatorId, null);
    }

    /**
     * 记一条「账单对账确认」（2026-10-10 加）。
     *
     * <p>管理员上传收款账单跑完对账、某笔凭证在账单里被认领时写一条。
     * <b>它与 {@link #recordPayReceived} 不是一回事</b>：
     * 那一条说的是「系统按自己的记录认了这笔钱」（提交即落账、复核通过、回调），
     * 这一条说的是「账单里也真有这一笔」—— 后者才排除得了
     * 「有人传了张似是而非的截图、机器读到了一串像样的数字」这种情形。
     *
     * <p>⚠️ 因此<b>小额收款的自动通过不写这一条</b>，见 {@link TradeEventType#RECONCILED}。
     *
     * @param targetType 收款类型名，取自凭证上的值（是库里的字符串，不是枚举）
     * @param targetNo   对外单号
     * @param userId     付款人
     * @param amount     凭证金额（元）
     * @param paymentNo  交易流水号，可为空
     * @param operatorId 跑这次对账的管理员 ID
     * @param remark     备注（如「对账批次 #12」）
     */
    public void recordReconciled(String targetType, String targetNo, Long userId,
                                 BigDecimal amount, String paymentNo,
                                 Long operatorId, String remark) {
        TradeLog entry = new TradeLog();
        entry.setEventType(TradeEventType.RECONCILED.name());
        entry.setTargetType(targetType);
        entry.setTargetNo(targetNo);
        entry.setUserId(userId);
        entry.setOperatorId(operatorId);
        entry.setAmount(amount);
        entry.setPaymentNo(paymentNo);
        // 对账是管理员点的、机器比的 —— 两半都算「后台动作」
        entry.setSource(TradeSource.ADMIN.name());
        entry.setRemark(remark);
        tradeLogMapper.insert(entry);

        log.info("[流水] {} {} 单号={} 用户={} 金额={} 流水号={} 来源={} 操作人={} 备注={}",
                TradeEventType.RECONCILED.getLabel(), targetType, targetNo, userId, amount,
                paymentNo, TradeSource.ADMIN.getLabel(), operatorId, remark);
    }

    /**
     * 统一的落库入口。
     *
     * <p>四个 {@code record*} 方法都走这里，只为让「插入 + 打日志」这两行的写法
     * 只有一处 —— 四份复制粘贴的失败方式是「某一条忘了打日志」，
     * 而那条恰恰是事后要查的。
     *
     * <p>⚠️ 这里<b>刻意不 try/catch</b>：让它抛，让调用方的事务回滚。
     * 理由见类注释「规矩一」。
     *
     * @param eventType  事件类型
     * @param targetType 收款目标类型
     * @param targetNo   对外单号
     * @param userId     相关用户 ID
     * @param amount     金额（元），可为空
     * @param paymentNo  交易流水号，可为空
     * @param source     来源渠道
     * @param operatorId 操作人（审核人），可为空 —— 为空表示不是管理员代劳的
     * @param remark     备注，可为空
     */
    private void write(TradeEventType eventType, PaymentTargetType targetType, String targetNo,
                       Long userId, BigDecimal amount, String paymentNo,
                       TradeSource source, Long operatorId, String remark) {
        TradeLog entry = new TradeLog();
        entry.setEventType(eventType.name());
        entry.setTargetType(targetType.name());
        entry.setTargetNo(targetNo);
        entry.setUserId(userId);
        entry.setOperatorId(operatorId);
        entry.setAmount(amount);
        entry.setPaymentNo(paymentNo);
        entry.setSource(source.name());
        entry.setRemark(remark);
        tradeLogMapper.insert(entry);

        log.info("[流水] {} {} 单号={} 用户={} 金额={} 流水号={} 来源={} 操作人={}{}",
                eventType.getLabel(), targetType.name(), targetNo, userId, amount,
                paymentNo, source.getLabel(), operatorId,
                remark == null ? "" : " 备注=" + remark);
    }
}
