package com.vonage.clientlibrary.network

import android.os.Build
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.TimeZone

class DateUtils {

    companion object {
        // DEVX-11226: SimpleDateFormat is not thread-safe. Use ThreadLocal so each thread
        // gets its own instance, avoiding data races when logging is concurrent.
        // Also fixes the format pattern: 'ssssss' (seconds repeated) → 'SSS' (milliseconds).
        private val simpleDateFormat: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").also {
                it.timeZone = TimeZone.getTimeZone("UTC")
            }
        }

        /**
         * Returns the ISO-8601 formatted date in UTC, such as '2011-12-03T10:15:30.123Z'
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