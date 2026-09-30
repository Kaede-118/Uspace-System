package com.kaede.uspace.promotion.dto;

import com.kaede.uspace.promotion.MonthlyCardStatus;
import com.kaede.uspace.promotion.MonthlyCardType;
import com.kaede.uspace.promotion.entity.MonthlyCard;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 一张月卡（卡包与后台列表共用）。
 */
@Data
public class MonthlyCardVo {

    /** 月卡 ID */
    private Long id;

    /** 卡号，对外展示 */
    private String cardNo;

    /** 卡种名 */
    private String cardType;

    /** 卡种中文名 */
    private String cardTypeLabel;

    /** 购买价格（元） */
    private BigDecimal price;

    /** 状态名 */
    private String status;

    /** 状态中文名 */
    private String statusLabel;

    /** 生效日期 */
    private LocalDate startDate;

    /** 失效日期，含当日 */
    private LocalDate endDate;

    /** 支付时刻 */
    private LocalDateTime paidAt;

    /** 支付通道 */
    private String paymentMethod;

    /** 覆盖范围的中文说明，如「仅夜场」 */
    private String coverageLabel;

    /**
     * 剩余天数（含今天）。
     *
     * <p><b>只有此刻真正生效的卡才有值</b>，其余（已过期、已退款、还没到生效日）
     * 一律为 null —— 前端据此决定要不要显示「还剩 N 天」，
     * 而不是自己拿日期去算（那样就得在客户端再实现一遍「含首尾」的口径）。
     */
    private Long remainingDays;

    /**
     * 把实体转成视图。
     *
     * @param card  月卡实体
     * @param today 今天的日期，用于算剩余天数
     * @return 月卡视图
     */
    public static MonthlyCardVo from(MonthlyCard card, LocalDate today) {
        MonthlyCardVo vo = new MonthlyCardVo();
        vo.setId(card.getId());
        vo.setCardNo(card.getCardNo());
        vo.setCardType(card.getCardType());
        vo.setCardTypeLabel(MonthlyCardType.labelOf(card.getCardType()));
        vo.setPrice(card.getPrice());
        vo.setStatus(card.getStatus());
        vo.setStatusLabel(MonthlyCardStatus.labelOf(card.getStatus()));
        vo.setStartDate(card.getStartDate());
        vo.setEndDate(card.getEndDate());
        vo.setPaidAt(card.getPaidAt());
        vo.setPaymentMethod(card.getPaymentMethod());
        vo.setCoverageLabel(coverageLabelOf(card.getCardType()));
        vo.setRemainingDays(remainingDaysOf(card, today));
        return vo;
    }

    /**
     * 取卡种对应的覆盖范围说明。
     *
     * @param cardType 卡种名
     * @return 覆盖范围说明；卡种名认不出时返回 null
     */
    private static String coverageLabelOf(String cardType) {
        return MonthlyCardType.isValid(cardType)
                ? MonthlyCardType.valueOf(cardType).getCoverage().getLabel()
                : null;
    }

    /**
     * 算剩余天数（含今天）。
     *
     * <p>判定口径与免单判定保持一致：<b>状态为生效中，且今天落在生效与失效日期之间
     * （两端都含）</b>。少判状态的话，一张已退款的卡也会显示「还剩 12 天」。
     *
     * @param card  月卡实体
     * @param today 今天的日期
     * @return 剩余天数；当前不生效时返回 null
     */
    private static Long remainingDaysOf(MonthlyCard card, LocalDate today) {
        if (!MonthlyCardStatus.ACTIVE.name().equals(card.getStatus())
                || card.getStartDate() == null || card.getEndDate() == null
                || card.getStartDate().isAfter(today) || card.getEndDate().isBefore(today)) {
            return null;
        }
        // 含首尾：最后一天当天显示「还剩 1 天」，而不是 0 天
        return ChronoUnit.DAYS.between(today, card.getEndDate()) + 1;
    }
}
