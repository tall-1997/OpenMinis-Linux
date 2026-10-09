package com.openminis.app.harness.result

/**
 * Adapted from taixu AppResult / AppError / ErrorCode (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 *
 * 结果类型三件套：把可预期的失败（IO/越界/超限）编码进返回值，
 * 而不是抛异常穿透到 LLM 循环。
 */

data class AppError(
    val code: ErrorCode,
    val message: String,
    val cause: Throwable? = null,
)

enum class ErrorCode {
    UNKNOWN,
    IO,
    NETWORK,
    DOWNLOAD,
    CHECKSUM,
    RUNTIME_NOT_INITIALIZED,
    UNSUPPORTED_ARCHITECTURE,
    INSUFFICIENT_STORAGE,
    INSTALLATION_FAILED,
    DATABASE,
    SECURITY,
}

sealed class AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>()
    data class Failure(val error: AppError) : AppResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun <R> map(transform: (T) -> R): AppResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }

    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Failure -> null
    }

    fun errorOrNull(): AppError? = when (this) {
        is Success -> null
        is Failure -> error
    }
}
