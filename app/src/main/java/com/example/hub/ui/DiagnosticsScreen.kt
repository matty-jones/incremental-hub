package com.example.hub.ui

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.hub.diagnostics.CapabilityDetector
import com.example.hub.diagnostics.ProfileIsolationTest
import com.example.hub.diagnostics.TestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "DiagnosticsScreen"

/**
 * Host composable for the WebView capability spike.
 *
 * On launch it runs [CapabilityDetector.detect] and [ProfileIsolationTest.test] on the
 * main dispatcher, then displays the results via [CapabilityScreen].
 *
 * Callers supply [onRefresh] to trigger a re-run of both detection and the isolation test.
 */
@Composable
fun DiagnosticsScreen(onRefresh: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var capabilities by remember { mutableStateOf<com.example.hub.diagnostics.WebViewCapabilities?>(null) }
    var isolationResult by remember { mutableStateOf<TestResult?>(null) }
    var running by remember { mutableStateOf(false) }

    LaunchedEffect(context) {
        coroutineScope.launch {
            val result = runDetection(context)
            capabilities = result.capabilities
            isolationResult = result.isolationResult
        }
    }

    CapabilityScreen(
        capabilities = capabilities ?: return, // wait for detection to complete
        isolationResult = isolationResult,
        onRefresh = {
            running = true
            coroutineScope.launch {
                val result = runDetection(context)
                capabilities = result.capabilities
                isolationResult = result.isolationResult
                running = false
            }
        },
    )
}

/**
 * Run detection and the isolation test sequentially on [Dispatchers.Main].
 */
private suspend fun runDetection(context: Context): DetectionResult = withContext(Dispatchers.Main) {
    Log.d(TAG, "Running capability detection")
    val detector = CapabilityDetector(context)
    val capabilities = detector.detect()
    Log.d(TAG, "Detection complete: allSupported=${capabilities.allSupported}")

    val isolationResult = runCatching {
        val profileStore = androidx.webkit.ProfileStore.getInstance()
        val test = ProfileIsolationTest(context)
        test.test(profileStore)
    }.getOrElse { e ->
        Log.e(TAG, "Isolation test threw", e)
        TestResult(passed = false, message = "Exception: ${e.message}")
    }

    Log.d(TAG, "Isolation test: passed=${isolationResult.passed} — ${isolationResult.message}")
    DetectionResult(capabilities, isolationResult)
}

/**
 * Pair of results returned by [runDetection].
 */
private data class DetectionResult(
    val capabilities: com.example.hub.diagnostics.WebViewCapabilities,
    val isolationResult: TestResult,
)
