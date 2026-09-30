package com.kaede.uspace.device.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增或修改机台的请求。
 *
 * <p>新增与修改共用同一个请求体：两者的业务字段完全相同，
 * 分成两个类只会多一份重复的校验注解。理由同 {@code ClosureRequest}。
 *
 * <p><b>状况的取值范围不在这里校验</b>（不写成 {@code @Pattern}）：
 * 合法取值的权威定义在 {@code DeviceStatus} 枚举里，再写一份正则就是第二份清单，
 * 将来加一态时容易只改一处。这条校验在 Service 里调
 * {@code DeviceStatus.isValid} 完成。
 */
@Data
public class DeviceRequest {

    /** 机台名称，必填 */
    @NotBlank(message = "机台名称不能为空")
    @Size(max = 50, message = "机台名称不能超过 50 个字符")
    private String name;

    /**
     * 资产编号，选填。
     *
     * <p>可为空 —— 新机到店还没贴编号时也能先录入。
     * 填了则要求同门店内不重复（Service 查重 + 库上唯一索引兜底）。
     */
    @Size(max = 32, message = "资产编号不能超过 32 个字符")
    private String deviceNo;

    /** 设备类型 ID，必填。必须指向一个存在的类型 */
    @NotNull(message = "必须指定设备类型")
    private Long typeId;

    /** 位置描述，选填 */
    @Size(max = 64, message = "位置描述不能超过 64 个字符")
    private String location;

    /**
     * 状况名，选填。
     *
     * <p>为空时<b>新增</b>默认「良好」；<b>修改</b>时则保持原值 ——
     * 一个不带状况的 PUT 不该把维护中的机台悄悄改回良好。
     */
    private String status;

    /** 展示顺序，选填。为空时新增按 0 处理 */
    @Min(value = 0, message = "展示顺序不能为负数")
    private Integer sort;

    /** 备注，仅运营可见，选填 */
    @Size(max = 255, message = "备注不能超过 255 个字符")
    private String remark;
}
