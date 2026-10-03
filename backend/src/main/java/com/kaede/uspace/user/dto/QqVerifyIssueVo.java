package com.kaede.uspace.user.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 签发验证码的返回体。
 *
 * <p><b>这里同时给出两个东西，它们的职责完全不同</b>：
 * <ul>
 *   <li>{@link #code} —— <b>给用户</b>，复制了发到 QQ 群里。
 *       它<b>不是</b>凭证：群里所有人都看得见，但只有持有那个 QQ 的人能用掉它
 *       （机器人是按「发消息那个人的 QQ」查表的）</li>
 *   <li>{@link #challengeId} —— <b>给浏览器</b>，留在页面里。
 *       它<b>是</b>凭证：证明「我就是发起这次验证的那个标签页」，
 *       轮询状态与提交注册都要带上它</li>
 * </ul>
 *
 * <p>少了 challengeId 会出两个洞：轮询只能拿 QQ 去问（等于给所有人一个
 * 「查这个 QQ 注册没有」的探测器），以及群里任何一个看到验证码的人
 * 都能<b>抢先用那个 QQ 注册</b>。
 */
@Data
public class QqVerifyIssueVo {

    /** 本次验证的凭证。前端要留着，轮询与注册都带上 */
    private String challengeId;

    /** 6 位验证码。给用户复制，发到 QQ 群里 */
    private String code;

    /** 有效期截止时刻 */
    private LocalDateTime expiresAt;

    /**
     * 还剩多少秒。
     *
     * <p>由后端算好给前端做倒计时 —— 让前端拿客户端时间与 {@code expiresAt} 相减的话，
     * 机器时间偏几分钟就会显示成一个负数或一个巨大的数。
     */
    private long ttlSeconds;

    /**
     * 构造签发结果。
     *
     * @param challengeId 验证凭证
     * @param code        6 位验证码
     * @param expiresAt   有效期截止时刻
     * @param ttlSeconds  剩余秒数
     * @return 返回体
     */
    public static QqVerifyIssueVo of(String challengeId, String code,
                                     LocalDateTime expiresAt, long ttlSeconds) {
        QqVerifyIssueVo vo = new QqVerifyIssueVo();
        vo.setChallengeId(challengeId);
        vo.setCode(code);
        vo.setExpiresAt(expiresAt);
        vo.setTtlSeconds(ttlSeconds);
        return vo;
    }
}
