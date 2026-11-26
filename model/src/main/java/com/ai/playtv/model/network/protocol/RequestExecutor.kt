package com.ai.playtv.model.network.protocol

import com.ai.playtv.model.network.error.RepositoryFailure
import com.ai.playtv.support.network.error.requestResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import retrofit2.Response

/**
 * Repository 共用的执行边界。端点/包络/成功码由 convert 明确提供，不能在此猜测 code/data。
 * 已知技术失败和 RepositoryFailure 转 Result；取消与程序缺陷继续抛出，不使用 runCatching。
 */
class RequestExecutor {
    suspend fun <D, T> execute(request: suspend () -> D, convert: (D) -> T): Result<T> = try {
        requestResult {
            val dto = request()
            currentCoroutineContext().ensureActive()
            convert(dto)
        }
    } catch (failure: RepositoryFailure) {
        Result.failure(failure)
    }

    /** 需要状态码/204 的接口使用 Response；convert 显式决定空 body 是否有效。JSON DTO 专用，非流式下载。 */
    suspend fun <D, T> executeResponse(
        request: suspend () -> Response<D>,
        convert: (body: D?, statusCode: Int) -> T,
    ): Result<T> = execute(request) { response ->
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            throw retrofit2.HttpException(response)
        }
        convert(response.body(), response.code())
    }
}
