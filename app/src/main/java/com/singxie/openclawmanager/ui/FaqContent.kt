package com.singxie.openclawmanager.ui

/**
 * 连接与 Token 常见问题文案（用于弹窗展示）。
 */
object FaqContent {

    const val TITLE = "连接常见问题"

    val sections: List<Pair<String, String>> = listOf(
        "openclaw.json中的重要配置：OpenClaw 版本差异（2026.2.26 前后）" to """
            从 v2026.2.26 起，Gateway 的局域网访问策略更严格（常见报错：origin not allowed），并且默认可能需要设备验证。
            
            【A】OpenClaw 版本 < 2026.2.26（通常需要设备验证/配对）
            • 第一次用本手机连接该 Gateway，可能需要在电脑端执行 devices approve（见下方「需要设备配对」）。
            
            【B】OpenClaw 版本 ≥ 2026.2.26（局域网 ws 连接建议配置）
            1) 让 Gateway 监听局域网：
               - gateway.bind: "lan"（或你的局域网 IP）
            2) 允许你的访问来源（非常关键：allowedOrigins 是 http/https，不是 ws/wss）：
               - gateway.controlUi.allowedOrigins 需要包含你连接时对应的 origin，例如：
                 "http://192.168.1.33:18789"
            
            3) （可选/不推荐）仅在完全可信的局域网内，为了省去设备 ID 验证，可关闭设备验证：
            
            {
              "gateway": {
                "bind": "lan",
                "controlUi": {
                  "allowedOrigins": [
                    "http://localhost:18789",
                    "http://127.0.0.1:18789",
                    "http://192.168.1.33:18789"
                  ],
                  "allowInsecureAuth": true,
                  "dangerouslyDisableDeviceAuth": true
                }
              }
            }
            
            注意事项：
            • 关闭设备验证（dangerouslyDisableDeviceAuth）会降低安全性：同网段内任何能拿到 Token/会话的人更容易接入，请务必只在可信局域网使用。
            • 改完配置后需要重启 Gateway 才会生效。
        """.trimIndent(),
        "手机如何连接电脑上的 Gateway？" to """
            • 电脑默认：Gateway 监听 127.0.0.1:18789，仅本机可连。
            • 手机要连同一台电脑：需让 Gateway 监听局域网。在电脑上打开 OpenClaw 配置文件（如 ~/.openclaw/openclaw.json），在 gateway 下设置 "bind": "0.0.0.0"（v2026.2.26以下） 或 "bind": "lan"，保存后重启 Gateway（如 openclaw gateway 或重启 OpenClaw 应用）。
            • 手机端填写：Gateway 地址填 ws://电脑局域网IP:18789（例如 ws://192.168.1.100:18789），端口一般为 18789。
        """.trimIndent(),
        "Gateway Token 从哪里获取？不填可以吗？" to """
            • 获取方式：在运行 Gateway 的电脑上，由管理员设置环境变量 OPENCLAW_GATEWAY_TOKEN，或启动 Gateway 时加参数 --token <令牌>。Token 由管理员自定，无固定格式，查看路径电脑网页端端Settings->Config->Gateway->Token。
            • 不填 Token：若电脑端未设置 OPENCLAW_GATEWAY_TOKEN 且未使用 --token，则本应用可不填 Token 直接连接（仅建议在可信局域网使用）。
        """.trimIndent(),
        "提示「需要设备配对」怎么办？" to """
            首次用本设备连接该 Gateway 时，需在电脑上批准一次：
            1. 在电脑终端执行：openclaw devices list
            2. 找到本设备对应的请求，执行：openclaw devices approve <请求ID>
            本应用在连接失败时会显示「配对请求 ID」，复制后在 approve 命令中使用即可。批准后再次点击「连接」。
        """.trimIndent(),
        "连接被拒绝或超时？" to """
            • 确认手机和电脑在同一 WiFi/局域网，或地址可访问。
            • 确认电脑上 Gateway 已启动（如 openclaw gateway）。
            • 确认 Gateway 已绑定到 0.0.0.0 或局域网 IP，而不是仅 127.0.0.1。
            • 如有防火墙，放行 18789 端口。
        """.trimIndent(),
        "发消息后电脑端没看到 agent 在跑、也没有回复？" to """
            • 看本应用对话页下方是否出现「发送结果: runId: xxx status: started」：若有，说明 Gateway 已收到消息；若显示「错误: …」，请按错误信息排查。
            • 在电脑浏览器打开 Control UI（http://127.0.0.1:18789/），在同一会话里发一条消息，看是否有回复。若浏览器里也没回复，多半是 Gateway/agent 配置问题（如默认 Pi 未配置或未启动）。
            • 用 adb logcat -s OpenClawGateway 可在电脑上查看本应用与 Gateway 的通信日志，确认是否收到 chat 事件。
        """.trimIndent(),
        "手机发的消息在浏览器 Control UI 里看不到？" to """
            本应用使用与浏览器相同的会话 key（agent:main:main），正常情况下手机和浏览器应看到同一条对话。若仍不一致，请确认 Gateway 版本并查看电脑端 OpenClaw 配置中 session.mainKey 是否为 "main"（默认）。若你自定义了 agent 或 mainKey，可能需在应用中另行配置会话 key。
        """.trimIndent()
    )
}
