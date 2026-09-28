package com.kaede.uspace.common.result;

import lombok.Data;
import org.springframework.http.ResponseEntity;

/**
 * HTTP 统一返回体。
 *
 * <p>所有接口的响应都是这个结构，无论成功还是失败：
 *
 * <pre>
 *   { "code": 0, "message": null, "data": { ... } }        // 成功
 *   { "code": 40901, "message": "该用户名已被使用", "data": null }  // 失败
 * </pre>
 *
 * <p><b>「成功」用 code=0 而非 200</b>：HTTP 状态码已经在响应行里了，
 * body 里的 code 是业务码，成功就应当是 0（无错误），写 200 会让两套编码体系
 * 长得一样却含义不同，反而容易混淆。
 *
 * <p><b>HTTP 状态码用真实的，不是一律 200</b>。401 / 403 / 404 / 409 各归各位，
 * 好处有三：符合 RESTful 语义；前端 axios 拦截器可以靠状态码统一处理登录态失效；
 * 网关、监控与日志分析工具能正确识别错误率。若一律返回 200，
 * 上述三样全部失效，只剩前端一个个判 body。
 *
 * <p>Controller 的标准写法是把 Service 结果直接交给 {@link #of(BizResult)}，
 * 一行完成转换，不必每处手写状态码：
 *
 * <pre>
 *   &#64;PostMapping("/register")
 *   public ResponseEntity&lt;ApiResult&lt;UserProfileVo&gt;&gt; register(...) {
 *       return ApiResult.of(userService.register(request));
 *   }
 * </pre>
 *
 * @param <T> 业务数据的类型
 */
@Data
public class ApiResult<T> {

    /** 业务码。0 表示成功，非 0 见 {@link ErrorCode} */
    private int code;

    /** 提示文案。成功时为 null */
    private String message;

    /** 业务数据。失败时为 null */
    private T data;

    /**
     * 构造成功响应体。
     *
     * @param data 业务数据，可为 null
     * @param <T>  业务数据类型
     * @return 成功响应体
     */
    public static <T> ApiResult<T> ok(T data) {
        ApiResult<T> result = new ApiResult<>();
        result.setCode(0);
        result.setData(data);
        return result;
    }

    /**
     * 构造失败响应体。
     *
     * @param error   错误码，不能为空
     * @param message 提示文案，为空时回退到错误码的默认文案
     * @param <T>     业务数据类型
     * @return 失败响应体
     */
    public static <T> ApiResult<T> failure(ErrorCode error, String message) {
        ApiResult<T> result = new ApiResult<>();
        result.setCode(error.getCode());
        result.setMessage(message == null || message.isBlank() ? error.getMessage() : message);
        return result;
    }

    /**
     * 把 Service 层的结果转换成带 HTTP 状态码的完整响应。
     *
     * <p>这是统一返回的<b>唯一出口</b> —— Controller 不必自己判空、自己选状态码，
     * 也就不可能出现「同一个错误码在不同接口返回不同状态码」的漂移。
     *
     * @param result Service 层的业务结果
     * @param <T>    业务数据类型
     * @return 状态码与响应体都已确定的 ResponseEntity
     */
    public static <T> ResponseEntity<ApiResult<T>> of(BizResult<T> result) {
        if (result.isSuccess()) {
            return ResponseEntity.ok(ok(result.getData()));
        }
        ErrorCode error = result.getError() == null ? ErrorCode.INTERNAL_ERROR : result.getError();
        return ResponseEntity.status(error.getHttpStatus())
                .body(failure(error, result.resolveMessage()));
    }

    /**
     * 直接构造一个指定错误码的响应，供拦截器、过滤器等非 Controller 场景使用
     * （它们拿不到 {@link BizResult}，但同样要输出统一格式）。
     *
     * @param error 错误码
     * @param <T>   业务数据类型
     * @return 状态码与响应体都已确定的 ResponseEntity
     */
    public static <T> ResponseEntity<ApiResult<T>> of(ErrorCode error) {
        return of(error, null);
    }

    /**
     * 直接构造一个指定错误码与提示文案的响应，供全局异常处理器使用 ——
     * 它手上有异常消息这类具体上下文，比错误码的默认文案更有助于排查。
     *
     * @param error   错误码
     * @param message 提示文案，为空时回退到错误码的默认文案
     * @param <T>     业务数据类型
     * @return 状态码与响应体都已确定的 ResponseEntity
     */
    public static <T> ResponseEntity<ApiResult<T>> of(ErrorCode error, String message) {
        return ResponseEntity.status(error.getHttpStatus()).body(failure(error, message));
    }
}
