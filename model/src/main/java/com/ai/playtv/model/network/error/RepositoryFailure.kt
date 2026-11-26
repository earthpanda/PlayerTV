package com.ai.playtv.model.network.error

/** 协议/业务失败的稳定出口；原始服务端文案不直接送 UI，requestId 不自动写日志。 */
open class RepositoryFailure(
    val kind: Kind,
    val businessCode: String? = null,
    val requestId: String? = null,
    cause: Exception? = null,
) : Exception("Repository operation failed", cause) {
    enum class Kind { BUSINESS, PROTOCOL }
}
