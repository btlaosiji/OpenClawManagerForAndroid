package com.singxie.openclawmanager.ui

/**
 * 将 Gateway 连接错误转为中文说明及解决提示。
 */
object ConnectErrorHelper {

    /**
     * @param message 服务端返回的 error.message
     * @param detailsCode 服务端返回的 error.details.code（若有）
     * @return Pair(中文简短描述, 解决提示，可为空)
     */
    fun toChinese(message: String?, detailsCode: String?): Pair<String, String?> {
        val msg = (message ?: "").trim().lowercase()
        val code = (detailsCode ?: "").trim()

        // Token / 认证类
        if (code.contains("AUTH_TOKEN", ignoreCase = true) ||
            msg.contains("token", ignoreCase = true) && (msg.contains("mismatch") || msg.contains("invalid") || msg.contains("missing"))
        ) {
            return "认证失败（Token 错误或未提供）" to """
                • 如何获取 Token：在运行 OpenClaw Gateway 的电脑上设置环境变量 OPENCLAW_GATEWAY_TOKEN，或启动时使用参数 --token <你的令牌>。
                • 若希望不填 Token 即可连接：请在电脑上不要设置 OPENCLAW_GATEWAY_TOKEN，也不要加 --token 启动。这样 Gateway 将允许无 Token 连接（仅建议在可信局域网内使用）。
            """.trimIndent()
        }

        if (msg.contains("pairing", ignoreCase = true) || code.contains("PAIRING", ignoreCase = true)) {
            return "需要设备配对" to """
                请在运行 Gateway 的电脑上执行：
                1. openclaw devices list
                2. openclaw devices approve <下方配对请求 ID>
                批准后再次点击「连接」。
            """.trimIndent()
        }

        if (msg.contains("device identity", ignoreCase = true) || msg.contains("device signature", ignoreCase = true)) {
            return "设备身份校验失败" to "请确认本应用为最新版本后重试。若仍失败，可在电脑端查看 Gateway 文档中的设备认证说明。"
        }

        if (msg.contains("connection") || msg.contains("refused") || msg.contains("failed") || msg.contains("network")) {
            return "连接失败（网络或地址不可达）" to """
                • 确认手机与电脑在同一局域网，或使用可访问的 Gateway 地址。
                • 电脑端 Gateway 若默认只监听 127.0.0.1，手机无法连接。请在电脑上修改 OpenClaw 配置，将 gateway.bind 设为 0.0.0.0 或本机局域网 IP，然后重启 Gateway。
            """.trimIndent()
        }

        // 默认：原文或简短翻译
        val brief = when {
            msg.isBlank() -> "连接失败"
            msg.contains("invalid") -> "请求无效"
            msg.contains("timeout") -> "连接超时"
            else -> message ?: "连接失败"
        }
        return brief to null
    }
}
