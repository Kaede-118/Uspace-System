package com.kaede.uspace.access.dto;

import com.kaede.uspace.access.AccessSource;
import com.kaede.uspace.access.entity.AccessRecord;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 开门记录视图。
 *
 * <p>用户端与管理端共用同一个视图 —— 与门店信息同理：本表没有「管理员能看、
 * 普通用户不能看」的字段（记录本来就是顾客自己的开门行为，密码也是他自己用的那串）。
 * 两端复用同一份结构，前端也能共用同一个列表组件。
 *
 * <p>不含 {@code deleted} 这类内部字段（本表压根没有），也不含任何审计字段之外的内部标记。
 */
@Data
public class AccessRecordVo {

    /** 记录 ID */
    private Long id;

    /**
     * 关联订单 ID，可为空。
     *
     * <p>为空有两种情形：管理员补录时未指定订单，或记录由门锁云同步而来
     * （云端记录不带订单号，见 {@code AccessRecord#orderId} 的说明）。
     */
    private Long orderId;

    /** 开门人用户 ID，可为空（指纹、钥匙开门或密码非本系统下发时为空） */
    private Long userId;

    /** 门店 ID */
    private Long storeId;

    /** 门锁 ID */
    private Long lockId;

    /** 本次开门所用密码，可为空 */
    private String passcode;

    /** 开门方式。通通锁原样返回的枚举值，含义参见官方文档 */
    private Integer openType;

    /** 开门时刻 */
    private LocalDateTime openTime;

    /**
     * 记录来源的枚举名（{@code MOCK} / {@code TTLOCK} / {@code ADMIN}）。
     *
     * <p>同时给出它与下面那个中文标签，是让前端自己选：
     * 要做筛选、比对这类逻辑时用枚举名，纯展示时用标签。
     */
    private String source;

    /** 记录来源的中文名称，如「模拟门锁」「管理员补录」。由 {@link AccessSource#getLabel()} 得来 */
    private String sourceLabel;

    /** 记录落库时刻。与 {@link #openTime} 是两回事：后者是开门发生的时刻 */
    private LocalDateTime createdAt;

    /**
     * 由实体构造视图。
     *
     * @param record 开门记录实体
     * @return 记录视图；入参为 null 时返回 null
     */
    public static AccessRecordVo from(AccessRecord record) {
        if (record == null) {
            return null;
        }
        AccessRecordVo vo = new AccessRecordVo();
        vo.setId(record.getId());
        vo.setOrderId(record.getOrderId());
        vo.setUserId(record.getUserId());
        vo.setStoreId(record.getStoreId());
        vo.setLockId(record.getLockId());
        vo.setPasscode(record.getPasscode());
        vo.setOpenType(record.getOpenType());
        vo.setOpenTime(record.getOpenTime());
        vo.setSource(record.getSource());
        vo.setSourceLabel(labelOf(record.getSource()));
        vo.setCreatedAt(record.getCreatedAt());
        return vo;
    }

    /**
     * 把来源枚举名转成中文标签。
     *
     * <p>库里存的是 {@code VARCHAR} 而非 {@code ENUM}，理论上可能出现枚举外的值
     * （比如手工改库、或将来新增来源却漏改枚举）。这里不做异常处理、
     * 直接回显原值 —— 展示层把异常值如实显示出来，比悄悄吞掉更容易发现问题。
     *
     * @param source 来源枚举名，可为 null
     * @return 中文标签；无法识别时原样返回入参
     */
    private static String labelOf(String source) {
        if (source == null) {
            return null;
        }
        for (AccessSource candidate : AccessSource.values()) {
            if (candidate.name().equals(source)) {
                return candidate.getLabel();
            }
        }
        return source;
    }
}
