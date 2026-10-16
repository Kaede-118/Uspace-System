package com.kaede.uspace.stats;

import com.kaede.uspace.order.mapper.TradeLogMapper;
import com.kaede.uspace.stats.dto.FinanceSummaryVo;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 收支概览（模块 10）。
 *
 * <p>本类是「数据统计」模块的第一块内容 —— 在此之前模块 10 是个空包，
 * 统计一律手工查库（见 {@code CLAUDE.md} 的模块状态表）。
 *
 * <p><b>口径</b>：收入 = {@code biz_trade_log} 里 {@code PAY_RECEIVED} 的金额合计，
 * 按事件发生时刻（{@code created_at}）归入自然月。与计费那套「订单按离场月归集」
 * 不同 —— 这里统计的是<b>钱什么时候到的</b>，不是服务什么时候消费的，
 * 两者本来就不是一件事（顾客 8/31 进店、9/1 离场、9/2 付款，这笔钱 9 月才到手）。
 *
 * <p>⚠️ <b>「本月」按 JVM 时区算</b>（{@code LocalDate.now()}），与计费同一套。
 * 容器时区若没配成 Asia/Shanghai，月末月初的归属会整体偏一小时到八小时 ——
 * 这类错不会报任何错，只会让两个月的数对不上。部署时务必核对容器时区
 * （见 {@code docs/Docker化与云部署草案.md} 的「时区排第一」）。
 *
 * <p>⚠️ <b>支出恒为 null</b>：系统没有支出记账，返回 0 会被读成
 * 「本月没有支出」。详见 {@link FinanceSummaryVo} 的类注释。
 */
@Service
public class FinanceSummaryService {

    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 累计的时间下界：早于开业即可，不写 LocalDateTime.MIN 是为了可读性 */
    private static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);

    private final TradeLogMapper tradeLogMapper;

    public FinanceSummaryService(TradeLogMapper tradeLogMapper) {
        this.tradeLogMapper = tradeLogMapper;
    }

    /**
     * 汇总收入。
     *
     * @return 本月 / 上月 / 累计收入，支出位留空
     */
    public FinanceSummaryVo summary() {
        LocalDate today = LocalDate.now();
        LocalDate firstOfThisMonth = today.withDayOfMonth(1);
        LocalDateTime thisFrom = firstOfThisMonth.atStartOfDay();
        LocalDateTime nextFrom = firstOfThisMonth.plusMonths(1).atStartOfDay();
        LocalDateTime lastFrom = firstOfThisMonth.minusMonths(1).atStartOfDay();
        LocalDateTime now = LocalDateTime.now();

        FinanceSummaryVo vo = new FinanceSummaryVo();
        vo.setThisMonthIncome(tradeLogMapper.sumPaidIncome(thisFrom, nextFrom));
        vo.setLastMonthIncome(tradeLogMapper.sumPaidIncome(lastFrom, thisFrom));
        vo.setTotalIncome(tradeLogMapper.sumPaidIncome(EPOCH, now.plusMinutes(1)));
        vo.setThisMonthLabel(firstOfThisMonth.format(MONTH_LABEL));
        vo.setLastMonthLabel(firstOfThisMonth.minusMonths(1).format(MONTH_LABEL));
        vo.setExpenseSupported(false);
        vo.setGeneratedAt(now);
        return vo;
    }
}
