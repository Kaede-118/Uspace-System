package com.kaede.uspace.billing.dto;

import lombok.Data;

/**
 * 「下一次账单变化」的预告。
 *
 * <p>回答用户看着计时器时最关心的那个问题：<b>现在停还是再玩一会儿，账单什么时候会变、变成多少。</b>
 * 首页的进行中卡片与结账预览页共用同一份数据。
 *
 * <p><b>为什么由后端算而不让前端拿账单分段自己推</b>：档位边界是
 * {@code units × 30 + 5 + 1} 分钟，其中的 30（档位单位）与 5（宽限）都是配置项。
 * 前端算等于把计费规则复制一份 —— 改配置时前端静默算错，且不报任何错。
 * 这与「日夜场边界只有计费侧一处定义」是同一条道理。
 *
 * <p><b>用剩余秒数而不是绝对时刻</b>：绝对时刻会带上客户端与服务端的时钟偏差，
 * 用户手机时间不准时倒计时会算出荒谬的值。秒数是后端算好的，前端只管每秒减一。
 *
 * @see com.kaede.uspace.billing.BillingService#nextChange
 */
@Data
public class NextChange {

    /**
     * 距下一次账单变化的剩余秒数；{@code null} 表示<b>当前计费段内不会再变化</b>。
     *
     * <p>为 null 的两种情形（均已封顶、当前段被月卡覆盖）都会同时给出说明性的
     * {@link #text}，前端此时只显示文字、不要显示倒计时 ——
     * 给一个「还有 0 秒」或空白反而像是坏了。
     */
    private Long inSeconds;

    /**
     * 变化性质的说明，<b>不含时间</b>。
     *
     * <p>时间是前端的事（它可以每秒重绘），后端只负责说清楚「将要发生什么」。
     * 前端拼成「还有 12:30 进入下一档 ¥20.00」这样一句。
     */
    private String text;

    /**
     * 构造一个带倒计时的预告。
     *
     * @param inSeconds 剩余秒数
     * @param text      变化说明
     * @return 预告
     */
    public static NextChange at(long inSeconds, String text) {
        NextChange change = new NextChange();
        change.setInSeconds(inSeconds);
        change.setText(text);
        return change;
    }

    /**
     * 构造一个「当前段内不会再变化」的说明。
     *
     * <p>倒计时留空：它要表达的是「不用等了」，而任何秒数都会读成「再等这么久」。
     *
     * @param text 不变的原因，如「当前已到封顶价」
     * @return 预告
     */
    public static NextChange none(String text) {
        NextChange change = new NextChange();
        change.setText(text);
        return change;
    }
}
