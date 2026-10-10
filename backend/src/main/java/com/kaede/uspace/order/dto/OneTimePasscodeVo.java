package com.kaede.uspace.order.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次性密码的发放结果（模块 11 的群指令 {@code fw开门} 用）。
 *
 * <p>⚠️ <b>它只在 {@code qqbot} 包内部消费，绝不能出现在任何 Controller 的返回体里</b> ——
 * 这是本系统里唯一一处「密码可以离开服务端」的地方，而它离开的方式只有一条：
 * 被组装进群消息的文案。任何把它塞进接口响应的改动都会让密码不再只属于本人。
 *
 * <p>与 {@link com.kaede.uspace.order.dto.OrderOpenVo} 里那个 {@code passcode} 的差别：
 * 那个是<b>可反复查看的限时密码</b>（网页端「查看密码」会给出来）；
 * 本类是<b>用一次即焚的一次性密码</b>，给出去之后服务端就不再管它，
 * 只留一串在订单上供结算时撤销。
 */
@Data
public class OneTimePasscodeVo {

    /** 密码内容，由锁云生成 */
    private String passcode;

    /** 生效时刻 */
    private LocalDateTime startTime;

    /** 失效时刻（生动起 6 小时，通通锁单次密码的固有规则） */
    private LocalDateTime endTime;

    /**
     * 构造发放结果。
     *
     * @param passcode  密码内容
     * @param startTime 生效时刻
     * @param endTime   失效时刻
     * @return 结果对象
     */
    public static OneTimePasscodeVo of(String passcode, LocalDateTime startTime, LocalDateTime endTime) {
        OneTimePasscodeVo vo = new OneTimePasscodeVo();
        vo.setPasscode(passcode);
        vo.setStartTime(startTime);
        vo.setEndTime(endTime);
        return vo;
    }
}
