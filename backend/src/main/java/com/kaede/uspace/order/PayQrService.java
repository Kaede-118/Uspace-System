package com.kaede.uspace.order;

import com.kaede.uspace.common.config.UploadProperties;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.order.dto.AdminPayQrVo;
import com.kaede.uspace.order.dto.PayQrSaveRequest;
import com.kaede.uspace.order.dto.PayQrVo;
import com.kaede.uspace.order.entity.PayQr;
import com.kaede.uspace.order.mapper.PayQrMapper;
import com.kaede.uspace.space.entity.Store;
import com.kaede.uspace.space.mapper.StoreMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 收款码的查询与维护（模块 8 的支付能力）。
 *
 * <p>收款码是 12 月投产时唯一的收款方式：店内张贴（或收银台上展示）二维码，
 * 顾客扫码转账，回到页面传付款截图，管理员核对。三条线上支付通道
 * 都因资质门槛走不通，见 {@code docs/开发约定与设计说明.md} 第九章。
 *
 * <h3>两个查询口径不同，用错不会有报错</h3>
 * <ul>
 *   <li>{@link #listEnabled()} —— <b>收银台用</b>，只取启用中的码。
 *       把停用的也列出去，顾客会扫到一张不再使用的码</li>
 *   <li>{@link #listAll()} —— <b>后台用</b>，含停用的。
 *       只列启用中的话，管理员停用一张码之后就再也找不回它了</li>
 * </ul>
 * 这一对与 {@code DeviceService} 的陈列 / 可选类型是同一组对照。
 *
 * <h3>包级依赖方向</h3>
 * <p>本类读 {@code space} 包的 {@code StoreMapper} 取当前门店 ——
 * 方向是 {@code order → space}，与 {@code BookingPaymentTargetHandler}
 * 操作包场表是同一个方向，不会形成包级循环。
 */
@Slf4j
@Service
public class PayQrService {

    private final PayQrMapper payQrMapper;
    private final StoreMapper storeMapper;
    private final UploadProperties uploadProperties;

    /**
     * 构造器注入。
     *
     * @param payQrMapper      收款码数据访问
     * @param storeMapper      门店数据访问，用于取「当前门店」的 ID
     * @param uploadProperties 上传配置，用于校验图片路径前缀
     */
    public PayQrService(PayQrMapper payQrMapper,
                        StoreMapper storeMapper,
                        UploadProperties uploadProperties) {
        this.payQrMapper = payQrMapper;
        this.storeMapper = storeMapper;
        this.uploadProperties = uploadProperties;
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 取当前门店启用中的收款码，供收银台展示。
     *
     * <p>门店不存在或一张码都没配时返回<b>空列表</b>而非 null，调用方不必判空。
     * 空列表是一种真实的运营状态（刚部署完还没配码），前端要能处理它 ——
     * 那时收银台应当提示「请联系管理员配置收款方式」，而不是白屏。
     *
     * @return 启用中的收款码，按 sort 升序
     */
    public List<PayQrVo> listEnabled() {
        Long storeId = currentStoreId();
        if (storeId == null) {
            return List.of();
        }
        return payQrMapper.selectEnabledByStore(storeId).stream()
                .map(PayQrVo::from)
                .toList();
    }

    /**
     * 取当前门店的全部收款码（含停用的），供后台列表。
     *
     * @return 全部收款码，按 sort 升序
     */
    public List<AdminPayQrVo> listAll() {
        Long storeId = currentStoreId();
        if (storeId == null) {
            return List.of();
        }
        return payQrMapper.selectAllByStore(storeId).stream()
                .map(AdminPayQrVo::from)
                .toList();
    }

    // ==================================================================
    // 维护
    // ==================================================================

    /**
     * 新增一张收款码。
     *
     * @param request 收款码内容
     * @return 成功时返回新增后的视图；渠道非法、图片路径非法或门店不存在时返回对应失败码
     */
    @Transactional
    public BizResult<AdminPayQrVo> create(PayQrSaveRequest request) {
        BizResult<Void> valid = validate(request);
        if (!valid.isSuccess()) {
            return BizResult.fail(valid.getError(), valid.getMessage());
        }
        Long storeId = currentStoreId();
        if (storeId == null) {
            return BizResult.fail(ErrorCode.STORE_NOT_FOUND);
        }

        PayQr entity = new PayQr();
        entity.setStoreId(storeId);
        entity.setChannel(request.getChannel());
        entity.setName(request.getName().trim());
        entity.setImageUrl(request.getImageUrl().trim());
        entity.setEnabled(request.getEnabled());
        entity.setSort(request.getSort() == null ? 0 : request.getSort());
        payQrMapper.insert(entity);

        log.info("[收款码] 已新增 id={} 渠道={} 名称={} 启用={}",
                entity.getId(), entity.getChannel(), entity.getName(), entity.getEnabled());
        // 重新查一次而不是直接用入参里的对象：created_at / updated_at 由框架在插入时填充，
        // 入参对象上还没有值，直接返回会让前端拿到两个 null
        return BizResult.ok(AdminPayQrVo.from(payQrMapper.selectById(entity.getId())));
    }

    /**
     * 修改一张收款码（全量替换）。
     *
     * <p><b>{@code enabled} 也在这里一起改</b>，不像机台状况那样单开一个接口：
     * 启停本来就是这张码编辑表单里的一个字段，而机台状况单开接口的理由
     *（「管理员正盯着看的事实，不能被一次普通改名顺手改掉」）在这里不成立。
     *
     * @param id      收款码 ID
     * @param request 新的内容
     * @return 成功时返回修改后的视图；记录不存在时返回 {@link ErrorCode#PAY_QR_NOT_FOUND}
     */
    @Transactional
    public BizResult<AdminPayQrVo> update(Long id, PayQrSaveRequest request) {
        if (payQrMapper.selectById(id) == null) {
            return BizResult.fail(ErrorCode.PAY_QR_NOT_FOUND);
        }
        BizResult<Void> valid = validate(request);
        if (!valid.isSuccess()) {
            return BizResult.fail(valid.getError(), valid.getMessage());
        }

        int affected = payQrMapper.updateFields(id,
                request.getChannel(),
                request.getName().trim(),
                request.getImageUrl().trim(),
                request.getEnabled(),
                request.getSort() == null ? 0 : request.getSort());
        if (affected == 0) {
            // 上面查过一次还活着，这里却是 0 —— 只可能是这一瞬间被并发删掉了
            return BizResult.fail(ErrorCode.PAY_QR_NOT_FOUND);
        }

        log.info("[收款码] 已修改 id={} 渠道={} 名称={} 启用={}",
                id, request.getChannel(), request.getName(), request.getEnabled());
        return BizResult.ok(AdminPayQrVo.from(payQrMapper.selectById(id)));
    }

    /**
     * 删除一张收款码（逻辑删除）。
     *
     * <p>走逻辑删除而不是物理删除：历史付款凭证上记着 {@code pay_qr_id}，
     * 物理删掉之后那个号就查不出对应的是哪张码了，对账时说不清
     * 「这笔钱当时扫的是哪个账号」。
     *
     * <p><b>不拦「删掉最后一张启用的码」</b>：那确实是让顾客付不了款的配置，
     * 但拦它要在这里做一次业务判断、并在前端给出对应提示，而管理员
     * 多半正在配一张新的 —— 拦下来反而挡了正常操作。真要提醒的话，
     * 该提醒的是「店里一张启用的码都没有」，那是收银台那一侧的事。
     *
     * @param id 收款码 ID
     * @return 成功时返回空数据；记录不存在时返回 {@link ErrorCode#PAY_QR_NOT_FOUND}
     */
    @Transactional
    public BizResult<Void> delete(Long id) {
        PayQr existing = payQrMapper.selectById(id);
        if (existing == null) {
            return BizResult.fail(ErrorCode.PAY_QR_NOT_FOUND);
        }
        payQrMapper.deleteById(id);

        log.info("[收款码] 已删除 id={} 名称={}", id, existing.getName());
        return BizResult.ok(null);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /**
     * 取当前门店 ID。
     *
     * <p>单门店运营下即表里唯一的那一条。返回 null 表示门店还没初始化 ——
     * 那时收款码无从挂靠，调用方应当返回 {@link ErrorCode#STORE_NOT_FOUND}。
     *
     * @return 门店 ID；门店不存在时返回 null
     */
    private Long currentStoreId() {
        Store store = storeMapper.selectCurrent();
        return store == null ? null : store.getId();
    }

    /**
     * 校验请求体里那两项 Service 才有条件校验的字段。
     *
     * <p>其余字段（非空、长度）由 {@code PayQrSaveRequest} 上的注解在进 Controller 时挡掉，
     * 不必在这里重复。
     *
     * @param request 待校验的请求体
     * @return 合法时返回成功；否则返回带中文提示的失败
     */
    private BizResult<Void> validate(PayQrSaveRequest request) {
        if (!PayQrChannel.isValid(request.getChannel())) {
            return BizResult.fail(ErrorCode.PARAM_INVALID, "收款渠道取值不合法");
        }
        if (!isPayQrImageUrl(request.getImageUrl())) {
            return BizResult.fail(ErrorCode.PARAM_INVALID,
                    "收款码图片路径不合法，请通过上传接口重新上传");
        }
        return BizResult.ok(null);
    }

    /**
     * 判断一个路径是不是本服务上传的收款码图片。
     *
     * <p><b>这道校验挡的是「用户可控的字符串直接落库」</b>：请求体是客户端给的，
     * 不校验的话可以填一个外链 —— 顾客的浏览器会去加载别人的服务器，
     * 等于把「谁在什么时候付款」泄露给第三方；也可以填
     * {@code /uploads/avatar/xxx.jpg} 把别人的头像当成收款码展示出来。
     *
     * <p>三条检查缺一不可：
     * <ol>
     *   <li>以 {@code {url-prefix}/payqr/} 开头 —— 前缀来自配置，与
     *       {@code PayQrImageService} 落盘时用的是同一个值</li>
     *   <li>剩下那段不含路径分隔符 —— 收款码文件名就是「UUID.扩展名」，
     *       出现分隔符说明有人想指向别的子目录</li>
     *   <li>不含 {@code ..} —— 字符串前缀挡不住 {@code /uploads/payqr/../avatar/x.png}，
     *       与 {@code ImageStorage.deleteByUrl} 上那条注释是同一条纪律</li>
     * </ol>
     *
     * @param url 待校验的图片路径
     * @return 是本服务上传的收款码图片返回 true
     */
    private boolean isPayQrImageUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String prefix = uploadProperties.getUrlPrefix() + "/" + PayQrImageService.KIND_PAY_QR + "/";
        if (!url.startsWith(prefix)) {
            return false;
        }
        String fileName = url.substring(prefix.length());
        return !fileName.contains("/") && !fileName.contains("\\") && !fileName.contains("..");
    }
}
