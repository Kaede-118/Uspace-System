package com.kaede.uspace.access.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理员手工补录开门记录的请求。
 *
 * <p>使用场景：系统或门锁故障期间，顾客开门这件事没能留下记录（比如锁离线期间
 * 记录没上报到云端），事后由管理员根据纸质登记或监控录像补入。
 *
 * <p><b>补录的记录来源固定记为 {@code ADMIN}</b>，不由请求方指定 ——
 * 「这条记录是人工补的」是管理员无法伪造掉的事实，若让请求方自己传，
 * 就存在把补录记录伪装成门锁云同步结果的可能，审计上失去意义。
 *
 * <p>补录的开门时刻不能晚于当前（那是还没发生的事），但<b>可以是任意早的时刻</b> ——
 * 补录本来就是在事后做的。
 */
@Data
public class ManualOpenRequest {

    /** 锁 ID，必填 */
    @NotNull(message = "锁 ID 不能为空")
    private Long lockId;

    /**
     * 开门时刻，必填。
     *
     * <p>不能晚于当前时刻；早于当前任意时刻均可（补录天然是事后行为）。
     */
    @NotNull(message = "开门时刻不能为空")
    private LocalDateTime openTime;

    /** 开门人用户 ID。顾客本人补录时填其用户 ID；无法确定时留空 */
    private Long userId;

    /** 关联订单 ID。能对上订单时填上，便于事后按订单追溯；对不上则留空 */
    private Long orderId;

    /** 本次开门所用密码。无法确定时留空 */
    private String passcode;
}
