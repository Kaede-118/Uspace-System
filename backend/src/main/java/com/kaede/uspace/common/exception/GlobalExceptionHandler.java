package com.kaede.uspace.common.exception;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.result.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * <p>把各类异常统一翻译成 {@link ApiResult}，保证<b>任何出口的响应体结构都一致</b> ——
 * 包括 401、404、500。前端因此可以只写一次统一的错误处理，
 * 不必为「万一某个接口挂了返回的是 Spring 默认的 HTML 错误页」做额外兼容。
 *
 * <p><b>它只管异常，不管业务失败。</b>业务失败走 {@code BizResult} 返回值，
 * 在 Controller 里被 {@link ApiResult#of} 转换掉，不会流到这里来。
 * 两者分工明确：异常 = 调用方式不对或系统出错，返回值 = 业务规则不接受。
 *
 * <p>新增异常类型时请一并想清楚两件事：该给什么错误码（决定 HTTP 状态码），
 * 以及<b>该不该把异常消息原样返回给前端</b>。含 SQL、表名、路径的消息绝不能外泄。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理参数错误。
     *
     * <p>这是全项目最常抛出的异常类型（Service 层用它拒绝非法入参），
     * 异常消息是给开发者看的，同时也是给用户看的提示，因此两边都合适。
     *
     * @param e 参数异常
     * @return 400 响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResult<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("[异常] 参数错误：{}", e.getMessage());
        return ApiResult.of(ErrorCode.PARAM_INVALID, e.getMessage());
    }

    /**
     * 处理请求体字段校验失败（{@code @Valid} 标注的请求对象）。
     *
     * <p>把各字段的错误消息拼成一句，用户一次就能看到全部问题，
     * 而不是改一个字段提交一次、再被告知下一个字段也不对。
     *
     * @param e 校验失败异常
     * @return 400 响应，消息为各字段错误的拼接
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Void>> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        log.warn("[异常] 请求体校验失败：{}", detail);
        return ApiResult.of(ErrorCode.PARAM_INVALID, detail);
    }

    /**
     * 处理路径参数、查询参数的校验失败
     * （Controller 类上标了 {@code @Validated} 时才会触发，与请求体校验是两套机制）。
     *
     * @param e 约束违反异常
     * @return 400 响应
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResult<Void>> handleConstraintViolation(ConstraintViolationException e) {
        log.warn("[异常] 参数校验失败：{}", e.getMessage());
        return ApiResult.of(ErrorCode.PARAM_INVALID, "请求参数不合法");
    }

    /**
     * 处理请求体无法解析：JSON 格式错误、字段类型对不上、缺少请求体等。
     *
     * <p>不把原始消息返回给前端 —— 它包含类名与字段路径，属于内部结构信息。
     *
     * @param e 消息不可读异常
     * @return 400 响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResult<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("[异常] 请求体解析失败：{}", e.getMessage());
        return ApiResult.of(ErrorCode.PARAM_INVALID, "请求体格式不正确");
    }

    /**
     * 处理查询参数、路径参数的类型转换失败，例如给一个本应是枚举的参数传了不存在的取值。
     *
     * <p><b>为什么必须单独处理</b>：这类异常不是 {@code IllegalArgumentException} 的子类，
     * 落到兜底分支会返回 500 —— 而它明明是「请求发得不对」。
     * 归到 500 会让监控里混进大量本可避免的「服务端错误」，
     * 前端也会拿到一个含义完全不同的错误码，去做重试而不是提示用户改参数。
     *
     * <p>消息里带上参数名（用户自己能对照着改），但不带期望的类型 ——
     * 那属于内部结构信息。
     *
     * @param e 类型不匹配异常
     * @return 400 响应
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResult<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("[异常] 参数类型不匹配：{}", e.getMessage());
        return ApiResult.of(ErrorCode.PARAM_INVALID, "参数 " + e.getName() + " 取值不合法");
    }

    /**
     * 处理请求方法不支持，例如对着只接受 POST 的接口发了 GET。
     *
     * <p>严格说该用 405，但错误码表里没有单列它，归入参数类问题
     * （语义上确实是「请求发得不对」）。将来若有需要再补 405 的码。
     *
     * @param e 方法不支持异常
     * @return 400 响应
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResult<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("[异常] 请求方法不支持：{}", e.getMessage());
        return ApiResult.of(ErrorCode.PARAM_INVALID, "请求方法不正确");
    }

    /**
     * 处理数据库唯一约束冲突。
     *
     * <p>正常情况下应用层会先查重并给出友好提示，走到这里说明是<b>并发</b> ——
     * 两个请求同时通过了查重，其中一个被唯一索引拦下。
     * 这正是唯一索引存在的意义：应用层查重是体验优化，索引才是最终防线。
     *
     * <p>按约束名区分具体是哪个字段冲突，让用户得到准确的提示。
     * 解析约束名确实依赖数据库驱动返回的错误消息格式，比较脆弱 ——
     * 但它是兜底路径，解析不出来时退化为通用的「操作冲突」，不影响正确性。
     *
     * @param e 唯一约束冲突异常
     * @return 409 响应
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiResult<Void>> handleDuplicateKey(DuplicateKeyException e) {
        ErrorCode error = resolveDuplicateKeyError(e.getMessage());
        log.warn("[异常] 唯一约束冲突，判定为 {}：{}", error, e.getMessage());
        return ApiResult.of(error);
    }

    /**
     * 处理未匹配到任何接口或静态资源的路径。
     *
     * <p>默认情况下 Spring Boot 会把这种情况兜成 500，让「用户敲错地址」
     * 看起来像「服务器挂了」，监控里的错误率也会被污染。显式处理成 404。
     *
     * <p>刻意不打日志：扫描器与爬虫会持续制造这类请求，记下来只会淹没真正的告警。
     *
     * @param e 资源未找到异常
     * @return 404 响应
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResult<Void>> handleNoResource(NoResourceFoundException e) {
        return ApiResult.of(ErrorCode.NOT_FOUND);
    }

    /**
     * 处理方法级权限拒绝：{@code @PreAuthorize} 校验不通过。
     *
     * <p><b>为什么这里需要单独处理，而不是交给 Security 的 {@code AccessDeniedHandler}</b>：
     * 那个出口管的是<b>过滤器链</b>中抛出的授权异常；而 {@code @PreAuthorize}
     * 的检查发生在 DispatcherServlet 内部的方法调用阶段，
     * 异常在冒泡出 Servlet 之前就被本类先行捕获了。
     *
     * <p>不加这段的后果是它落到最后的兜底分支，变成 500「服务异常」——
     * 既是错误的语义（权限不足不是服务端故障），也会污染错误率指标，
     * 让真正的服务端问题淹没在大量「普通用户访问了后台接口」里。
     *
     * @param e 授权拒绝异常
     * @return 403 响应
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResult<Void>> handleAccessDenied(AccessDeniedException e) {
        log.warn("[异常] 权限不足：{}", e.getMessage());
        return ApiResult.of(ErrorCode.FORBIDDEN);
    }

    /**
     * 处理上传文件超过大小上限。
     *
     * <p><b>不加这个分支的后果</b>：上传一张 5MB 的图会落到最后的兜底分支，
     * 返回 500「服务异常，请稍后重试」—— 而真正的事实是「你的图太大了」。
     * 用户的可行动作是换张小图或压缩一下，不是「稍后重试」。
     * <b>让用户去重试一件永远不会成功的事，比不提示更糟。</b>
     *
     * <p>本异常由 Spring 在处理 multipart 时抛出（对应
     * {@code spring.servlet.multipart.max-file-size}），
     * 走不到 Service 层那道同样的大小的校验 —— 所以两条路都得有归宿：
     * 这条负责「超了 Spring 的闸门」，Service 里那条负责「超了业务配置的上限」。
     *
     * <p>日志用 warn 而非 error：这是用户操作不当，不是服务端故障。
     *
     * @param e 上传超限异常
     * @return 413 响应
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResult<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("[异常] 上传文件超过大小上限：{}", e.getMessage());
        return ApiResult.of(ErrorCode.UPLOAD_FILE_TOO_LARGE);
    }

    /**
     * 处理「上传请求本身就不合法」：压根不是 multipart 请求，或 multipart 报文残缺。
     *
     * <p><b>两个异常类型合在一处，是因为它们描述的是同一件事</b> ——
     * 调用方没按「以 {@code multipart/form-data} 上传」这个约定来。区别只在
     * Spring 判定得早还是晚：
     * <ul>
     *   <li>{@link HttpMediaTypeNotSupportedException} —— 接口上声明了
     *       {@code consumes = MULTIPART_FORM_DATA_VALUE}，请求的 {@code Content-Type}
     *       不是 multipart（比如一个没有任何请求体的 POST），映射阶段就被挡下</li>
     *   <li>{@link MultipartException} —— {@code Content-Type} 声称是 multipart，
     *       但报文本身残缺（boundary 对不上、缺少结束标记）</li>
     * </ul>
     *
     * <p><b>这条分支是实测发现的</b>：不加它，一个不带请求体的 POST 会落到兜底分支
     * 返回 500「服务异常，请稍后重试」。而真机实测之前它<b>骗过了集成测试</b> ——
     * MockMvc 的 {@code multipart()} 构造器永远会造出一个合法的 multipart 请求，
     * 走不到「压根不是 multipart」这条路上。所以补这条分支时，
     * 集成测试里也补了一条用普通 {@code post()} 发起的用例。
     *
     * <p>严格说第一个该用 415，但错误码表里没有单列它，归入参数类问题
     * （与 {@link #handleMethodNotSupported} 对 405 的处理同一套做法）。
     *
     * <p>注意它<b>不会抢走</b> {@code MaxUploadSizeExceededException} 的分支：
     * 那个异常是 {@code MultipartException} 的子类，Spring 按「最贴近的父类」选处理器。
     *
     * @param e 上传请求异常
     * @return 400 响应
     */
    @ExceptionHandler({HttpMediaTypeNotSupportedException.class, MultipartException.class})
    public ResponseEntity<ApiResult<Void>> handleBadUploadRequest(Exception e) {
        log.warn("[异常] 文件上传请求不合法：{}", e.getMessage());
        return ApiResult.of(ErrorCode.PARAM_INVALID, "请以 multipart/form-data 方式上传文件");
    }

    /**
     * 兜底：任何未被上面处理的异常。
     *
     * <p>这是全项目唯一使用 {@code error} 级别的地方 —— 走到这里意味着代码有 bug，
     * 而不是用户操作不当，运维上必须能靠日志级别把它与参数错误区分开。
     *
     * <p>对外只给一句笼统的提示，堆栈只进服务端日志：
     * 异常消息里常含表名、SQL 片段与文件路径，原样返回等于免费送出内部情报。
     *
     * @param e 未预期的异常
     * @return 500 响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleUnexpected(Exception e) {
        log.error("[异常] 未预期的服务端异常", e);
        return ApiResult.of(ErrorCode.INTERNAL_ERROR);
    }

    /**
     * 从唯一约束冲突的原始消息中判断是哪个业务字段重复。
     *
     * @param rawMessage 数据库驱动返回的原始错误消息
     * @return 对应的错误码；无法判断时返回通用的业务冲突码
     */
    private static ErrorCode resolveDuplicateKeyError(String rawMessage) {
        if (rawMessage == null) {
            return ErrorCode.BUSINESS_REJECTED;
        }
        if (rawMessage.contains("uk_username")) {
            return ErrorCode.USERNAME_EXISTS;
        }
        if (rawMessage.contains("uk_qq")) {
            return ErrorCode.QQ_ALREADY_BOUND;
        }
        return ErrorCode.BUSINESS_REJECTED;
    }
}
