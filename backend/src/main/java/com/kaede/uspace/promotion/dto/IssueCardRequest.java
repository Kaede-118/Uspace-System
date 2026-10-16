package com.kaede.uspace.promotion.dto;

import com.kaede.uspace.promotion.MonthlyCardType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 后台给指定用户开卡的请求（面对面收款场景）。
 *
 * <p>与用户端购买（{@link PurchaseCardRequest}）的区别在于<b>它是管理员发的</b>：
 * 用户端只能按配置价买在售卡种，而这里能指定用户、能改实收金额
 *（当面交易可能打折、抹零），也能发STAFF 卡。
 *
 * @see com.kaede.uspace.promotion.MonthlyCardService#issueCard
 */
@Data
public class IssueCardRequest {

    /** 持卡人用户 ID */
    @NotNull(message = "请选择用户")
    private Long userId;

    /** 卡种。取值见 {@link MonthlyCardType}，非法取值由全局异常处理器转成 400 */
    @NotNull(message = "请选择卡种")
    private MonthlyCardType cardType;

    /**
     * 实收金额（元），<b>选填</b>：不传按配置价（当面交易大多照价收）。
     *
     * <p>填 0 表示不收钱（赠送、测试）—— 那笔不会记进累计消费。
     * STAFF 卡忽略本字段（它本来就无价）。
     *
     * <p>⚠️ 它只影响「记多少累计消费」，不影响卡本身 —— 卡的有效期按卡种算，
     * 与实收多少无关。Bean Validation 的 {@code @DecimalMin} 只作用于 Controller 层，
     * {@code MonthlyCardService#issueCard} 里对负数还会再挡一道。
     */
    @DecimalMin(value = "0", message = "实收金额不能为负数")
    private BigDecimal price;
}
