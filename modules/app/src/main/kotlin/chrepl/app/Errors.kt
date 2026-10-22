package chrepl.app

import chrepl.control.ConflictException
import chrepl.control.ForbiddenException
import chrepl.control.NotFoundException
import chrepl.control.QuotaException
import chrepl.control.RequestException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

data class ApiError(
    val error: String,
    val message: String,
)

/** Control plane rejections as 4xx with a readable message; anything else is a 500 without details. */
@RestControllerAdvice
class Errors {
    @ExceptionHandler(RequestException::class)
    fun rejected(e: RequestException): ResponseEntity<ApiError> {
        val (status, code) =
            when (e) {
                is ForbiddenException -> HttpStatus.FORBIDDEN to "forbidden"
                is NotFoundException -> HttpStatus.NOT_FOUND to "not_found"
                // Quotas: the request is well-formed but not allowed now (SPEC §7.4).
                is QuotaException -> HttpStatus.UNPROCESSABLE_CONTENT to "quota"
                // Recently copied by someone else: resubmit with confirm_overwrite_recent (SPEC §7.1).
                is ConflictException -> HttpStatus.CONFLICT to "confirm_overwrite_recent"
                else -> HttpStatus.BAD_REQUEST to "bad_request"
            }
        return ResponseEntity.status(status).body(ApiError(code, e.message ?: code))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun badArgument(e: IllegalArgumentException) = ResponseEntity.badRequest().body(ApiError("bad_request", e.message ?: "bad request"))

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ResponseEntity<ApiError> {
        log.error("request failed", e)
        return ResponseEntity.internalServerError().body(ApiError("internal", "internal error; see the server log"))
    }

    companion object {
        private val log = LoggerFactory.getLogger(Errors::class.java)
    }
}
