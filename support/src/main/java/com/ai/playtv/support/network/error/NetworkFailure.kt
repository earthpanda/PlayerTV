package com.ai.playtv.support.network.error

import com.google.gson.JsonParseException
import com.google.gson.stream.MalformedJsonException
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.io.InterruptedIOException

/** 技术错误，不含业务成功码。cause 仅用于诊断，不直接展示给用户。 */
sealed class NetworkFailure(message: String, cause: Throwable?) : Exception(message, cause) {
    class Http(val statusCode: Int) : NetworkFailure("HTTP failure", null)
    class Timeout(cause: Exception) : NetworkFailure("Network timeout", cause)
    class Transport(cause: Exception) : NetworkFailure("Transport failure", cause)
    class Parsing(cause: Exception) : NetworkFailure("JSON parsing failure", cause)
}

/** Repository 的外层边界；只归类已知 SDK 错误，程序错误继续抛出，协程取消永远重抛。 */
suspend fun <T> requestResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (exception: CancellationException) {
    throw exception
} catch (exception: HttpException) {
    exception.response()?.errorBody()?.close()
    Result.failure(NetworkFailure.Http(exception.code()))
} catch (exception: SocketTimeoutException) {
    Result.failure(NetworkFailure.Timeout(exception))
} catch (exception: JsonParseException) {
    Result.failure(NetworkFailure.Parsing(exception))
} catch (exception: MalformedJsonException) {
    Result.failure(NetworkFailure.Parsing(exception))
} catch (exception: InterruptedIOException) {
    Result.failure(NetworkFailure.Timeout(exception))
} catch (exception: IOException) {
    Result.failure(NetworkFailure.Transport(exception))
}
