package com.kaede.uspace.device.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kaede.uspace.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 机台实体，对应 {@code biz_device} 表。
 *
 * <p><b>本实体不进主链路</b>：不参与计费，也不绑定订单 ——
 * 用户下单时不选机台，计费也不看他玩了哪台。它承担的是一份
 * <b>资产台账 + 陈列数据</b>：店里有哪些机器、每台什么状况、摆在哪儿。
 *
 * <p><b>{@code status} 是「能不能玩」，不是「谁在玩」</b>：
 * 三态见 {@link com.kaede.uspace.device.DeviceStatus}，都由管理员手工维护。
 * 要做到「谁正占着哪台」需要设备级使用记录（设备侧上报、或用户下单时选机台），
 * 成本高一个量级，当前明确不做 —— 详见 {@code docs/开发约定与设计说明.md}
 * 的「待定事项」。
 *
 * <p><b>本表的状态挡不住人</b>：能不能进店只由停业与包场决定，
 * 哪怕全店机器都标成维护中，系统照样放人进门。
 *
 * <p>退役的机台走<b>逻辑删除</b>（{@code deleted = 1}），不加「报废」状态 ——
 * 一台机器一旦退役就不再陈列，与「暂时不能玩」是两回事；
 * 混进状态枚举里会让「店里还有几台」这个数需要额外过滤才对。
 *
 * @see EquipmentType 机台类型字典
 * @see com.kaede.uspace.device.DeviceStatus 状况三态
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_device")
public class Device extends BaseEntity {

    /** 主键。策略取 application.properties 里配的数据库自增 */
    @TableId
    private Long id;

    /**
     * 所属门店 ID。
     *
     * <p>单门店运营下恒为当前那家店，字段仍保留 —— 与 {@code biz_closure} /
     * {@code biz_booking} 一致，开分店时不必改表。
     */
    private Long storeId;

    /** 机台名称，如「拍拍机 1 号」。对外展示 */
    private String name;

    /**
     * 资产编号，如 {@code PP-01}。现场贴纸编号，便于管理员与顾客核对。
     *
     * <p>可空 —— 新机到店还没来得及贴编号时也能先录入。
     * 同门店内不重复（库里是 {@code uk_store_device_no}，且 MySQL 允许多行为 NULL），
     * 所以「几台都没编号」不冲突，填了才要求唯一。
     */
    private String deviceNo;

    /** 设备类型 ID，指向 {@code biz_equipment_type} 的一个类型 */
    private Long typeId;

    /** 位置描述，如「靠窗第二台」。帮顾客在店里找到它 */
    private String location;

    /**
     * 状况名，取值见 {@link com.kaede.uspace.device.DeviceStatus}。
     *
     * <p>存字符串而不是枚举类型：与 {@code biz_order.status} 同一套做法，
     * 库里存的就是人可读的名字（{@code NORMAL} / {@code NEEDS_REPAIR} /
     * {@code MAINTAINING}），排查数据时不必回查对照表。
     */
    private String status;

    /** 展示顺序，越小越靠前。管理员可调，决定陈列时的排列 */
    private Integer sort;

    /**
     * 备注，<b>仅运营可见</b>（如「等屏幕配件到货」「张三反映左键不灵」）。
     *
     * <p>因此不出现在用户端的陈列视图里 —— 那道边界在
     * {@code DeviceDisplayVo} 上，与门店停业原因不外泄是同一个道理。
     */
    private String remark;
}
