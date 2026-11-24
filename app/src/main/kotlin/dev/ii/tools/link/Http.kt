package dev.ii.tools.link

import okhttp3.OkHttpClient

/**
 * The one OkHttp the app builds on. Every client is a `newBuilder()` of this, so they all
 * share one connection pool, one dispatcher and its threads, instead of each feature
 * keeping its own set alive in the background.
 */
object Http {
    val base: OkHttpClient by lazy { OkHttpClient.Builder().build() }
}
