package logic

import neton.security.crypto.SecretBox

/**
 * 渠道 API Key 的静态加密。
 *
 * 主密钥来自环境变量 `NEWGATE_CHANNEL_KEY_ENC_KEY`（base64，解码后 32 字节 = AES-256）。
 * **未配置时不加密**——保持可运行并原样读写明文，便于先部署再启用；一旦配置，新写入即加密，
 * 存量明文仍可读（[SecretBox.open] 对非密文原样返回），无需停机迁移。
 *
 * 上游厂商 Key 一旦泄露即直接经济损失，生产部署应当配置此变量。
 */
object GatewayCrypto {

    const val ENV_KEY_NAME = "NEWGATE_CHANNEL_KEY_ENC_KEY"

    private val box: SecretBox? by lazy {
        val raw = neton.core.config.getEnv(ENV_KEY_NAME)?.takeIf { it.isNotBlank() } ?: return@lazy null
        runCatching { SecretBox.fromBase64(raw) }.getOrElse {
            // 配了但不合法：宁可启动即失败，也不要静默降级成明文存储
            error("$ENV_KEY_NAME is set but invalid: ${it.message}")
        }
    }

    val enabled: Boolean get() = box != null

    /** 入库前加密；未配置主密钥时原样返回。 */
    fun seal(apiKey: String): String = box?.seal(apiKey) ?: apiKey

    /** 使用前解密；非密文（存量明文）原样返回。 */
    fun open(stored: String): String = box?.open(stored) ?: stored
}
