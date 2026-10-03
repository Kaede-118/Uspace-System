package com.kaede.uspace.lock.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 获取一次性密码的调用结果。
 *
 * <p>结构对齐通通锁 {@code POST /v3/keyboardPwd/get} 的返回体
 * （{@code {"keyboardPwd": "123456", "keyboardPwdId": "10236"}}），
 * 并补上由「生效时刻 + 6 小时」推得的失效时刻 —— 真实接口不返回起止时间，
 * 本系统的实现按同一规则补出来，上层才不必自己记这个常量。
 *
 * <p>⚠️ <b>刻意不复用 {@link PasscodeResult}，也不给它加字段</b>：
 * 那个类是「增删改限时密码」三条接口的结果，而一次性密码多出来的
 * {@code keyboardPwdId} 与窗口语义是另一套东西 —— 混在一起会让
 * {@code addPasscode} 的调用方看到一个<b>永远为 null 的 id</b>，
 * 而那种「有时有值有时没有」的字段比没有字段更难排查。
 *
 * <p>使用建议：判断成功与否请用 {@link #success}，
 * {@link #errcode} 与 {@link #errmsg} 用于日志记录与问题定位。
 */
@Data
public class OneTimePasscodeResult {

    /** 是否成功。由 errcode 是否为 0 推导得出 */
    private boolean success;

    /** 通通锁错误码。0 表示成功；非 0 参见官方错误码表 */
    private Integer errcode;

    /** 错误描述，用于日志与前端提示 */
    private String errmsg;

    /** 密码内容。由锁云生成，仅在下发成功的返回中有值 */
    private String keyboardPwd;

    /** 通通锁侧的密码 ID，删除该密码时按它定位 */
    private Long keyboardPwdId;

    /** 生效时刻 */
    private LocalDateTime startTime;

    /** 失效时刻（生效起 6 小时，通通锁单次密码的固有规则） */
    private LocalDateTime endTime;

    /**
     * 构造成功结果。
     *
     * @param keyboardPwd   密码内容
     * @param keyboardPwdId 锁云侧的密码 ID
     * @param startTime     生效时刻
     * @param endTime       失效时刻
     * @return 成功结果对象
     */
    public static OneTimePasscodeResult ok(String keyboardPwd, Long keyboardPwdId,
                                           LocalDateTime startTime, LocalDateTime endTime) {
        OneTimePasscodeResult result = new OneTimePasscodeResult();
        result.setSuccess(true);
        result.setErrcode(0);
        result.setErrmsg("success");
        result.setKeyboardPwd(keyboardPwd);
        result.setKeyboardPwdId(keyboardPwdId);
        result.setStartTime(startTime);
        result.setEndTime(endTime);
        return result;
    }

    /**
     * 构造失败结果。
     *
     * @param errcode 错误码
     * @param errmsg  错误描述
     * @return 失败结果对象
     */
    public static OneTimePasscodeResult fail(Integer errcode, String errmsg) {
        OneTimePasscodeResult result = new OneTimePasscodeResult();
        result.setSuccess(false);
        result.setErrcode(errcode);
        result.setErrmsg(errmsg);
        return result;
    }
}
