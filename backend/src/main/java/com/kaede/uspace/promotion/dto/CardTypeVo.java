package com.kaede.uspace.promotion.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 在售月卡卡种。
 *
 * <p>供购买页展示「有哪些卡、各多少钱、各覆盖什么时段」。
 * 价格来自配置，因此调价之后前端不必改动，重新拉一次即可。
 */
@Data
public class CardTypeVo {

    /** 卡种名，下单时原样回传 */
    private String cardType;

    /** 卡种中文名，如「全天月卡」 */
    private String label;

    /** 价格（元） */
    private BigDecimal price;

    /** 覆盖范围的中文说明，如「全天」「仅夜场」 */
    private String coverageLabel;

    /**
     * 覆盖时段的具体说明，如「不限时段」「22:00 – 次日 10:00」。
     *
     * <p>时刻取自计费配置（{@code uspace.billing.day-start/day-end}），
     * 与计费本身共用同一套边界 —— 不在这里另写一份，否则改了营业时段
     * 就会出现「月卡说夜间是 22:00 起、账单却按另一个时刻切段」。
     */
    private String periodText;

    /** 有效期天数（含首尾） */
    private int validDays;
}
