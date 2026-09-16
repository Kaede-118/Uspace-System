package com.kaede.uspace.lock.dto;

import java.time.LocalDateTime;

/**
 * 开门记录项。
 *
 * <p>对应通通锁 {@code POST /v3/lockRecord/list} 返回的单条记录。
 * 供「进出记录」模块（模块 6）落库使用。
 *
 * @param lockId      锁 ID
 * @param keyboardPwd 本次开门所用的密码。用指纹、钥匙等方式开门时可能为空
 * @param openTime    开门时间
 * @param openType    开门方式。通通锁原样返回的枚举值，取值含义参见官方文档
 * @param userId      系统内的用户 ID。仅当所用密码是由本系统下发的，才能反查出来；
 *                    其余情况为 null
 */
public record LockRecordDto(
        Long lockId,
        String keyboardPwd,
        LocalDateTime openTime,
        Integer openType,
        Long userId
) {
}
