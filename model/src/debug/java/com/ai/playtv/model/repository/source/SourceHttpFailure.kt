package com.ai.playtv.model.repository.source

import java.io.IOException
import retrofit2.HttpException

/** HTTP status from a raw source response; do not retain the URL or response body. */
class SourceHttpFailure(val statusCode: Int) : IOException("HTTP $statusCode")

/** Both raw responses and typed GitHub API calls can report an HTTP status. */
fun Throwable.sourceHttpStatus(): Int? = when (this) {
    is SourceHttpFailure -> statusCode
    is HttpException -> code()
    else -> null
}
