package com.kaede.uspace.lock.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 撤销（失效）已下发密码的请求。
 *
 * <p>对应通通锁 {@code POST /v3/keyboardPwd/delete}。密码本身通常带失效时间、
 * 到期自动失效，本接口用于<b>提前</b>让密码作废 —— 比如顾客提前离场、
 * 或密码不慎泄露给了不该给的人。
 *
 * <p>删除是幂等的：密码本就不存在时同样返回成功，以便安全地重复调用。
 *
 * <p><b>为什么撤销走 POST 而不是 {@code DELETE} 带请求体</b>：密码若放在查询参数里，
 * 会原样写进各级访问日志与浏览器历史 —— 那等于把开店门的凭据散播出去。
 * 放请求体里则不会进日志。
 */
@Data
public class RevokePasscodeRequest {

    /** 锁 ID，必填 */
    @NotNull(message = "锁 ID 不能为空")
    private Long lockId;

    /** 要撤销的密码，必填 */
    @NotBlank(message = "密码不能为空")
    private String keyboardPwd;
}
