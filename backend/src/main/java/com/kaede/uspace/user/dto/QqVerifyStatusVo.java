package com.kaede.uspace.user.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 查询 QQ 验证状态的返回体（注册页轮询用）。
 *
 * <p><b>刻意不返回 QQ 号、也不返回验证码</b>：前端自己知道 QQ（它填的），
 * 验证码在签发那次响应里就给过了（存在 sessionStorage，刷新能恢复）。
 * 轮询接口只回答一个问题：群里确认了没有。
 */
@Data
public class QqVerifyStatusVo {

    /**
     * 是否已通过群内确认。
     *
     * <p>⚠️ <b>刻意用布尔，而不是 {@code status: "PENDING" | "VERIFIED"} 这样的字符串</b>：
     * 前端写 {@code resp.data.status === 'VERIFED'} 时打错一个字母就会永远不相等，
     * 页面永远停在「等待群内确认…」，而控制台里一行报错都没有。布尔没有这个失败模式。
     */
    private boolean verified;

    /** 有效期截止时刻（确认通过后会被顺延） */
    private LocalDateTime expiresAt;

    /**
     * 构造状态视图。
     *
     * @param verified  是否已确认
     * @param expiresAt 有效期截止时刻
     * @return 状态视图
     */
    public static QqVerifyStatusVo of(boolean verified, LocalDateTime expiresAt) {
        QqVerifyStatusVo vo = new QqVerifyStatusVo();
        vo.setVerified(verified);
        vo.setExpiresAt(expiresAt);
        return vo;
    }
}
