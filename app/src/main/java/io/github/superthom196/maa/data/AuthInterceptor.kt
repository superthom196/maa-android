package io.github.superthom196.maa.data

import okhttp3.Interceptor
import okhttp3.Response

/** WS-B: adds `Authorization: Bearer` only for requests to [ServerConfig.urls] hosts. Stub. */
class AuthInterceptor(config: ConfigStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
