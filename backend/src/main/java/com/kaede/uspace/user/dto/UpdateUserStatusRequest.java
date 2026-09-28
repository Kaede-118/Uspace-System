package com.kaede.uspace.user.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 管理员启用 / 禁用用户的请求。
 *
 * <p>用 0 / 1 而非布尔值，是为了与 {@code sys_user.status} 列的类型直接对齐 ——
 * 布尔值在数据库里存成 tinyint 后，读出来是 0 与 1，
 * 中间做过一次类型翻译只会多一处可能出错的地方。
 *
 * <p><b>禁用即踢下线</b>：禁用操作会把该用户的 token 版本号 +1，
 * 于是他手上所有还没过期的凭证立即失效，不必等它们自然到期。
 * 这是「用户离场未付款就拉黑」这类场景能立刻生效的前提。
 */
@Data
public class UpdateUserStatusRequest {

    /** 目标状态：1=启用（正常），0=禁用 */
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态只能是 0（禁用）或 1（启用）")
    @Max(value = 1, message = "状态只能是 0（禁用）或 1（启用）")
    private Integer status;
}
