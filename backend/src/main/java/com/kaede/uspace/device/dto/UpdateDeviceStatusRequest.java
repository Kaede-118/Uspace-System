package com.kaede.uspace.device.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 修改机台状况的请求。
 *
 * <p>只有一个字段，看起来单薄，但它对的是一个<b>高频且独立</b>的动作：
 * 机器坏了点一下、修好了再点一下。走全量替换的话，前端得先把整条记录读出来、
 * 改这一个字段、再整个传回去 —— 中间若有别人改了名称或位置，
 * 这次提交会静默覆盖对方的改动。
 *
 * <p>取值合法性在 Service 里用 {@code DeviceStatus.isValid} 校验，
 * 此处只保证非空。
 */
@Data
public class UpdateDeviceStatusRequest {

    /** 新的状况名，取值见 {@code DeviceStatus} */
    @NotBlank(message = "机台状况不能为空")
    private String status;
}
