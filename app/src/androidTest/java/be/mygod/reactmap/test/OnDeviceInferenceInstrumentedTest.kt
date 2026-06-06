package be.mygod.reactmap.test

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.Firebase
import com.google.firebase.ai.DownloadStatus
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.InferenceSource
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.OnDeviceModelOption
import com.google.firebase.ai.OnDeviceModelStatus
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.generationConfig
import com.google.mlkit.genai.common.DownloadStatus as MlKitDownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.PromptPrefix
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnDeviceInferenceInstrumentedTest {
    @OptIn(PublicPreviewAPI::class)
    @Test
    fun generateWeatherLabelFromOnDeviceModel() = runBlocking {
        val modelOptionArg = InstrumentationRegistry.getArguments().getString("modelOption")
        val modelOption = when (modelOptionArg) {
            null, "", "default" -> null
            "stable" -> OnDeviceModelOption.STABLE
            "preview" -> OnDeviceModelOption.PREVIEW
            "preview_fast" -> OnDeviceModelOption.PREVIEW_FAST
            else -> throw AssertionError("Unknown modelOption argument: $modelOptionArg")
        }
        val downloadTimeoutMsArg = InstrumentationRegistry.getArguments().getString("downloadTimeoutMs")
        val downloadTimeoutMs = when {
            downloadTimeoutMsArg.isNullOrBlank() -> MODEL_DOWNLOAD_TIMEOUT_MS
            else -> downloadTimeoutMsArg.toLongOrNull()
                ?: throw AssertionError("Invalid downloadTimeoutMs argument: $downloadTimeoutMsArg")
        }
        Log.i(TAG, "Testing on-device model option: ${modelOptionArg ?: "default"}")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val launchIntent = targetContext.packageManager
            .getLaunchIntentForPackage(targetContext.packageName)
            ?: throw AssertionError("No launch activity found for ${targetContext.packageName}")
        targetContext.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        val model = Firebase.ai.generativeModel(
            modelName = "gemini-2.5-flash",
            generationConfig = generationConfig {
                temperature = 0f
            },
            onDeviceConfig = OnDeviceConfig(
                mode = InferenceMode.ONLY_ON_DEVICE,
                maxOutputTokens = 32,
                temperature = 0f,
                modelOption = modelOption,
            ),
        )
        val onDevice = model.onDeviceExtension ?: throw AssertionError("On-device AI extension is unavailable")
        val readyStatus = try {
            withTimeout(downloadTimeoutMs) {
                while (true) {
                    when (val status = onDevice.checkStatus()) {
                        OnDeviceModelStatus.AVAILABLE -> return@withTimeout status
                        OnDeviceModelStatus.DOWNLOADABLE -> {
                            Log.i(TAG, "Starting on-device model download")
                            var failedDownload: DownloadStatus.DownloadFailed? = null
                            onDevice.download().collect { downloadStatus ->
                                when (downloadStatus) {
                                    is DownloadStatus.DownloadStarted ->
                                        Log.i(TAG, "Download started: ${downloadStatus.bytesToDownload} bytes")
                                    is DownloadStatus.DownloadInProgress ->
                                        Log.i(TAG, "Download progress: ${downloadStatus.totalBytesDownloaded} bytes")
                                    is DownloadStatus.DownloadCompleted ->
                                        Log.i(TAG, "Download completed")
                                    is DownloadStatus.DownloadFailed -> {
                                        Log.w(TAG, "Download failed", downloadStatus.exception)
                                        failedDownload = downloadStatus
                                    }
                                }
                            }
                            failedDownload?.let {
                                throw AssertionError("On-device model download failed: ${it.exception.message}", it.exception)
                            }
                        }
                        OnDeviceModelStatus.DOWNLOADING -> {
                            Log.i(TAG, "On-device model is already downloading")
                            delay(1_000)
                        }
                        OnDeviceModelStatus.UNAVAILABLE -> return@withTimeout status
                        else -> return@withTimeout status
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                OnDeviceModelStatus.UNAVAILABLE
            }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError(
                "Timed out waiting for the on-device model to become available after ${downloadTimeoutMs}ms",
                e
            )
        }
        Assert.assertEquals("On-device model is not available", OnDeviceModelStatus.AVAILABLE, readyStatus)

        val prompt = """
            Classify this AccuWeather daily forecast HTML snippet.
            Choose the single best label based only on weather content.
            The very last non-empty line must be exactly one allowed label and nothing else.

            Allowed labels:
            ${allowedLabels.joinToString(separator = "\n") { "- $it" }}

            Forecast HTML snippet:
            <div class="daily-wrapper">
              <span class="module-header sub date">6/6</span>
              <img class="icon" src="/images/weathericons/30.svg">
              <div class="phrase">Sunny</div>
              <p class="panel-item">Wind<span class="value">5 km/h</span></p>
            </div>
        """.trimIndent()
        val response = withTimeout(INFERENCE_TIMEOUT_MS) {
            model.generateContent(prompt)
        }
        val rawText = response.text?.trim()
        Log.i(TAG, "Inference source: ${response.inferenceSource}; response: $rawText")
        Assert.assertEquals("Inference did not run on-device", InferenceSource.ON_DEVICE, response.inferenceSource)
        Assert.assertFalse("On-device inference returned no text", rawText.isNullOrBlank())
        val label = rawText!!.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.last()
        Assert.assertTrue("Unsupported on-device label from response: $rawText", label in allowedLabels)
    }

    @Test
    fun generateWeatherLabelWithPrefixCachingFromOnDeviceModel() = runBlocking {
        val downloadTimeoutMsArg = InstrumentationRegistry.getArguments().getString("downloadTimeoutMs")
        val downloadTimeoutMs = when {
            downloadTimeoutMsArg.isNullOrBlank() -> MODEL_DOWNLOAD_TIMEOUT_MS
            else -> downloadTimeoutMsArg.toLongOrNull()
                ?: throw AssertionError("Invalid downloadTimeoutMs argument: $downloadTimeoutMsArg")
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val launchIntent = targetContext.packageManager
            .getLaunchIntentForPackage(targetContext.packageName)
            ?: throw AssertionError("No launch activity found for ${targetContext.packageName}")
        targetContext.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()

        val model = Generation.getClient()
        val readyStatus = try {
            withTimeout(downloadTimeoutMs) {
                while (true) {
                    when (val status = model.checkStatus()) {
                        FeatureStatus.AVAILABLE -> return@withTimeout status
                        FeatureStatus.DOWNLOADABLE -> {
                            Log.i(TAG, "Starting ML Kit on-device model download")
                            var failedDownload: MlKitDownloadStatus.DownloadFailed? = null
                            model.download().collect { downloadStatus ->
                                when (downloadStatus) {
                                    is MlKitDownloadStatus.DownloadStarted ->
                                        Log.i(TAG, "ML Kit download started: ${downloadStatus.bytesToDownload} bytes")
                                    is MlKitDownloadStatus.DownloadProgress ->
                                        Log.i(TAG, "ML Kit download progress: ${downloadStatus.totalBytesDownloaded} bytes")
                                    is MlKitDownloadStatus.DownloadCompleted ->
                                        Log.i(TAG, "ML Kit download completed")
                                    is MlKitDownloadStatus.DownloadFailed -> {
                                        Log.w(TAG, "ML Kit download failed", downloadStatus.e)
                                        failedDownload = downloadStatus
                                    }
                                }
                            }
                            failedDownload?.let {
                                throw AssertionError("ML Kit on-device model download failed: ${it.e.message}", it.e)
                            }
                        }
                        FeatureStatus.DOWNLOADING -> {
                            Log.i(TAG, "ML Kit on-device model is already downloading")
                            delay(1_000)
                        }
                        FeatureStatus.UNAVAILABLE -> return@withTimeout status
                        else -> return@withTimeout status
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                FeatureStatus.UNAVAILABLE
            }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError(
                "Timed out waiting for the ML Kit on-device model to become available after ${downloadTimeoutMs}ms",
                e
            )
        }
        Assert.assertEquals("ML Kit on-device model is not available", FeatureStatus.AVAILABLE, readyStatus)
        Assert.assertTrue("ML Kit prefix caching is not available on this device", model.isCachingFeatureAvailable())

        val request = generateContentRequest(
            TextPart(
                """
                    <div class="daily-wrapper">
                      <span class="module-header sub date">6/6</span>
                      <img class="icon" src="/images/weathericons/30.svg">
                      <div class="phrase">Sunny</div>
                      <p class="panel-item">Wind<span class="value">5 km/h</span></p>
                    </div>
                """.trimIndent()
            )
        ) {
            promptPrefix = PromptPrefix(
                """
                    Classify this AccuWeather daily forecast HTML snippet.
                    Choose the single best label based only on weather content.
                    The very last non-empty line must be exactly one allowed label and nothing else.

                    Allowed labels:
                    ${allowedLabels.joinToString(separator = "\n") { "- $it" }}

                    Forecast HTML snippet:
                """.trimIndent()
            )
            maxOutputTokens = 32
            temperature = 0f
        }
        val response = withTimeout(INFERENCE_TIMEOUT_MS) {
            model.generateContent(request)
        }
        val rawText = response.candidates.firstOrNull()?.text?.trim()
        Log.i(TAG, "Prefix-cached response: $rawText")
        Assert.assertFalse("Prefix-cached inference returned no text", rawText.isNullOrBlank())
        val label = rawText!!.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.last()
        Assert.assertTrue("Unsupported prefix-cached label from response: $rawText", label in allowedLabels)
    }

    private companion object {
        private const val TAG = "OnDeviceInferenceTest"
        private const val MODEL_DOWNLOAD_TIMEOUT_MS = 10 * 60 * 1_000L
        private const val INFERENCE_TIMEOUT_MS = 2 * 60 * 1_000L
        private val allowedLabels = listOf(
            "Sunny",
            "Mostly Sunny",
            "Partly Sunny",
            "Intermittent Clouds",
            "Hazy Sunshine",
            "Mostly Cloudy",
            "Cloudy",
            "Dreary (Overcast)",
            "Fog",
            "Showers",
            "Mostly Cloudy w/ Showers",
            "Partly Sunny w/ Showers",
            "T-Storms",
            "Mostly Cloudy w/ T-Storms",
            "Partly Sunny w/ T-Storms",
            "Rain",
            "Flurries",
            "Mostly Cloudy w/ Flurries",
            "Partly Sunny w/ Flurries",
            "Snow",
            "Mostly Cloudy w/ Snow",
            "Ice",
            "Sleet",
            "Freezing Rain",
            "Rain and Snow",
            "Windy",
        )
    }
}
