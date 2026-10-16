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
     * 此刻是否真正生效（状态为生效中、且今天落在有效期之内）。
     *
     * <p>由后端算好而不是让前端拼 {@code status === 'ACTIVE'}：日期那一半
     * 也要判，而「过期翻转是定时任务做的、可能滞后」这件事只有后端知道
     *（见 {@code MonthlyCard.isEffectiveOn}）。前端拿这个布尔值决定
     * 操作按钮的显隐即可，不要在客户端重实现一遍口径。
     */
    private Boolean effective;

    /**
     * 剩余天数（含今天）。
     *
     * <p><b>只有此刻真正生效的卡才有值</b>，其余（已过期、已退款、还没到生效日）
     * 一律为 null —— 前端据此决定要不要显示「还剩 N 天」，
     * 而不是自己拿日期去算（那样就得在客户端再实现一遍「含首尾」的口径）。
     *
     * <p><b>STAFF 卡也是 null</b>：它的到期日在 2099，算出「还剩两万多天」
     * 对谁都没有意义 —— 前端按卡种显示「长期有效」。
     */
    private Long remainingDays;

    /**
     * 把实体转成视图。
     *
     * @param card  月卡实体
     * @param today 今天的日期，用于算剩余天数与生效判定
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
        vo.setEffective(card.isEffectiveOn(today));
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
                ? MonthlyCardType.valueOf(cardType).getScope().getLabel()
                : null;
    }

    /**
     * 算剩余天数（含今天）。
     *
     * <p>判定口径与免单判定共用 {@link MonthlyCard#isEffectiveOn} —— 分开各写一份的话，
     * 会出现「列表说生效、结算说不生效」这种自相矛盾（少判状态的话，
     * 一张已退款的卡也会显示「还剩 12 天」）。
     *
     * @param card  月卡实体
     * @param today 今天的日期
     * @return 剩余天数；当前不生效时、或STAFF 卡（远期到期日）返回 null
     */
    private static Long remainingDaysOf(MonthlyCard card, LocalDate today) {
        if (!card.isEffectiveOn(today)) {
            return null;
        }
        // STAFF 卡的到期日在 2099：它其实不会到期，「还剩两万多天」是句废话。
        // 按卡种判而不是按日期大小判 —— 后者在 2099 年之后会静默失效
        if (MonthlyCardType.STAFF.name().equals(card.getCardType())) {
            return null;
        }
        // 含首尾：最后一天当天显示「还剩 1 天」，而不是 0 天
        return ChronoUnit.DAYS.between(today, card.getEndDate()) + 1;
    }
}
