package com.kaede.uspace.device.dto;

import com.kaede.uspace.device.DeviceStatus;
import com.kaede.uspace.device.entity.Device;
import lombok.Data;

/**
 * 机台陈列视图（<b>用户端用</b>）。
 *
 * <p>只有顾客该看到的字段：叫什么、编号多少、摆在哪儿、现在能不能玩。
 * 与后台的 {@link DeviceVo} 分开，是为了让边界落在类型上而不是「前端记着别显示」——
 * 备注那类运营内务一旦跟着响应发出去，就等于公开了。
 *
 * <p>同理不含 {@code sort} 与 {@code updatedAt}：前者是后台的排序依据，
 * 后端已按它排好序，前端不必知道权重是多少；后者对顾客没有意义。
 */
@Data
public class DeviceDisplayVo {

    /** 机台 ID，供前端渲染列表时作 key */
    private Long id;

    /** 机台名称，如「拍拍机 1 号」 */
    private String name;

    /** 资产编号，可为空。顾客可用它与现场贴纸核对 */
    private String deviceNo;

    /** 位置描述，可为空。如「靠窗第二台」 */
    private String location;

    /** 状况名，取值见 {@link DeviceStatus} */
    private String status;

    /** 状况的中文说明，如「待维护」 */
    private String statusLabel;

    /**
     * 该状况下机台是否可用。
     *
     * <p>由后端算好交给前端：判断口径（「待维护」算可用）只该有一处定义，
     * 让每个前端各自去比对字符串，早晚会漂移成「网页能玩、小程序说不能玩」。
     * 前端拿它决定标签样式与提示语即可。
     */
    private boolean usable;

    /**
     * 由实体构造 VO。
     *
     * @param device 机台实体
     * @return 陈列视图；入参为 null 时返回 null
     */
    public static DeviceDisplayVo from(Device device) {
        if (device == null) {
            return null;
        }
        DeviceDisplayVo vo = new DeviceDisplayVo();
        vo.setId(device.getId());
        vo.setName(device.getName());
        vo.setDeviceNo(device.getDeviceNo());
        vo.setLocation(device.getLocation());
        vo.setStatus(device.getStatus());
        vo.setStatusLabel(DeviceStatus.labelOf(device.getStatus()));
        vo.setUsable(DeviceStatus.isUsable(device.getStatus()));
        return vo;
    }
}
