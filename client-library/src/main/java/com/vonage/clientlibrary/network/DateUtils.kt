package com.vonage.clientlibrary.network

import android.os.Build
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.TimeZone

class DateUtils {

    companion object {
        private val simpleDateFormat: SimpleDateFormat by lazy {
            var sf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.ssssss'Z'")
            sf.setTimeZone(TimeZone.getTimeZone("UTC"))
            sf
        }

        /**
         * Returns the ISO-8601 formatted date in UTC, such as '2011-12-03T10:15:30Z'
         */
        public fun now(): String {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            } else {
                simpleDateFormat.format(Date())
            }
        }
    }
}