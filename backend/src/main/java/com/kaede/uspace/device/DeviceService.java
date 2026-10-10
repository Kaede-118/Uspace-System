package com.kaede.uspace.device;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.device.dto.DeviceDisplayVo;
import com.kaede.uspace.device.dto.DeviceGroupVo;
import com.kaede.uspace.device.dto.DeviceRequest;
import com.kaede.uspace.device.dto.DeviceVo;
import com.kaede.uspace.device.dto.EquipmentTypeVo;
import com.kaede.uspace.device.dto.UpdateDeviceStatusRequest;
import com.kaede.uspace.device.entity.Device;
import com.kaede.uspace.device.entity.EquipmentType;
import com.kaede.uspace.device.mapper.DeviceMapper;
import com.kaede.uspace.device.mapper.EquipmentTypeMapper;
import com.kaede.uspace.notice.NoticeContents;
import com.kaede.uspace.notice.NoticeService;
import com.kaede.uspace.notice.NoticeSourceType;
import com.kaede.uspace.space.mapper.StoreMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 机台服务（模块 4）。
 *
 * <p>职责：店内的机台台账 —— 有哪些机器、每台什么状况、摆在哪儿 ——
 * 以及把这些信息组装成用户端可看的<b>陈列数据</b>。
 *
 * <p><b>本模块不进主链路</b>：不参与计费，也不绑定订单。用户下单时不选机台，
 * 计费也不看他玩了哪台；机台状况更<b>不影响准入</b> ——
 * 能不能进店只由停业与包场决定，哪怕全店机器都标成维护中，系统照样放人进门。
 *
 * <p><b>参数错误抛异常、业务失败走返回值</b>：同 {@code UserService} 的约定，
 * 本类所有校验失败都是「业务规则不接受」，一律走 {@link BizResult#fail}。
 *
 * <p><b>依赖方向</b>：本类依赖 {@code space} 包的 {@link StoreMapper} 取当前门店，
 * 反方向没有依赖（门店不认识机台）。机台与门店的关联只落在
 * {@code biz_device.store_id} 这一列上，不是一条双向关系。
 */
@Slf4j
@Service
public class DeviceService {

    /** 状况缺省值：新录入的机台默认「良好」 */
    private static final String DEFAULT_STATUS = DeviceStatus.NORMAL.name();

    /**
     * 类型已被删除时，兜底分组的名称。
     *
     * <p>正常情况下不会出现 —— 类型的删除接口当前就不提供。但万一有人手工改库
     * 把某条类型删了，挂在上面的机台不该从陈列页上凭空消失，
     * 那会让顾客以为机器搬走了。归到这个组里，异常数据在界面上一眼可见。
     */
    private static final String ORPHAN_GROUP_NAME = "其他";

    private final DeviceMapper deviceMapper;
    private final EquipmentTypeMapper equipmentTypeMapper;
    private final StoreMapper storeMapper;
    private final NoticeService noticeService;

    public DeviceService(DeviceMapper deviceMapper,
                         EquipmentTypeMapper equipmentTypeMapper,
                         StoreMapper storeMapper,
                         NoticeService noticeService) {
        this.deviceMapper = deviceMapper;
        this.equipmentTypeMapper = equipmentTypeMapper;
        this.storeMapper = storeMapper;
        this.noticeService = noticeService;
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 取用户端的机台陈列，按类型分组。
     *
     * <p>返回<b>全部机台</b>，含维护中的 —— 陈列的目的就是让顾客看到
     * 「这台在修」，藏起来反而会让人以为机器搬走了。
     *
     * <p>用户端两处展示（门店信息页的概况与「店内设施」页的完整陈列）
     * <b>共用这一份数据</b>：概况里的「几台正常、几台待维护」由前端从同一份结果里
     * 自行统计。分成两个接口会出现「首页说 6 台、设施页说 5 台」这类自相矛盾。
     *
     * @return 成功时返回按类型分组的陈列；门店不存在时返回 {@link ErrorCode#STORE_NOT_FOUND}
     */
    public BizResult<List<DeviceGroupVo>> listForDisplay() {
        Long storeId = storeMapper.selectCurrentId();
        if (storeId == null) {
            return BizResult.fail(ErrorCode.STORE_NOT_FOUND);
        }

        List<Device> devices = deviceMapper.selectListByStore(storeId);
        List<EquipmentType> types = equipmentTypeMapper.selectListAll();

        return BizResult.ok(groupByType(devices, types));
    }

    /**
     * 取后台机台列表（含运营内部字段）。
     *
     * <p>与用户端陈列取的是同一批数据，差别只在视图：本方法用 {@link DeviceVo}，
     * 多出备注、排序权重与类型代码这些运营要看、顾客不必看的字段。
     *
     * @return 成功时返回机台列表；门店不存在时返回 {@link ErrorCode#STORE_NOT_FOUND}
     */
    public BizResult<List<DeviceVo>> listDevices() {
        Long storeId = storeMapper.selectCurrentId();
        if (storeId == null) {
            return BizResult.fail(ErrorCode.STORE_NOT_FOUND);
        }

        List<Device> devices = deviceMapper.selectListByStore(storeId);
        Map<Long, EquipmentType> typeMap = typeMap();

        return BizResult.ok(devices.stream()
                .map(device -> DeviceVo.from(device, typeMap.get(device.getTypeId())))
                .toList());
    }

    /**
     * 取可选类型列表（仅启用中的），按排序权重排列。
     *
     * <p>两个消费方共用：顾客选游玩偏好时的复选框、管理员新增机台时的类型下拉框。
     * <b>不含已停用的类型</b> —— 停用就是「不再新增使用」。
     *
     * <p>与机台陈列有个容易混淆的区别要注意：<b>已停用类型下的机台照常陈列</b>
     * （组装时查的是全量类型），停用只影响「还能不能选它」。
     * 两条查询口径不同，见 {@link EquipmentTypeMapper} 的类注释。
     *
     * @return 启用中的类型列表
     */
    public BizResult<List<EquipmentTypeVo>> listSelectableTypes() {
        return BizResult.ok(equipmentTypeMapper.selectEnabledList().stream()
                .map(EquipmentTypeVo::from)
                .toList());
    }

    // ==================================================================
    // 写操作
    // ==================================================================

    /**
     * 新增机台。
     *
     * @param request 机台名称、编号、类型、位置、状况、排序与备注
     * @return 成功时返回新建的机台；失败时返回对应错误码
     */
    @Transactional
    public BizResult<DeviceVo> createDevice(DeviceRequest request) {
        Long storeId = storeMapper.selectCurrentId();
        if (storeId == null) {
            return BizResult.fail(ErrorCode.STORE_NOT_FOUND);
        }

        EquipmentType type = equipmentTypeMapper.selectTypeById(request.getTypeId());
        if (type == null) {
            return BizResult.fail(ErrorCode.EQUIPMENT_TYPE_NOT_FOUND);
        }

        String status = resolveStatus(request.getStatus(), DEFAULT_STATUS);
        if (status == null) {
            return BizResult.fail(ErrorCode.DEVICE_STATUS_INVALID);
        }

        String deviceNo = trimToNull(request.getDeviceNo());
        if (deviceNo != null && deviceMapper.countByDeviceNo(storeId, deviceNo, null) > 0) {
            return BizResult.fail(ErrorCode.DEVICE_NO_EXISTS);
        }

        Device device = new Device();
        device.setStoreId(storeId);
        device.setName(request.getName().trim());
        device.setDeviceNo(deviceNo);
        device.setTypeId(type.getId());
        device.setLocation(trimToNull(request.getLocation()));
        device.setStatus(status);
        device.setSort(request.getSort() == null ? 0 : request.getSort());
        device.setRemark(trimToNull(request.getRemark()));
        deviceMapper.insert(device);

        log.info("[设备] 新增机台 id={} 名称={} 编号={} 类型={}",
                device.getId(), device.getName(), device.getDeviceNo(), type.getCode());

        // 新机到店也是一件发生的事，值得在公告栏里留一条 ——
        // 顾客在陈列页上看到多了台机器，公告栏里能找到它的来历
        noticeService.publishAuto(NoticeSourceType.DEVICE, device.getId(),
                NoticeContents.deviceCreated(device.getName(), DeviceStatus.labelOf(status)));

        return BizResult.ok(DeviceVo.from(device, type));
    }

    /**
     * 修改机台，语义是全量替换（PUT）：字段传 null 即清空该项。
     *
     * <p><b>状况是这条规则唯一的例外</b>：{@code status} 传 null 时<b>保持原值</b>，
     * 不会把维护中的机台悄悄改回良好。之所以特殊对待，是因为「状况」表达的是
     * 设备当前的实际状态（管理员正盯着看的那个事实），而不是一个可以随便清空的描述字段；
     * 把它按「没传就是清空」处理，等于让一次普通的改名顺手改了设备状态。
     *
     * <p>要单独改状况请走 {@link #updateStatus}，那个接口不会碰到其他字段。
     *
     * @param id      机台 ID
     * @param request 新的机台字段
     * @return 成功时返回更新后的机台；失败时返回对应错误码
     */
    @Transactional
    public BizResult<DeviceVo> updateDevice(Long id, DeviceRequest request) {
        Device existing = deviceMapper.selectById(id);
        if (existing == null) {
            return BizResult.fail(ErrorCode.DEVICE_NOT_FOUND);
        }

        EquipmentType type = equipmentTypeMapper.selectTypeById(request.getTypeId());
        if (type == null) {
            return BizResult.fail(ErrorCode.EQUIPMENT_TYPE_NOT_FOUND);
        }

        // 先把旧状况取出来存好，后面同步公告要用。
        // ⚠️ 不能等到写完库再去读 existing.getStatus() —— 那是个隐式假设：
        // 它要求 existing 与库里的行「不共享状态」。真实 MyBatis 每次查询都构造新对象，
        // 这个假设成立；但假 Mapper 返回的是内存里的同一个对象，写完就变了。
        // 依赖这种差别写出来的代码，测试通过与否都不说明问题 —— 显式存一份最省事
        String oldStatus = existing.getStatus();

        String status = resolveStatus(request.getStatus(), oldStatus);
        if (status == null) {
            return BizResult.fail(ErrorCode.DEVICE_STATUS_INVALID);
        }

        String name = request.getName().trim();
        String deviceNo = trimToNull(request.getDeviceNo());
        if (deviceNo != null
                && deviceMapper.countByDeviceNo(existing.getStoreId(), deviceNo, id) > 0) {
            return BizResult.fail(ErrorCode.DEVICE_NO_EXISTS);
        }

        deviceMapper.updateDevice(id, name, deviceNo, type.getId(),
                trimToNull(request.getLocation()), status,
                request.getSort() == null ? 0 : request.getSort(),
                trimToNull(request.getRemark()));

        log.info("[设备] 修改机台 id={} 名称={} 状况={}", id, name, status);

        // ⚠️ 这一处钩子最容易漏，但走的人最多：从「编辑机台」对话框里改状况，
        // 走的就是本方法（status 非 null 时它同样生效，只是传 null 时保持原值）。
        // 只挂 updateStatus 会漏掉这条最常见的路径，而且不报任何错
        announceStatusChange(id, name, oldStatus, status);

        // 重新查一次而不是改内存对象：updatedAt 由数据库的 NOW() 写入，
        // 手工赋值会与库里的值差几毫秒
        Device updated = deviceMapper.selectById(id);
        return BizResult.ok(DeviceVo.from(updated, type));
    }

    /**
     * 只修改机台状况。
     *
     * <p>运营里最高频的动作：机器坏了点一下、修好了再点一下。
     * 单独一个接口而不是让前端走全量替换，理由见 {@link DeviceMapper#updateStatus}。
     *
     * <p>改成与当前相同的状况<b>不算错误</b>：重复点两下的结果与点一下相同，
     * 前端不必为此做额外的状态判断。
     *
     * @param id      机台 ID
     * @param request 新的状况
     * @return 成功时 data 为 null；失败时返回对应错误码
     */
    @Transactional
    public BizResult<Void> updateStatus(Long id, UpdateDeviceStatusRequest request) {
        String status = request.getStatus().trim();
        if (!DeviceStatus.isValid(status)) {
            return BizResult.fail(ErrorCode.DEVICE_STATUS_INVALID);
        }

        Device existing = deviceMapper.selectById(id);
        if (existing == null) {
            return BizResult.fail(ErrorCode.DEVICE_NOT_FOUND);
        }

        // 旧值先存好，理由同 updateDevice 里的注释
        String oldStatus = existing.getStatus();
        String deviceName = existing.getName();

        deviceMapper.updateStatus(id, status);
        log.info("[设备] 机台状况变更 id={} {} → {}", id, oldStatus, status);

        announceStatusChange(id, deviceName, oldStatus, status);
        return BizResult.ok(null);
    }

    /**
     * 删除机台（逻辑删除），用于机器退役、搬走。
     *
     * <p><b>退役走删除而不是加一个「已报废」状况</b>：一台机器一旦退役就不再陈列，
     * 与「暂时不能玩」是两回事 —— 混进状况枚举里，会让「店里还有几台能玩」
     * 这个数需要额外过滤才对，而且陈列页要专门记住跳过它。
     *
     * <p>逻辑删除后记录仍在库里，事后能查到「这台机器原来在哪个位置」。
     *
     * @param id 机台 ID
     * @return 成功时 data 为 null；机台不存在时返回 {@link ErrorCode#DEVICE_NOT_FOUND}
     */
    @Transactional
    public BizResult<Void> deleteDevice(Long id) {
        Device existing = deviceMapper.selectById(id);
        if (existing == null) {
            return BizResult.fail(ErrorCode.DEVICE_NOT_FOUND);
        }

        // deleteById 会被逻辑删除配置改写成 UPDATE ... SET deleted = 1
        deviceMapper.deleteById(id);

        // 退役也是一件发生的事：顾客会发现陈列页上少了一台机器，
        // 公告栏里该有个交代。这一处容易被漏 —— 删除看起来跟公告没关系
        noticeService.publishAuto(NoticeSourceType.DEVICE, id,
                NoticeContents.deviceRetired(existing.getName()));

        log.info("[设备] 删除机台 id={} 名称={} 编号={}",
                id, existing.getName(), existing.getDeviceNo());
        return BizResult.ok(null);
    }

    /**
     * 按名字找一台机台 —— <b>群里的「改状况」指令走这条</b>（{@code fw拍拍机 1 号维护中}）。
     *
     * <p>群里没有地方填机台 ID，管理员打出来的就是名字，所以名字必须能定位到唯一一台。
     * 机台名没有唯一键，因此结果有三种：<b>没有</b>（{@link ErrorCode#DEVICE_NOT_FOUND}）、
     * <b>唯一一台</b>（成功）、<b>多台同名</b>（{@link ErrorCode#DEVICE_NAME_AMBIGUOUS}）。
     * 多台时<b>刻意不挑一台返回</b> —— 调用方拿去改状况时「改错了哪台」在界面上看不出来，
     * 而拒绝执行只会让群里的回复说一句「有多台同名」，管理员一看就懂。
     *
     * <p>名字按<b>完全一致</b>匹配（解析器已 trim 过）。不做模糊匹配：
     * 群里打错半个字，改的就是另一台机器，且当场没人会发现。
     *
     * @param name 机台名，调用方须已去掉首尾空白
     * @return 命中唯一一台时成功；没有或多台同名时失败并给出对应错误码
     */
    public BizResult<DeviceVo> findByName(String name) {
        Long storeId = storeMapper.selectCurrentId();
        if (storeId == null) {
            return BizResult.fail(ErrorCode.STORE_NOT_FOUND);
        }
        List<Device> matched = deviceMapper.selectListByName(storeId, name);
        if (matched.isEmpty()) {
            return BizResult.fail(ErrorCode.DEVICE_NOT_FOUND);
        }
        if (matched.size() > 1) {
            return BizResult.fail(ErrorCode.DEVICE_NAME_AMBIGUOUS);
        }
        Device device = matched.get(0);
        return BizResult.ok(DeviceVo.from(device,
                equipmentTypeMapper.selectTypeById(device.getTypeId())));
    }

    // ==================================================================
    // 内部辅助
    // ==================================================================

    /**
     * 机台状况变化时，往首页公告栏记一条消息。
     *
     * <p><b>公告是一条消息，不是一份状态。</b> 所以这里做的是「记录刚才发生了什么」，
     * 而不是「让公告与当前状态保持一致」—— 后者会要求机台修好时把那条维护公告
     * 撤掉或改掉，而消息流里没有「撤销已经发生的事」这回事。
     * 机台修好只会<b>再产生一条</b>「由维护中转为良好」。
     *
     * <p><b>只在状况真的变了时才记</b>：管理员对着一台已经在维护中的机器再点一次
     * 「维护中」，什么都没发生，不该产生第二条消息。反复点几下就把公告栏刷满，
     * 会把真正要紧的信息挤下去。
     *
     * <p><b>本方法不抛异常</b>：公告是次要功能，机台状况是主要功能。
     * 公告写失败不该让管理员改不了机台状况 —— 具体的隔离范围见
     * {@code NoticeService#publishAuto} 的注释（它保护什么、不保护什么）。
     *
     * @param deviceId   机台 ID
     * @param deviceName 机台名称，用于拼公告标题
     * @param oldStatus  变更前的状况名，可为 null（新增机台时没有「之前」）
     * @param newStatus  变更后的状况名
     */
    private void announceStatusChange(Long deviceId, String deviceName,
                                      String oldStatus, String newStatus) {
        if (oldStatus != null && oldStatus.equals(newStatus)) {
            return;
        }
        noticeService.publishAuto(NoticeSourceType.DEVICE, deviceId,
                NoticeContents.deviceStatusChanged(deviceName,
                        DeviceStatus.labelOf(oldStatus), DeviceStatus.labelOf(newStatus)));
    }

    /**
     * 把机台按类型分组，组的顺序由类型的排序权重决定。
     *
     * @param devices 机台列表，调用方须已按展示顺序排好（本方法保持组内顺序）
     * @param types   全量类型列表（含已停用），调用方须已按排序权重排好
     * @return 分组结果；<b>空组不出现在结果里</b>（前端不必处理「有标题没内容」）
     */
    private static List<DeviceGroupVo> groupByType(List<Device> devices, List<EquipmentType> types) {
        Map<Long, EquipmentType> typeMap = types.stream()
                .collect(Collectors.toMap(EquipmentType::getId, Function.identity(),
                        (first, second) -> first, LinkedHashMap::new));

        // 按类型归拢机台。用 LinkedHashMap 保持机台的展示顺序
        Map<Long, List<Device>> devicesByType = new LinkedHashMap<>();
        List<Device> orphans = new ArrayList<>();
        for (Device device : devices) {
            if (typeMap.containsKey(device.getTypeId())) {
                devicesByType.computeIfAbsent(device.getTypeId(), key -> new ArrayList<>())
                        .add(device);
            } else {
                orphans.add(device);
            }
        }

        List<DeviceGroupVo> groups = new ArrayList<>();
        // 按类型顺序（types 已排好）输出，而不是按机台顺序 ——
        // 分组标题的先后由字典表的 sort 决定，运营调它就能调整整个陈列页的版面
        for (EquipmentType type : types) {
            List<Device> group = devicesByType.get(type.getId());
            if (group != null && !group.isEmpty()) {
                groups.add(groupOf(type.getCode(), type.getName(), group));
            }
        }

        if (!orphans.isEmpty()) {
            log.warn("[设备] 有 {} 台机台指向已不存在的类型，归入「{}」组陈列",
                    orphans.size(), ORPHAN_GROUP_NAME);
            groups.add(groupOf(null, ORPHAN_GROUP_NAME, orphans));
        }
        return groups;
    }

    /**
     * 组装一个分组。
     *
     * @param typeCode 类型代码，可为 null（兜底组）
     * @param typeName 类型名称
     * @param devices  组内机台，须已按展示顺序排好
     * @return 分组视图
     */
    private static DeviceGroupVo groupOf(String typeCode, String typeName, List<Device> devices) {
        DeviceGroupVo group = new DeviceGroupVo();
        group.setTypeCode(typeCode);
        group.setTypeName(typeName);
        group.setDevices(devices.stream().map(DeviceDisplayVo::from).toList());
        return group;
    }

    /**
     * 取「类型 ID → 类型」的映射，供列表组装时解析类型名。
     *
     * @return 映射；字典表为空时返回空映射
     */
    private Map<Long, EquipmentType> typeMap() {
        return equipmentTypeMapper.selectListAll().stream()
                .collect(Collectors.toMap(EquipmentType::getId, Function.identity(),
                        (first, second) -> first));
    }

    /**
     * 校验并归一状况取值。
     *
     * <p>调用方传了就用传的（先校验合法性），没传就退回 {@code fallback}。
     * 两个调用方给出的 fallback 不同，正是「状况」这条规则的关键：
     * 新增时退回「良好」，修改时退回<b>原值</b>（而不是把维护中改回良好）。
     *
     * @param requested 请求里的状况名，可为 null 或空白
     * @param fallback  未提供时采用的状况名
     * @return 可用的状况名；请求里的取值非法时返回 null（由调用方转成错误码）
     */
    private static String resolveStatus(String requested, String fallback) {
        String trimmed = trimToNull(requested);
        if (trimmed == null) {
            return fallback;
        }
        return DeviceStatus.isValid(trimmed) ? trimmed : null;
    }

    /**
     * 去除首尾空白，空串归一为 null。
     *
     * <p>统一成 null 而不是空串，是为了让「没填」在库里只有一种表示 ——
     * 否则 {@code ''} 与 {@code NULL} 混用，查「没有编号的机台」时得写两个条件。
     *
     * @param value 原始字符串，可为 null
     * @return 去空白后的字符串；空白串返回 null
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
