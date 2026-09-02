package logic

/**
 * Windows/mingw：不提供解析器（显式降级，不是漏写）。
 *
 * WinSock 的 getaddrinfo 需要先 WSAStartup，而初始化时序由 HTTP server 引擎掌握、不在本模块手里；
 * 更关键的是 mingw 目标在本仓只能交叉编译、无法运行验证——一旦解析在运行期失败，
 * [NetGuard.checkUpstreamUrl] 的 fail-closed 会让 Windows 部署**完全无法新增域名渠道**，
 * 这比明确少一层检查更糟。
 *
 * 所以这里返回 null：字面量校验照常生效，调用方（ChannelController）会打一条降级告警，
 * 域名指向的最终兜底交给部署侧出网过滤（DEPLOY.md「SSRF」）。
 */
internal actual fun platformHostResolver(): NetGuard.HostResolver? = null
