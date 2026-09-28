package com.kaede.uspace.lock.dto;

import lombok.Data;

/**
 * 密码下发的结果视图。
 *
 * <p>刻意只暴露密码本身，不带 {@link PasscodeResult} 里的 {@code errcode} / {@code errmsg} ——
 * 那是通通锁的内部错误码，对前端没有意义；失败的情形已经在服务端被翻译成
 * 本系统的 {@code BizResult} 错误码与中文说明，不会以「成功但带个错误码」的形态返回。
 *
 * <p>生效与失效时间不在这里重复给出：调用方就是下发者，请求里已经带了这两个值。
 */
@Data
public class PasscodeVo {

    /** 下发成功的限时密码，由管理员告知顾客或由下单流程直接展示给用户 */
    private String keyboardPwd;

    /**
     * 由门锁服务的返回构造视图。
     *
     * @param keyboardPwd 下发成功的密码
     * @return 结果视图
     */
    public static PasscodeVo of(String keyboardPwd) {
        PasscodeVo vo = new PasscodeVo();
        vo.setKeyboardPwd(keyboardPwd);
        return vo;
    }
}
