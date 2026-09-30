package com.kaede.uspace.device.dto;

import com.kaede.uspace.device.entity.EquipmentType;
import lombok.Data;

/**
 * 设备类型视图。
 *
 * <p>供「可选类型」列表使用，有两个消费方：
 * <ul>
 *   <li><b>用户端</b> —— 顾客在个人资料里选游玩偏好，勾的就是这里的 {@code code}</li>
 *   <li><b>运营后台</b> —— 新增或修改机台时选类型</li>
 * </ul>
 *
 * <p>只给 {@code code} 与 {@code name}：{@code sort} 已经体现在返回顺序里，
 * {@code enabled} 的过滤在查询时就做掉了，前端拿到这两样就够渲染。
 */
@Data
public class EquipmentTypeVo {

    /** 类型 ID。机台表引用的是它 */
    private Long id;

    /** 类型代码，如 {@code PAIPAI}。用户偏好存的就是这个值 */
    private String code;

    /** 类型名称，如「拍拍机」。前端展示用 */
    private String name;

    /**
     * 由实体构造 VO。
     *
     * @param type 类型实体
     * @return 类型视图；入参为 null 时返回 null
     */
    public static EquipmentTypeVo from(EquipmentType type) {
        if (type == null) {
            return null;
        }
        EquipmentTypeVo vo = new EquipmentTypeVo();
        vo.setId(type.getId());
        vo.setCode(type.getCode());
        vo.setName(type.getName());
        return vo;
    }
}
