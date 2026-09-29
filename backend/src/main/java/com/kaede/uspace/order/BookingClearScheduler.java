package com.kaede.uspace.order;

import com.kaede.uspace.space.BookingService;
import com.kaede.uspace.space.entity.Booking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 包场清场调度器（模块 8）。
 *
 * <p><b>做什么</b>：每隔一段时间看一眼店里是不是有包场正在进行，若有就把仍在店内的
 * 非参与者订单结算掉 —— 包场是独占时段，散客必须在此之前离场。
 * 费用的计算与状态流转全在 {@link OrderService#settleNonParticipants} 里，
 * 本类只负责「什么时候叫它」。
 *
 * <p><b>为什么用定时扫描而不是「到点触发」</b>：包场是提前几天甚至几周排好的，
 * 而应用可能在此期间重启过。定时扫描只需要「当前时刻 + 一个查询」就能得到正确结论，
 * 不依赖任何内存中的计划表 —— 重启不会丢失，改期也不必重新登记。
 *
 * <p><b>重复执行是安全的</b>：结算过的订单不再是 {@code IN_USE}，下一轮扫描查不出来。
 * 所以这里不必记录「这场包场清过场没有」，也就不存在「记录与实际不一致」的可能。
 *
 * <p><b>开销</b>：每分钟一次索引查询。店里没有包场时就是一次
 * {@code biz_booking} 的按门店 + 时间范围查询；有包场但店内无人时多一次
 * {@code biz_order} 查询。都不触碰门锁云接口 —— 预算里那 30,000 次/月
 * 只在真正要撤销密码时才会花掉。
 *
 * @see OrderService#settleNonParticipants(Booking)
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "uspace.order.clear-scheduler.enabled",
        havingValue = "true", matchIfMissing = true)
public class BookingClearScheduler {

    /** 包场服务（模块 3）：用来查当前是否有场次正在进行 */
    private final BookingService bookingService;

    /** 订单服务（模块 8）：清场的实际执行者 */
    private final OrderService orderService;

    public BookingClearScheduler(BookingService bookingService, OrderService orderService) {
        this.bookingService = bookingService;
        this.orderService = orderService;
    }

    /**
     * 扫描一轮：有包场在进行就清场。
     *
     * <p>用 {@code fixedDelay} 而不是 {@code fixedRate}：前者是「上一轮结束后再等 N 秒」，
     * 后者是「每 N 秒起一轮」。清场要调门锁云撤销密码，单轮耗时可能超过间隔，
     * 用 fixedRate 会让任务排队堆积 —— 而堆积起来的清场毫无意义，
     * 它们做的是同一件事。
     *
     * <p><b>异常不会中断调度</b>：方法内自行 catch 并记日志。Spring 对
     * {@code fixedDelay} 任务抛出的异常也是记日志后继续下一轮，但自行 catch 能
     * 把「哪场包场、哪一轮失败」写清楚，排查时不必去翻框架的日志格式。
     * 失败的那批订单仍是 {@code IN_USE}，下一轮会重新尝试。
     *
     * @see OrderService#settleNonParticipants(Booking)
     */
    @Scheduled(fixedDelayString = "${uspace.order.clear-scheduler.interval-millis:60000}")
    public void clearNonParticipants() {
        // 截断到秒与数据库列精度对齐：库列是 DATETIME，亚秒会被 MySQL 四舍五入，
        // 不截断会让内存里的时刻与查出来的值差最多 1 秒（模块 6 起沿用的习惯）
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

        // findActiveBookingAt 只认「此刻正在包场中」的场次，不含开始前的准入窗口 ——
        // 清场发生在包场开始之后，用这个正好
        Booking booking = bookingService.findActiveBookingAt(now);
        if (booking == null) {
            return;
        }

        try {
            int settled = orderService.settleNonParticipants(booking);
            if (settled > 0) {
                log.info("[清场] 包场 {} 进行中，已结算 {} 笔在店的非参与者订单",
                        booking.getBookingNo(), settled);
            }
        } catch (RuntimeException e) {
            log.error("[清场] 包场 {} 清场失败，相关订单仍为使用中，下一轮将重试",
                    booking.getBookingNo(), e);
        }
    }
}
