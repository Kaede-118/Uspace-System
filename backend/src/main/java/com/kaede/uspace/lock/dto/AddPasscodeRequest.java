package com.kaede.uspace.lock.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 下发限时密码的请求参数。
 *
 * <p>字段命名与语义对齐通通锁云 API 的 {@code POST /v3/keyboardPwd/add}，
 * 目的是将来从模拟实现切换到真实调用时，上层代码无需改动。
 *
 * <p>与通通锁参数的对应关系：
 * <pre>
 *   lockId           -> lockId
 *   keyboardPwd      -> keyboardPwd
 *   keyboardPwdName  -> keyboardPwdName
 *   startTime        -> startDate（Unix 秒级时间戳）
 *   endTime          -> endDate  （Unix 秒级时间戳）
 *   addType          -> addType
 * </pre>
 */
@Data
public class AddPasscodeRequest {

    /** 锁 ID。由通通锁 {@code /v3/lock/init} 返回，或从锁列表接口获取 */
    @NotNull(message = "锁 ID 不能为空")
    private Long lockId;

    /** 密码内容。留空则由服务方随机生成 */
    private String keyboardPwd;

    /** 密码名称，便于后台识别用途，如「3号房-订单20260915001」 */
    private String keyboardPwdName;

    /**
     * 生效时间。
     *
     * <p>通通锁要求自定义密码必须是限时密码，因此本字段与 {@link #endTime} 均为必填。
     */
    @NotNull(message = "生效时间不能为空")
    private LocalDateTime startTime;

    /** 失效时间。到达该时刻后密码自动失效，无需再调删除接口 */
    @NotNull(message = "失效时间不能为空")
    private LocalDateTime endTime;

    /**
     * 下发方式，对应通通锁的 addType 参数。
     *
     * <ul>
     *   <li>1 = 先在 APP 内用蓝牙添加，再调接口同步到云端（非远程）</li>
     *   <li>2 = 经网关或 Wi-Fi 锁<b>远程直接下发</b>（无人值守场景必须用这个）</li>
     *   <li>3 = NB-IoT 锁，需等网络唤醒后才下发</li>
     * </ul>
     *
     * <p>默认 2，因为本系统的核心场景就是远程下发。
     */
    private Integer addType = 2;
}
