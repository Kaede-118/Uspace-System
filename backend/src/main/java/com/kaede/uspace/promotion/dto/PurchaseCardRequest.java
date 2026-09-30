package com.kaede.uspace.promotion.dto;

import com.kaede.uspace.promotion.MonthlyCardType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 发起购买月卡的请求。
 *
 * <p>只收一个卡种，价格由后端按配置算 —— 前端传价格来的话，
 * 改一改请求体就能用 1 分钱买下 600 元的卡。价格永远是服务端说了算。
 */
@Data
public class PurchaseCardRequest {

    /** 卡种。取值见 {@link MonthlyCardType}，非法取值由全局异常处理器转成 400 */
    @NotNull(message = "请选择月卡类型")
    private MonthlyCardType cardType;
}
