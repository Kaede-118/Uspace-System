/**
 * 用户接口（模块 1）。
 *
 * <p>路径前缀 {@code /api/user}。注册匿名，其余需登录。
 */
import http from './http'
import { uploadImage } from './upload'

/**
 * 注册。
 *
 * <p>⚠️ <b>注册接口不返回 token</b> —— 模块 1 不依赖模块 2，前端注册成功后
 * 需要再调一次登录。这是刻意的，不是遗漏。
 *
 * @param {object} data 注册信息
 * @param {string} data.username   用户名，注册校验限死 {@code [a-zA-Z0-9_]{3,20}}
 * @param {string} data.password   明文密码
 * @param {string} [data.nickname] 昵称
 * @param {string} [data.phone]    手机号
 * @param {string} [data.qq]       QQ 号（模块 11 靠它定位用户）
 * @param {string} [data.preference] 游玩偏好，逗号分隔的类型 code
 * @returns {Promise} 成功时 data 里是新建的用户资料
 */
export function register(data) {
  return http.post('/api/user/register', data)
}

/**
 * 取一个 QQ 验证码。
 *
 * <p>返回体里有两个东西，<b>职责完全不同</b>：
 * - `code` —— 给用户，复制了发到 QQ 群里。它不是凭证（群里所有人都看得见）
 * - `challengeId` —— **是**凭证，留在页面里。轮询状态与提交注册都要带上它
 *
 * <p>少了 challengeId 会出两个洞：轮询只能拿 QQ 去问（等于给所有人一个
 * 「查这个 QQ 注册没有」的探测器），以及群里任何一个看到验证码的人
 * 都能抢先用那个 QQ 注册。
 *
 * @param {string} qq 要验证的 QQ 号
 * @returns {Promise<{data:{challengeId, code, expiresAt, ttlSeconds}}>}
 */
export function issueQqVerify(qq) {
  return http.post('/api/user/qq-verify', { qq })
}

/**
 * 查 QQ 验证状态（注册页轮询用）。
 *
 * <p>⚠️ **是 POST 不是 GET**，虽然它只读。因为它要带 challengeId，
 * 而那是个能换取账号绑定的凭证 —— 放进 URL 查询参数会落进访问日志、
 * 浏览器历史与反向代理日志，看到日志的人就能抢先注册。
 *
 * @param {string} qq          用户在页面里填的 QQ
 * @param {string} challengeId 签发时给出的凭证
 * @returns {Promise<{data:{verified, expiresAt}}>} verified 是**布尔**，
 *          不是字符串状态 —— 后端刻意如此，写错一个字母就永远不相等那种坑
 */
export function getQqVerifyStatus(qq, challengeId) {
  return http.post('/api/user/qq-verify/status', { qq, challengeId })
}

/**
 * 查自己的资料。
 *
 * @returns {Promise<{data:{id, username, nickname, phone, qq, preference, avatar, banner, role, status, totalPaid, orderPaid, cardPaid}}>}
 *          路径里不出现用户 ID，身份一律从凭证取
 */
export function getProfile() {
  return http.get('/api/user/me')
}

/**
 * 改自己的资料。
 *
 * <p>⚠️ <b>这是「全量替换」语义：传 {@code null} 表示清空该项</b>。
 * 页面必须先把当前值全部回填、提交时一起带上 ——
 * 只提交改动的字段，会把手机号、QQ 号一起清掉，而且不报任何错。
 *
 * <p>⚠️ <b>头像与背景图不走这个接口</b>（它们各有独立的上传端点）：
 * 混进来的话，上面那条「只提交改动字段」的后果就变成「把头像清空」。
 *
 * @param {object} data 资料
 * @param {string} [data.nickname]   昵称，传 null 清空
 * @param {string} [data.phone]      手机号，传 null 清空
 * @param {string} [data.qq]         QQ 号，传 null 清空
 * @param {string} [data.preference] 游玩偏好，传 null 清空
 * @returns {Promise} 成功时 data 里是更新后的完整资料
 */
export function updateProfile(data) {
  return http.put('/api/user/me', data)
}

/**
 * 改密码。
 *
 * <p>改密成功后服务端会升 {@code token_version}，<b>所有旧凭证立即失效</b> ——
 * 包括当前这一个。前端拿到成功响应后应当清登录态并跳登录页。
 *
 * @param {string} oldPassword 原密码
 * @param {string} newPassword 新密码
 * @returns {Promise}
 */
export function changePassword(oldPassword, newPassword) {
  return http.put('/api/user/me/password', { oldPassword, newPassword })
}

/**
 * 上传头像（multipart）。
 *
 * @param {File} file 图片文件。后端按【文件头】判类型（JPG / PNG / WebP），
 *                    不看 Content-Type；上限 2MB
 * @returns {Promise} 成功时 data 是完整的用户资料（前端一行 setUser 即可）
 */
export function uploadAvatar(file) {
  return uploadImage('/api/user/me/avatar', file)
}

/**
 * 上传背景图（multipart）。
 *
 * <p>⚠️ 传进来的图会先被 {@code utils/image.js} 压成 3:1 的横长条
 *（与 UserCard 素材区比例一致）—— 卡片上的 {@code object-fit: cover}
 * 因此等于原样铺满、不会再裁第二次。比 3:1 更宽的素材（如 6:1 的舞萌姓名框）
 * 按「保留右半」裁切（2026-10-03 定）。后端不做宽高比校验。
 *
 * @param {File} file 图片文件，任意比例（上传前会裁成 3:1）
 * @returns {Promise} 成功时 data 是完整的用户资料
 */
export function uploadBanner(file) {
  return uploadImage('/api/user/me/banner', file)
}
