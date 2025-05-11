package com.example.vroomhero

import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private const val BASE_URL = "https://router.hereapi.com/v8/routes"
    private const val API_KEY = "YOUR_API_KEY_HERE" // Replace with your HERE API key

    private val authInterceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer $API_KEY")
            .build()
        chain.proceed(request)
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .build()

    val apiService: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(ScalarsConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}