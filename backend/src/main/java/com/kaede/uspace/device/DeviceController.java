package com.kaede.uspace.device;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.device.dto.DeviceGroupVo;
import com.kaede.uspace.device.dto.EquipmentTypeVo;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 机台接口（模块 4 的用户端）。
 *
 * <p>两个接口都是<b>只读的公开信息</b>，允许匿名访问（配在 {@code SecurityConfig}
 * 的放行列表里）：
 * <ul>
 *   <li>{@code /api/devices} —— 店内机台陈列。顾客走到店门口想先看看有什么机器，
 *       没有理由要求先注册</li>
 *   <li>{@code /api/devices/types} —— 可选类型列表。顾客在个人资料里选游玩偏好时
 *       勾选它，同时也供运营后台新增机台时选类型（公开数据，后台直接复用）</li>
 * </ul>
 *
 * <p><b>不提供按 ID 查单台机台的接口</b>：陈列本来就是整批展示，
 * 没有「只看某一台」的场景。真需要时再加。
 *
 * <p><b>本模块与准入无关</b>：机台状况不影响能不能下单、能不能开门 ——
 * 那由停业与包场决定。所以这里没有任何「可预订 / 空闲」之类的字段，
 * 顾客看到「维护中」也只是知道别去碰那台机器。
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    /**
     * 查询店内机台陈列，按类型分组。
     *
     * <p>返回<b>全部机台</b>，含维护中的 —— 陈列的目的就是让顾客看到「这台在修」。
     * 门店信息页的概况与「店内设施」页的完整陈列<b>共用这一份数据</b>，
     * 概况里的台数统计由前端自行完成。
     *
     * @return 按类型分组的机台列表，组内已按展示顺序排好
     */
    @GetMapping
    public ResponseEntity<ApiResult<List<DeviceGroupVo>>> list() {
        return ApiResult.of(deviceService.listForDisplay());
    }

    /**
     * 查询可选的设备类型列表（仅启用中的）。
     *
     * <p>只给 {@code code} 与 {@code name}，顺序即排序权重。
     * 顾客的游玩偏好存的就是这里的 {@code code}。
     *
     * @return 启用中的类型列表
     */
    @GetMapping("/types")
    public ResponseEntity<ApiResult<List<EquipmentTypeVo>>> listTypes() {
        return ApiResult.of(deviceService.listSelectableTypes());
    }
}
