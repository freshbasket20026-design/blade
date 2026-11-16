package com.example.data.remote

import android.content.ContentResolver
import android.net.Uri
import com.example.data.local.AppPreferences
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import okio.BufferedSink
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

class SupabaseClient(private val preferences: AppPreferences) {

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val path = original.url.encodedPath
        val isPublicAuthCall = path.endsWith("/auth/v1/signup") ||
            path.endsWith("/auth/v1/token") ||
            path.endsWith("/auth/v1/otp") ||
            path.endsWith("/auth/v1/verify") ||
            path.endsWith("/auth/v1/recover")
        val session = runBlocking { preferences.getSessionSnapshot() }
        val token = if (isPublicAuthCall) {
            SupabaseConfig.anonKey
        } else {
            session.accessToken.ifBlank { SupabaseConfig.anonKey }
        }

        val builder = original.newBuilder()
            .header("apikey", SupabaseConfig.anonKey)
            .header("Authorization", "Bearer $token")

        if (original.header("Content-Type") == null && original.body?.contentType() == null) {
            builder.header("Content-Type", "application/json")
        }

        chain.proceed(builder.build())
    }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun baseUrlOrPlaceholder(): String {
        val configured = SupabaseConfig.url
        return if (configured.startsWith("http://") || configured.startsWith("https://")) {
            "$configured/"
        } else {
            "https://your-project.supabase.co/"
        }
    }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(baseUrlOrPlaceholder())
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    val authApi: SupabaseAuthApi by lazy { retrofit.create(SupabaseAuthApi::class.java) }
    val restApi: SupabaseRestApi by lazy { retrofit.create(SupabaseRestApi::class.java) }

    suspend fun uploadToStorage(
        contentResolver: ContentResolver,
        fileUri: Uri,
        bucket: String,
        objectPath: String,
        mimeType: String,
        onProgress: (Float) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Supabase is not configured in Secrets."))
        }
        val session = preferences.getSessionSnapshot()
        if (!session.isLoggedIn) {
            return@withContext Result.failure(IllegalStateException("Please sign in to upload media."))
        }

        try {
            val totalBytes = contentResolver.openAssetFileDescriptor(fileUri, "r")?.use { it.length } ?: -1L
            val mediaType = mimeType.toMediaTypeOrNull()

            fun buildUploadBody() = object : RequestBody() {
                override fun contentType(): MediaType? = mediaType
                override fun contentLength(): Long = totalBytes
                override fun writeTo(sink: BufferedSink) {
                    val input = contentResolver.openInputStream(fileUri)
                        ?: throw IOException("Unable to read selected file")
                    input.use { stream ->
                        val buffer = ByteArray(16 * 1024)
                        var uploaded = 0L
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            sink.write(buffer, 0, read)
                            uploaded += read
                            if (totalBytes > 0) {
                                onProgress((uploaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.98f))
                            }
                        }
                    }
                }
            }

            val uploadUrl = "${SupabaseConfig.url}/storage/v1/object/$bucket/$objectPath"
            fun executeUpload(): okhttp3.Response {
                val request = Request.Builder()
                    .url(uploadUrl)
                    .header("apikey", SupabaseConfig.anonKey)
                    .header("Authorization", "Bearer ${session.accessToken}")
                    .header("Content-Type", mimeType)
                    .header("x-upsert", "true")
                    .post(buildUploadBody())
                    .build()
                return okHttpClient.newCall(request).execute()
            }

            var response = executeUpload()
            if (response.code == 404) {
                response.close()
                // Attempt to create missing public bucket then retry once
                val createBucketReq = Request.Builder()
                    .url("${SupabaseConfig.url}/storage/v1/bucket")
                    .header("apikey", SupabaseConfig.anonKey)
                    .header("Authorization", "Bearer ${session.accessToken}")
                    .post("""{"id":"$bucket","name":"$bucket","public":true}""".toRequestBody("application/json".toMediaType()))
                    .build()
                okHttpClient.newCall(createBucketReq).execute().close()
                response = executeUpload()
            }

            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: "HTTP ${response.code}"
                response.close()
                return@withContext Result.failure(IllegalStateException("Storage upload failed (${response.code}): $errBody"))
            }
            response.close()
            onProgress(1f)
            val publicUrl = "${SupabaseConfig.url}/storage/v1/object/public/$bucket/$objectPath"
            Result.success(publicUrl)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
