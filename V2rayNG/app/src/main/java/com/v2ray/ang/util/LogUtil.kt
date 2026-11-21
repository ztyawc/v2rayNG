package com.v2ray.ang.util

import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import java.util.Locale
import java.util.IdentityHashMap

object LogUtil {

    private const val DEFAULT_LEVEL = "warning"
    private const val CACHE_UNSET = Int.MIN_VALUE

    @Volatile
    private var cachedMinPriority: Int = CACHE_UNSET

    private fun parsePriority(level: String?): Int {
        return when ((level ?: DEFAULT_LEVEL).lowercase(Locale.US)) {
            "verbose" -> Log.VERBOSE
            "debug" -> Log.DEBUG
            "info" -> Log.INFO
            "warn", "warning" -> Log.WARN
            "error" -> Log.ERROR
            "none", "off" -> Int.MAX_VALUE
            else -> Log.WARN
        }
    }

    @Suppress("unused")
    fun refreshLogLevel() {
        cachedMinPriority = parsePriority(MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL, DEFAULT_LEVEL))
    }

    private fun minPriority(): Int {
        val cached = cachedMinPriority
        if (cached != CACHE_UNSET) {
            return cached
        }

        return synchronized(this) {
            val current = cachedMinPriority
            if (current != CACHE_UNSET) {
                current
            } else {
                parsePriority(MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL, DEFAULT_LEVEL)).also {
                    cachedMinPriority = it
                }
            }
        }
    }

    private fun isEnabled(priority: Int): Boolean {
        return priority >= minPriority()
    }

    private fun log(priority: Int, tag: String, message: String, throwable: Throwable? = null) {
        if (!isEnabled(priority)) return
        val safeThrowable = throwable?.let(::sanitizedThrowable)

        when {
            throwable == null -> Log.println(priority, tag, message)
            priority >= Log.ERROR -> Log.e(tag, message, safeThrowable)
            priority == Log.WARN -> Log.w(tag, message, safeThrowable)
            priority == Log.INFO -> Log.i(tag, message, safeThrowable)
            priority == Log.DEBUG -> Log.d(tag, message, safeThrowable)
            else -> Log.v(tag, message, safeThrowable)
        }
    }

    /** Parser/network/native exception messages can contain credentials or config fragments. */
    internal fun sanitizedThrowable(source: Throwable): Throwable {
        val visited = IdentityHashMap<Throwable, Throwable>()
        fun copy(error: Throwable): Throwable {
            visited[error]?.let { return it }
            val safe = Throwable(error.javaClass.name).apply { stackTrace = error.stackTrace }
            visited[error] = safe
            error.cause?.takeIf { it !in visited }?.let { safe.initCause(copy(it)) }
            error.suppressed.filter { it !in visited }.forEach { safe.addSuppressed(copy(it)) }
            return safe
        }
        return copy(source)
    }

    fun d(tag: String = AppConfig.TAG, message: String) = log(Log.DEBUG, tag, message)
    fun i(tag: String = AppConfig.TAG, message: String) = log(Log.INFO, tag, message)
    fun w(tag: String = AppConfig.TAG, message: String) = log(Log.WARN, tag, message)
    fun e(tag: String = AppConfig.TAG, message: String) = log(Log.ERROR, tag, message)

    fun d(tag: String = AppConfig.TAG, message: String, throwable: Throwable) = log(Log.DEBUG, tag, message, throwable)
    fun i(tag: String = AppConfig.TAG, message: String, throwable: Throwable) = log(Log.INFO, tag, message, throwable)
    fun w(tag: String = AppConfig.TAG, message: String, throwable: Throwable) = log(Log.WARN, tag, message, throwable)
    fun e(tag: String = AppConfig.TAG, message: String, throwable: Throwable) = log(Log.ERROR, tag, message, throwable)
}
