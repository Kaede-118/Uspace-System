package com.kaede.uspace.access.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 开门记录实体，对应 {@code biz_access_record} 表。
 *
 * <p><b>本表记录的是「开门事件」，而非严格意义上的进与出。</b>封闭空间的出门通常是
 * 机械推杠或按钮（消防要求内部必须能免密自由开门），<b>不产生门锁记录</b>，
 * 因此系统只能记录到「进门」这一次开门；用户的离场时刻由其在应用内手动点
 * 「结束使用」确定，落在 {@code biz_order.end_time}，属模块 8。
 * 这是由安全要求反推出来的数据模型限制，不是设计疏漏。
 *
 * <p><b>本表是 append-only 的事件日志</b>：记录一旦写入就不再修改，也不适用逻辑删除 ——
 * 开门是已经发生过的既成事实，改它等于篡改审计凭据。因此本模块
 * <b>不提供修改与删除接口</b>。
 *
 * <p><b>刻意不继承 {@code BaseEntity}</b>：基类带 {@code updated_at} 与 {@code deleted} 两个字段，
 * 而本表只有 {@code created_at} 一列。继承的话，MyBatis-Plus 会往 INSERT 语句里塞两个
 * 库里并不存在的列，直接 {@code Unknown column}；查询也会被自动追加 {@code deleted = 0}。
 * {@code BaseEntity} 的类注释里点了本表的名，{@code application.properties} 的逻辑删除
 * 配置注释也说明了「{@code biz_access_record} 表无此列，不受影响」——
 * 三处说的是同一件事。{@code created_at} 靠下面字段上的
 * {@code @TableField(fill = FieldFill.INSERT)} 单独指定，不继承基类也能自动填充。
 *
 * @see com.kaede.uspace.access.AccessSource 记录来源的取值定义
 */
@Data
@TableName("biz_access_record")
public class AccessRecord {

    /** 主键。策略取 application.properties 里配的数据库自增 */
    @TableId
    private Long id;

    /**
     * 关联订单 ID，可为空。
     *
     * <p>管理员开门、事后补录等无订单场景为空；<b>从门锁云同步进来的记录也恒为空</b> ——
     * 云端返回的 {@code LockRecordDto} 不带订单号，而本模块不持有订单数据
     * （模块之间不反向依赖），因此同步路径上不做关联。
     *
     * <p>需要按订单追溯开门记录时（如用户忘记点「结束使用」、管理员核对监控），
     * 用订单上的 {@code lock_id} 与 {@code passcode} 配合时间范围查询即可 ——
     * {@code idx_lock_time} 正好覆盖这个查法。
     *
     * <p><b>本列不参与计费</b>：计费起点是订单的 {@code start_time}
     * （用户点击「开门」的时刻），由模块 8 自行记录，无需等门锁记录回来对齐。
     */
    private Long orderId;

    /**
     * 开门人用户 ID，可为空。
     *
     * <p>仅当本次开门所用密码是由本系统下发的，才能反查出来；
     * 用指纹、钥匙等方式开门，或密码来自其他渠道时为空。
     */
    private Long userId;

    /**
     * 门店 ID，不为空。
     *
     * <p>通通锁返回的开门记录里没有门店信息，本模块按<b>单门店运营</b>的现状
     * 取当前门店。将来开分店时须改为按 {@code biz_lock.store_id} 反查。
     */
    private Long storeId;

    /** 门锁 ID，不为空 */
    private Long lockId;

    /** 本次开门所用密码。用指纹、钥匙等方式开门时为空。列宽 {@code VARCHAR(10)}，通通锁密码为 6~7 位 */
    private String passcode;

    /** 开门方式。通通锁 {@code lockRecord} 原样返回的枚举值，取值含义参见官方文档 */
    private Integer openType;

    /**
     * 开门时刻，不为空。
     *
     * <p><b>落库前一律截断到秒</b>（见 {@code AccessRecordService}）。原因是门锁云返回的时间
     * 可能带亚秒精度，而本列是 {@code DATETIME}（秒精度），MySQL 会对其<b>四舍五入</b> ——
     * 那样「拉回来的值」与「库里的值」将永远不相等，按 {@code (lock_id, open_time)}
     * 做的重复判定会静默失效，每次同步都重复插入，且不报任何错。
     *
     * <p>与 {@link #createdAt} 是两回事：本字段是<b>开门发生的时刻</b>，
     * 可从云端补拉历史数据，因此可能早于记录落库的时刻。
     */
    private LocalDateTime openTime;

    /**
     * 记录来源，取值见 {@link com.kaede.uspace.access.AccessSource}。
     *
     * <p>存的是枚举名（{@code MOCK} / {@code TTLOCK} / {@code ADMIN}）。
     * 列定义是 {@code VARCHAR(20)} 而非 {@code ENUM}，库不会替我们挡住非法值。
     */
    private String source;

    /**
     * 记录落库时刻（不是开门时刻）。插入时由 {@code AuditMetaObjectHandler} 自动填充，
     * 业务代码不要赋值。
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
