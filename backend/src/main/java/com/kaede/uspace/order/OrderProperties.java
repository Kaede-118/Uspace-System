package com.kaede.uspace.order;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 订单模块配置，对应配置文件中的 {@code uspace.order.*}。
 *
 * @see com.kaede.uspace.lock.LockProperties 门锁模块配置（密码下发的实现选择在这里）
 */
@Data
@Component
@ConfigurationProperties(prefix = "uspace.order")
public class OrderProperties {

    /**
     * 下发的限时门锁密码的有效期。默认 12 小时。
     *
     * <p><b>这个值只是兜底，不是正常路径的时长</b>：密码在用户点击「开门」时下发，
     * 他会立刻走到门口输进去。有效期真正要覆盖的是「玩到一半出门买水、吃饭再回来」——
     * 取 12 小时是因为它恰好覆盖一个完整夜场（22:00–10:00），也覆盖白天从下午玩到深夜。
     *
     * <p><b>过期了怎么办</b>：用户点「查看密码」时会自动续期，而且续期
     * <b>不换密码</b>（调通通锁的 {@code changePasscode} 改有效期窗口，密码数字不变）——
     * 用户截图转发出去的那串数字始终有效，锁上也不会越积越多密码。
     *
     * <p><b>演示用</b>：把它改成 {@code 20s}，一分钟内就能稳定复现「过期 → 续期」
     * 与「续期失败 → 重新下发」两条分支，不必真等 12 小时。
     * 注意必须带单位后缀（{@code 12h} / {@code 30m}），不带后缀时 Spring 按毫秒解析。
     */
    private Duration passcodeValidDuration = Duration.ofHours(12);

    /**
     * 包场开始前多久停止接待新顾客。默认 15 分钟。
     *
     * <p><b>为什么需要这个提前量</b>：一局游戏打不完就要被清场，放进来等于让顾客
     * 花了钱却玩不尽兴。提前量取 15 分钟，是因为它刚好覆盖「进来打一局」的最短耗时 ——
     * 更晚进来的顾客必然会中途被包场打断，不如一开始就挡住。
     *
     * <p>这段时间对店内的散客没有任何影响（准入只挡新订单），
     * 他们照常玩到包场开始，届时由 {@link OrderService#settleNonParticipants}
     * 按包场开始时刻结算清场。
     */
    private Duration bookingLeadDuration = Duration.ofMinutes(15);

    /**
     * 包场参与者可以提前多久入场。默认 5 分钟。
     *
     * <p>参与者（包场人与被邀请者）比普通顾客多一个提前量：进店放东西、
     * 做开场准备。但也不能与普通顾客一样早 —— 包场时段之前店里仍按普通营业安排。
     *
     * <p><b>必须满足 {@code participantLeadDuration <= bookingLeadDuration}</b>，
     * 否则会形成一个「参与者能进、非参与者也能进」的窗口，提前量的意义就没了。
     * 两者的关系由 {@code OrderServiceTests} 的用例钉住。
     */
    private Duration participantLeadDuration = Duration.ofMinutes(5);
}
