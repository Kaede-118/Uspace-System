package com.kaede.uspace.billing.event;

/**
 * 免费活动发生了什么 —— {@link FreePeriodChangedEvent} 的动作标识。
 *
 * <p>与 {@code ClosureChangeAction} 同构，但两个枚举<b>刻意各留一份</b>：
 * 一个住 space 包、一个住 billing 包，合并成一个就得找个第三处安放，
 * 而两者的取值将来未必同步演进（比如停业可能先支持「改期也播」）。
 * 两个各五行的枚举，比一个要解释「为什么它住在公共层」的枚举便宜。
 */
public enum FreePeriodChangeAction {

    /** 新增免费活动（{@code FreePeriodService#createPeriod}） */
    CREATED,

    /** 撤销免费活动（{@code FreePeriodService#deletePeriod}，逻辑删除） */
    DELETED
}
