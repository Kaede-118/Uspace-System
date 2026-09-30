package com.kaede.uspace.device.dto;

import com.kaede.uspace.device.DeviceStatus;
import com.kaede.uspace.device.entity.Device;
import com.kaede.uspace.device.entity.EquipmentType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 机台视图（<b>后台用</b>，含运营内部字段）。
 *
 * <p>用户端看的是 {@link DeviceDisplayVo}，两者刻意分开：
 * 本视图带 {@code remark}（备注），那是运营内务（「等屏幕配件到货」
 * 「张三反映左键不灵」），不该出现在顾客面前。分开一个类比在展示层
 * 靠「前端不显示」更可靠 —— 字段一旦发出去，就再也收不回来了。
 *
 * <p><b>类型名与类型代码在这里就解析好</b>：机台表只存 {@code typeId}，
 * 前端要显示「拍拍机 1 号 · 拍拍机」得自己再查一次字典。由 Service 组装
 * 可以一次查完字典、一次组装，也避免前端各自处理「类型已被停用/删除」的分支。
 */
@Data
public class DeviceVo {

    /** 机台 ID */
    private Long id;

    /** 机台名称 */
    private String name;

    /** 资产编号，可为空 */
    private String deviceNo;

    /** 设备类型 ID */
    private Long typeId;

    /** 设备类型代码，如 {@code PAIPAI}。可为空（类型已被删除时） */
    private String typeCode;

    /** 设备类型名称，如「拍拍机」。可为空（类型已被删除时） */
    private String typeName;

    /** 位置描述，可为空 */
    private String location;

    /** 状况名，取值见 {@link DeviceStatus} */
    private String status;

    /** 状况的中文说明，如「维护中」。由后端翻译，免得每个前端各维护一份对照表 */
    private String statusLabel;

    /** 展示顺序 */
    private Integer sort;

    /** 备注，仅运营可见 */
    private String remark;

    /** 最后更新时间 */
    private LocalDateTime updatedAt;

    /**
     * 由实体与类型构造 VO。
     *
     * @param device 机台实体
     * @param type   机台所属的类型，可为 null（类型已被删除时）——
     *               此时类型字段留空而不是报错：机台本身是有效数据，
     *               不能因为字典缺了一条就查不出来
     * @return 机台视图；机台为 null 时返回 null
     */
    public static DeviceVo from(Device device, EquipmentType type) {
        if (device == null) {
            return null;
        }
        DeviceVo vo = new DeviceVo();
        vo.setId(device.getId());
        vo.setName(device.getName());
        vo.setDeviceNo(device.getDeviceNo());
        vo.setTypeId(device.getTypeId());
        vo.setTypeCode(type == null ? null : type.getCode());
        vo.setTypeName(type == null ? null : type.getName());
        vo.setLocation(device.getLocation());
        vo.setStatus(device.getStatus());
        vo.setStatusLabel(DeviceStatus.labelOf(device.getStatus()));
        vo.setSort(device.getSort());
        vo.setRemark(device.getRemark());
        vo.setUpdatedAt(device.getUpdatedAt());
        return vo;
    }
}
