package com.kaede.uspace.lock.dto;

import lombok.Data;

/**
 * 门锁接口的调用结果。
 *
 * <p>结构对齐通通锁云 API 的统一返回体 {@code {"errcode": 0, "errmsg": "success"}}，
 * 便于真实实现直接映射。
 *
 * <p>使用建议：业务代码判断成功与否请用 {@link #success}，
 * {@link #errcode} 与 {@link #errmsg} 用于日志记录与问题定位。
 */
@Data
public class PasscodeResult {

    /** 是否成功。由 errcode 是否为 0 推导得出 */
    private boolean success;

    /** 通通锁错误码。0 表示成功；非 0 参见官方错误码表，如 -4043 表示该锁不支持此操作 */
    private Integer errcode;

    /** 错误描述，用于日志与前端提示 */
    private String errmsg;

    /** 最终生效的密码。仅在下发成功的返回中有值 */
    private String keyboardPwd;

    /**
     * 构造成功结果。
     *
     * @param keyboardPwd 生效的密码
     * @return 成功结果对象
     */
    public static PasscodeResult ok(String keyboardPwd) {
        PasscodeResult result = new PasscodeResult();
        result.setSuccess(true);
        result.setErrcode(0);
        result.setErrmsg("success");
        result.setKeyboardPwd(keyboardPwd);
        return result;
    }

    /**
     * 构造失败结果。
     *
     * @param errcode 错误码
     * @param errmsg  错误描述
     * @return 失败结果对象
     */
    public static PasscodeResult fail(Integer errcode, String errmsg) {
        PasscodeResult result = new PasscodeResult();
        result.setSuccess(false);
        result.setErrcode(errcode);
        result.setErrmsg(errmsg);
        return result;
    }
}
