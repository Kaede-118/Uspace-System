package com.kaede.uspace.user;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * QQ 验证码的配置（模块 1）。
 *
 * <p>⚠️ <b>{@link Duration} 类型的值在 properties 里必须带单位</b>：
 * 写 {@code 10} 会被当成 <b>10 毫秒</b>，验证码签发出来就已经过期，
 * 而且不会有任何报错 —— 用户只会看到「验证已失效，请重新获取」，
 * 反复重试都一样。项目里 {@code uspace.order.passcode-valid-duration}
 * 已经为这条踩过一次坑了。
 */
@Data
@Component
@ConfigurationProperties(prefix = "uspace.user.qq-verify")
public class QqVerifyProperties {

    /**
     * 验证码的有效期：从签发，到「用户把它发到群里、机器人确认」之间。
     *
     * <p>给得比较宽（10 分钟）：这段时间里用户要做不少事 —— 切到 QQ、找到群、
     * 粘贴、发送，可能还要先翻一下聊天记录。太短的话他会反复回来重新获取，
     * 而每重新获取一次，之前发到群里的那条就成了死信。
     */
    private Duration codeTtl = Duration.ofMinutes(10);

    /**
     * 确认通过之后的有效期，<b>从确认那一刻重新算</b>。
     *
     * <p>不能沿用签发时刻：那样第 9 分钟才在群里确认成功的人只剩 1 分钟注册，
     * 密码还没填完就被拒了 —— 而他明明已经完成了验证。
     */
    private Duration verifiedTtl = Duration.ofMinutes(10);

    /**
     * 同一个验证码最多能被错猜几次，超过就把这条记录整个删掉。
     *
     * <p>5 次是留够手滑的余量（从群里复制时偶尔会带上前后的字）。
     */
    private int maxAttempts = 5;

    /**
     * 同时未过期的待验证请求数上限。
     *
     * <p>签发接口匿名可用，这个上限是唯一的兜底：不设的话，
     * 一个脚本循环调用就能把内存撑满。
     */
    private int maxPending = 10000;
}
