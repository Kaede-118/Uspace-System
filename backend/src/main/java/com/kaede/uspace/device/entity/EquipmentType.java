package com.kaede.uspace.device.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kaede.uspace.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 设备类型实体，对应 {@code biz_equipment_type} 表（模块 4 的字典表）。
 *
 * <p><b>本表先于机台台账落地</b>，因为有两处要共用同一份取值：
 * <ul>
 *   <li><b>用户的游玩偏好</b>（模块 1）—— {@code sys_user.preference} 存的就是
 *       本表的 {@code code}，逗号分隔。让顾客自报偏好与机台标注类型用同一份取值，
 *       避免两边各维护一套、慢慢漂移</li>
 *   <li><b>机台台账</b>（本模块）—— 每台 {@code biz_device} 指向本表的一个类型，
 *       陈列时按类型分组</li>
 * </ul>
 *
 * <p>与机台一样，<b>本表不参与计费</b>：计费规则对娱乐类型不做区分
 * （「打日麻」与「打音游」同价），这是刻意的 —— 界定手段尚未落地之前，
 * 计费规则不预留相关字段，理由见 {@code docs/开发约定与设计说明.md} 的「待定事项」。
 *
 * <p>类型的增删改由运营在后台维护（接口见 {@code AdminDeviceController}），
 * 当前由建表脚本预置两条：拍拍机 / 抬手乐。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_equipment_type")
public class EquipmentType extends BaseEntity {

    /** 主键。策略取 application.properties 里配的数据库自增 */
    @TableId
    private Long id;

    /**
     * 类型代码，如 {@code PAIPAI} / {@code TAISHOU}，唯一。
     *
     * <p>存进 {@code sys_user.preference} 的就是它，所以<b>代码一旦被使用就不该改</b> ——
     * 改了会让已有用户的偏好字符串解析不出名称。要改就停用旧代码、新增一个。
     */
    private String code;

    /** 类型名称，如「拍拍机」「抬手乐」。对外展示 */
    private String name;

    /** 排序权重，越小越靠前。决定前端复选框与陈列分组的显示顺序 */
    private Integer sort;

    /**
     * 是否启用：1=启用 0=停用。
     *
     * <p>停用后不再出现在「可选类型」里（如顾客选偏好时的复选框），
     * 但<b>历史偏好仍能解析出名称</b>，机台上也不会因此失去类型 ——
     * 所以停用是「不再新增使用」，不是「删掉」。
     */
    private Integer enabled;

    /** 备注 */
    private String remark;
}
