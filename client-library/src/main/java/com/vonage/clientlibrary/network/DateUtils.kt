package com.vonage.clientlibrary.network

import android.os.Build
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DateUtils {

    companion object {
        // DEVX-11226: SimpleDateFormat is not thread-safe. Use ThreadLocal so each thread
        // gets its own instance, avoiding data races when logging is concurrent.
        // Also fixes the format pattern: 'ssssss' (seconds repeated) → 'SSS' (milliseconds).
        // Note: ThreadLocal.withInitial{} requires API 26+; anonymous initialValue() works on all supported levels.
        private val simpleDateFormat: ThreadLocal<SimpleDateFormat> =
            object : ThreadLocal<SimpleDateFormat>() {
                override fun initialValue(): SimpleDateFormat =
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).also {
                        it.timeZone = TimeZone.getTimeZone("UTC")
                    }
            }

        /**
         * Returns the current time as an ISO-8601 UTC string.
         * On API 26+, uses DateTimeFormatter.ISO_INSTANT: fractional seconds are omitted when
         * zero and may be 1–9 digits otherwise (e.g. '2011-12-03T10:15:30Z').
         * On API < 26, uses SimpleDateFormat with millisecond precision
         * (e.g. '2011-12-03T10:15:30.123Z').
         */
        public fun now(): String {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            } else {
                simpleDateFormat.get()!!.format(Date())
            }
        }
    }
}
