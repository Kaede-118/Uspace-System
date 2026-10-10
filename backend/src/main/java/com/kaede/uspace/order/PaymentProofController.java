package com.kaede.uspace.order;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.security.UserPrincipal;
import com.kaede.uspace.common.trade.TradeSource;
import com.kaede.uspace.order.dto.ProofImageVo;
import com.kaede.uspace.order.dto.ProofSubmitRequest;
import com.kaede.uspace.order.dto.ProofSubmitVo;
import com.kaede.uspace.order.dto.RejectedProofVo;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 付款凭证接口（模块 8 的支付能力）。
 *
 * <p><b>四类收款共用这一个入口</b>（订单 / 包场 / 月卡 / 商品），靠请求体里的
 * {@code targetType} + {@code targetId} 指明是哪一笔 —— 这是
 * {@code POST /api/payments} 那个统一入口的同款设计，加一类收款不必新开端点。
 *
 * <p>提交之后会发生什么取决于收款类型（订单与商品当场落账、包场与月卡等复核），
 * 由返回体里的 {@code delivered} 说明 —— 前端照着展示即可，
 * <b>不要按 {@code targetType} 自己判一遍</b>，理由见 {@code ProofSubmitVo} 的类注释。
 *
 * <p>上传与提交是<b>两个接口</b>：上传只落盘、返回路径，提交才写库。
 * 用户传完图要核对流水号、可能还要改一遍再交，把两者合成一个的话，
 * 「传了图又放弃」会留下一条状态不明的凭证记录。
 */
@RestController
@RequestMapping("/api/payment-proofs")
@Validated
public class PaymentProofController {

    private final PaymentProofService paymentProofService;
    private final PaymentProofImageService imageService;

    /**
     * 构造器注入。
     *
     * @param paymentProofService 凭证的提交与复核
     * @param imageService        付款截图的上传
     */
    public PaymentProofController(PaymentProofService paymentProofService,
                                  PaymentProofImageService imageService) {
        this.paymentProofService = paymentProofService;
        this.imageService = imageService;
    }

    /**
     * 提交付款凭证。
     *
     * <p>需要登录 —— 凭证挂在具体某个人的某笔单子上，归属校验靠的就是登录身份。
     *
     * @param request 凭证内容（目标、截图路径、可选流水号）
     * @param me      当前登录用户
     * @return 提交结果，含「这笔是否已经结清」
     */
    @PostMapping
    public ResponseEntity<ApiResult<ProofSubmitVo>> submit(
            @Valid @RequestBody ProofSubmitRequest request,
            @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(paymentProofService.submit(me.id(), request, TradeSource.WEB));
    }

    /**
     * 上传付款截图。
     *
     * <p><b>只返回路径，不写数据库</b>：前端拿到 {@code proofUrl} 后随提交请求
     * 一起发回，那时才写进凭证表。三条理由（上传时还没有凭证可写、
     * 用户要核对后再提交、传错能反复换）见 {@code PaymentProofImageService}。
     *
     * <p>{@code required = false} 是刻意的：请求里没带 file 部分时，
     * 让它落到 Service 的校验里变成一个 400，而不是被 Spring 抛成异常。
     * 这条经验来自图片上传那一轮 —— 接口声明了
     * {@code consumes = MULTIPART_FORM_DATA_VALUE} 之后，一个不带请求体的
     * POST 在真实服务上会返回 500，而 MockMvc 的 {@code multipart()} 构造器
     * 永远造出合法请求，集成测试因此是全绿的。
     *
     * @param file 上传的图片
     * @return 截图的站内路径
     */
    @PostMapping(value = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResult<ProofImageVo>> uploadImage(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(imageService.upload(file, me.id()));
    }

    /**
     * 查我被驳回的付款凭证。
     *
     * <p><b>补的是「先交付后复核」的缺口</b>：订单与商品提交即落账，
     * 管理员事后驳回不回退订单状态，所以用户端看到的仍是「已支付」——
     * 首页那条提醒与订单详情的标记都读这个接口。
     *
     * <p>一条都没有时返回<b>空列表而不是 404</b>：首页每次加载都会调它，
     * 「没有待处理的事」是最常见的正常状态，不该在浏览器控制台留下一片红。
     *
     * @param me 当前登录用户
     * @return 被驳回的凭证列表，可能为空
     */
    @GetMapping("/rejected")
    public ResponseEntity<ApiResult<List<RejectedProofVo>>> listRejected(
            @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(paymentProofService.listRejected(me.id()));
    }
}
