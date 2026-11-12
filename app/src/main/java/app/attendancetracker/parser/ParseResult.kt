package app.attendancetracker.parser

sealed class ParseResult<out T> {
    data class Success<T>(val data: T) : ParseResult<T>()
    data class Failure(val reason: String) : ParseResult<Nothing>()
}
