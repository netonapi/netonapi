@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package logic

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.AF_UNSPEC
import platform.posix.NI_MAXHOST
import platform.posix.NI_NUMERICHOST
import platform.posix.SOCK_STREAM
import platform.posix.addrinfo
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.getnameinfo

private val posixResolver = NetGuard.HostResolver { host -> resolveHostAddresses(host) }

internal actual fun platformHostResolver(): NetGuard.HostResolver? = posixResolver

/**
 * getaddrinfo → 数字地址串。v4/v6 都要（只查 v4 会漏掉指向 `::1` 的域名）。
 *
 * 阻塞调用：只用于管理员写入渠道时的 SSRF 复查，不进中转热路径。
 * 解析失败（NXDOMAIN / DNS 不可达）返回空列表，由 [NetGuard.checkUpstreamUrl] fail-closed。
 */
private fun resolveHostAddresses(host: String): List<String> = memScoped {
    val hints = alloc<addrinfo>().apply {
        ai_family = AF_UNSPEC
        ai_socktype = SOCK_STREAM
    }
    val result = alloc<CPointerVar<addrinfo>>()
    if (getaddrinfo(host, null, hints.ptr, result.ptr) != 0) return@memScoped emptyList()
    try {
        val out = mutableListOf<String>()
        var node: CPointer<addrinfo>? = result.value
        while (node != null) {
            val addr = node.pointed.ai_addr
            if (addr != null) {
                val buf = allocArray<ByteVar>(NI_MAXHOST)
                // salen/servlen 是 socklen_t（Apple 与 glibc 上都是 UInt），字面量必须显式 convert
                val rc = getnameinfo(
                    addr, node.pointed.ai_addrlen.convert(), buf, NI_MAXHOST.convert(),
                    null, 0.convert(), NI_NUMERICHOST,
                )
                if (rc == 0) buf.toKString().takeIf { it.isNotEmpty() }?.let { out += it }
            }
            node = node.pointed.ai_next
        }
        out
    } finally {
        freeaddrinfo(result.value)
    }
}
