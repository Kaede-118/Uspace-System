package com.kaede.uspace.stats.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 收支概览（模块 10）。
 *
 * <p><b>收入取交易流水的「到账」事件</b>（{@code biz_trade_log} 里
 * {@code PAY_RECEIVED} 的金额合计）：四类收款（订单 / 商品 / 包场 / 月卡）
 * 共用同一条支付入口，每一笔到账都会在这里落一行，且它是 append-only 的 ——
 * 比「分别聚合四张业务表再相加」少一次口径漂移的机会。
 *
 * <p>⚠️ <b>只算到账</b>：提交凭证、驳回、取消未付款单这几类事件<b>没有钱发生</b>，
 * 不计入收入。前者是「他说付了」，后者是「这笔单关掉了」——
 * 把它们算成收入，账面上会凭空多出钱。
 *
 * <p>⚠️ <b>支出当前恒为 {@code null}，且 {@code expenseSupported} 为 false</b>。
 * 系统还没有支出记账（没有对应的表），所以这里<b>不返回 0</b> ——
 * 0 会被读成「本月没有支出」，而事实是「系统不知道」。
 * 前端据此显示「—」并标注「暂未做账」，而不是一个看着像真数据的 ¥0.00。
 */
@Data
public class FinanceSummaryVo {

    /** 本月收入（元） */
    private BigDecimal thisMonthIncome;

    /** 上月收入（元） */
    private BigDecimal lastMonthIncome;

    /** 累计收入（元）：开业至今全部到账 */
    private BigDecimal totalIncome;

    /** 本月支出（元）；未做支出记账时为 null */
    private BigDecimal thisMonthExpense;

    /** 上月支出（元）；未做支出记账时为 null */
    private BigDecimal lastMonthExpense;

    /** 累计支出（元）；未做支出记账时为 null */
    private BigDecimal totalExpense;

    /** 系统是否支持支出记账。当前恒为 false —— 见类注释 */
    private boolean expenseSupported;

    /** 本月标签，如 {@code 2026-10} */
    private String thisMonthLabel;

    /** 上月标签，如 {@code 2026-09} */
    private String lastMonthLabel;

    /** 统计时刻 */
    private LocalDateTime generatedAt;
}
