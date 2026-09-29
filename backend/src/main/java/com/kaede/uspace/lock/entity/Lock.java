package com.kaede.uspace.lock.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kaede.uspace.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 智能门锁实体，对应 {@code biz_lock} 表。
 *
 * <p>本实体是随模块 8 补上的 —— 在此之前 {@code biz_lock} 表只有建表脚本，
 * 没有任何 Java 代码。模块 8 的「点击开门」要下发限时密码，
 * 而下发密码的入参是 {@code lockId}，必须有东西把当前门店的锁 ID 查出来，
 * 整条主链路才能在第一步走下去。补实体（而非在订单 Mapper 里直接查锁表）
 * 是为了守住「一张表一个 Mapper」的边界：锁是模块 5 的地盘。
 *
 * <p><b>命名说明</b>：本类与 {@code java.util.concurrent.locks.Lock} 同名，
 * 但包不同、用途毫不相干，同一文件里不会同时引用两者，不构成冲突。
 * 沿用「表名去掉 biz_ 前缀」的项目惯例（{@code biz_store} → {@code Store}）。
 *
 * <p><b>⚠️ 本实体含敏感字段</b>（{@code aesKeyStr} / {@code adminPwd} / {@code pwdInfo}，
 * 建表脚本已标注「应加密存储」）。<b>严禁把本实体直接作为接口返回值</b> ——
 * 需要对外暴露门锁信息时另建 VO 并逐字段挑选。当前没有任何 Controller 返回本类。
 *
 * <p>本表在运营中几乎只读：单门店下只有一把锁，由建表脚本预置，
 * 接入真实通通锁时改数据库即可，不需要新增接口。
 *
 * @see com.kaede.uspace.lock.LockService 门锁云的调用接口（与本实体是两回事）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_lock")
public class Lock extends BaseEntity {

    /** 主键。策略取 application.properties 里配的数据库自增 */
    @TableId
    private Long id;

    /** 所属门店 ID。单门店期间恒为 1，开分店后用于按门店取锁 */
    private Long storeId;

    /** 锁名称，供后台识别，如「大门锁」 */
    private String lockName;

    /** 通通锁 lockMac，锁的物理标识。有唯一索引 */
    private String lockMac;

    /** 通通锁 /v3/lock/init 返回的 lockId。接入真实锁前为空 */
    private Long ttlockLockId;

    /** 通通锁返回的 keyId（管理员钥匙） */
    private Long ttlockKeyId;

    /** [敏感] 通通锁 aesKeyStr，应加密存储 */
    private String aesKeyStr;

    /** [敏感] 管理员密码，应加密存储 */
    private String adminPwd;

    /** [敏感] 密码数据，生成密码用，应加密存储 */
    private String pwdInfo;

    /** 网关 ID。为空表示未接网关，无法远程下发密码（addType=2 不可用） */
    private String gatewayId;

    /** 电量百分比 */
    private Integer battery;

    /** 在线状态：ONLINE / OFFLINE / UNKNOWN */
    private String onlineStatus;

    /** 最后一次与门锁云同步的时刻 */
    private LocalDateTime lastSyncAt;
}
