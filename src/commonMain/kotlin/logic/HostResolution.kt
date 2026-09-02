package logic

/**
 * 平台 DNS 解析器：SSRF 复查需要知道域名**指向哪里**，光看字面量看不出来。
 *
 * 返回 null 表示该平台没有可用实现。调用方必须显式记录降级，不得把「没有解析器」
 * 当成「地址已检查」——静默少一层校验比没有校验更难发现。
 */
internal expect fun platformHostResolver(): NetGuard.HostResolver?
