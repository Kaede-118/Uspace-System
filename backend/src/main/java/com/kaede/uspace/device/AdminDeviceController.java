package com.kaede.uspace.device;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.device.dto.DeviceRequest;
import com.kaede.uspace.device.dto.DeviceVo;
import com.kaede.uspace.device.dto.UpdateDeviceStatusRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 机台管理接口（模块 4 的管理员侧）。
 *
 * <p>路径前缀 {@code /api/admin}，与用户端的 {@code /api/devices} 分开。
 *
 * <p><b>权限声明放在类上而不是每个方法上</b>：本类每个接口都要求管理员，
 * 逐个方法写 {@code @PreAuthorize} 只是重复，且新增方法时容易漏 ——
 * 漏掉的后果是接口裸奔且没有任何报错提醒。理由详见 {@code AdminUserController}。
 *
 * <p><b>列表不分页</b>：机台是「店里有几台机器」，十几到几十条封顶，
 * 与停业记录那种会逐年累积的数据不同，套分页只会让前端多写一轮翻页逻辑。
 * 理由同 {@code DeviceMapper} 的类注释。
 *
 * <p><b>没有类型字典的增删改接口</b>：当前类型由建表脚本预置（拍拍机、抬手乐），
 * 增删场景尚未出现。要做时在这里补，注意<b>类型代码一旦被用户偏好引用就不该改</b>——
 * 改了会让已有偏好字符串解析不出名称（见 {@code EquipmentType#code}）。
 */
@RestController
@RequestMapping("/api/admin/devices")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminDeviceController {

    private final DeviceService deviceService;

    public AdminDeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    /**
     * 查询全部机台（含运营内部字段：备注、排序权重、类型代码）。
     *
     * <p>含维护中的机台 —— 后台恰恰要靠它来找到「哪几台要修」。
     *
     * @return 机台列表，按展示顺序排列
     */
    @GetMapping
    public ResponseEntity<ApiResult<List<DeviceVo>>> list() {
        return ApiResult.of(deviceService.listDevices());
    }

    /**
     * 新增机台。
     *
     * <p>状况不填默认「良好」；资产编号可不填（新机还没贴编号时也能先录入），
     * 填了则要求同门店内不重复。
     *
     * @param request 机台字段
     * @return 新建的机台
     */
    @PostMapping
    public ResponseEntity<ApiResult<DeviceVo>> create(@Valid @RequestBody DeviceRequest request) {
        return ApiResult.of(deviceService.createDevice(request));
    }

    /**
     * 修改机台（全量替换：字段传 null 即清空）。
     *
     * <p><b>状况是唯一例外</b>：{@code status} 传 null 时保持原值，
     * 不会被一次普通改名顺手改回「良好」。要单独改状况请用下面的接口。
     *
     * @param id      机台 ID
     * @param request 新的机台字段
     * @return 更新后的机台
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResult<DeviceVo>> update(
            @PathVariable Long id,
            @Valid @RequestBody DeviceRequest request) {
        return ApiResult.of(deviceService.updateDevice(id, request));
    }

    /**
     * 修改机台状况。
     *
     * <p>运营里最高频的动作，单独成一个接口：只更新状况一列，
     * 不会碰到名称、位置等其他字段，也就不存在「覆盖别人刚改的内容」的窗口。
     *
     * @param id      机台 ID
     * @param request 新的状况
     * @return 成功时 data 为 null
     */
    @PutMapping("/{id}/status")
    public ResponseEntity<ApiResult<Void>> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateDeviceStatusRequest request) {
        return ApiResult.of(deviceService.updateStatus(id, request));
    }

    /**
     * 删除机台（逻辑删除），用于机器退役、搬走。
     *
     * @param id 机台 ID
     * @return 成功时 data 为 null
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResult<Void>> delete(@PathVariable Long id) {
        return ApiResult.of(deviceService.deleteDevice(id));
    }
}
