package com.wireturn.app

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import com.google.gson.Gson
import com.wireturn.app.data.*
import com.wireturn.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** Shell-only foreground acceptance harness. Never packaged in the release variant. */
class ReleaseQaActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val label = TextView(this).apply { text = "TUNA VPN: local acceptance"; textSize = 20f }
        setContentView(label)
        val requestId = intent.getStringExtra("requestId") ?: return finish()
        val action = intent.getStringExtra("action") ?: return finish()
        scope.launch {
            val rows = mutableListOf<Map<String, Any?>>()
            fun save(done: Boolean, error: String? = null) {
                File(filesDir, "qa-foreground.json").writeText(Gson().toJson(mapOf(
                    "requestId" to requestId, "version" to BuildConfig.VERSION_NAME,
                    "action" to action, "done" to done, "error" to error, "rows" to rows)))
                label.text = "TUNA VPN: $action\nCompleted: ${rows.size}\n${error ?: if (done) "Done" else "Running"}"
            }
            save(false)
            try {
                withTimeout(300_000) {
                    val real = AndroidGroupRuntime(applicationContext)
                    val profiles = real.profiles()
                    if (action == "paired-url") {
                        val protocol = intent.getStringExtra("protocol")
                        require(protocol in listOf("snell", "mieru", "naive", "vless"))
                        val members = profiles.filter { it.listCategory == ConnectionCategory.VPN &&
                            (it.kernelConfig as? KernelConfig.Vpn)?.config?.protocol == protocol }.take(2)
                        require(members.size == 2)
                        val base = ConnectionGroup("qa-rc49", "QA", ConnectionCategory.VPN,
                            GroupTestType.URL_TEST, members.map { it.id }, samples = 1, timeoutSeconds = 8)
                        for (round in 1..2) {
                            val group = base
                            val child = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                            try {
                                val runner = ConnectionGroupRunner(real, child)
                                runner.test(group)
                                withTimeout(130_000) { while (runner.state.value.busy) delay(100) }
                                val state = runner.state.value
                                rows += mapOf("round" to round, "kind" to "url", "protocol" to protocol,
                                    "message" to state.message, "measured" to state.results.size,
                                    "results" to state.results.map { result -> mapOf(
                                        "member" to members.indexOfFirst { it.id == result.profileId },
                                        "latencyMs" to result.latencyMs, "bytesPerSecond" to result.bytesPerSecond,
                                        "error" to result.error, "endpoint" to result.endpointLabel) })
                                save(false)
                            } finally { child.cancel() }
                        }
                        return@withTimeout
                    }
                    val indices = intent.getStringExtra("indices").orEmpty().split(',').map { it.toInt() }
                    val selected = indices.map { profiles[it] }
                    if (action == "isolated") {
                        for ((i, profile) in selected.withIndex()) {
                            val began = System.currentTimeMillis()
                            val result = runCatching {
                                withTimeout(45_000) { real.withTestTunnel(profile) { cfg ->
                                    GroupProbe.measure(ConnectionGroup("qa", "QA", profile.category,
                                        GroupTestType.URL_TEST, listOf(profile.id), samples = 1,
                                        timeoutSeconds = 8), profile.id, cfg)
                                } }
                            }
                            currentCoroutineContext().ensureActive()
                            rows += mapOf("index" to indices[i], "ok" to result.isSuccess,
                                "error" to result.exceptionOrNull()?.javaClass?.simpleName,
                                "elapsedMs" to System.currentTimeMillis() - began)
                            save(false)
                        }
                    } else {
                        require(action == "groups" && selected.size >= 2)
                        val base = ConnectionGroup("qa-temporary", "QA", selected.first().category,
                            GroupTestType.URL_TEST, selected.map { it.id }, samples = 1, timeoutSeconds = 5)
                        // Real disposable processes/probes; activation is recorded without touching preferences/TUN.
                        val activations = mutableListOf<String>()
                        val runtime = object : GroupRuntime by real {
                            override val status = MutableStateFlow<CoreStatus>(CoreStatus.Connected)
                            override suspend fun selectGroup(id: String) = Unit
                            override suspend fun activate(profile: Profile, routing: Profile): ClientConfig {
                                activations += profile.id
                                return ClientConfig(kernelConfig = profile.kernelConfig)
                            }
                        }
                        for ((name, group) in listOf(
                            "get-best" to base,
                            "head-priority" to base.copy(probeMethod = GroupProbeMethod.HEAD, selectionMode = GroupSelectionMode.PRIORITY),
                            "all-rejected" to base.copy(expectedStatus = 299))) {
                            val child = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                            try {
                                activations.clear()
                                val runner = ConnectionGroupRunner(runtime, child)
                                runner.connect(group)
                                withTimeout(110_000) { while (runner.state.value.busy) delay(100) }
                                val state = runner.state.value
                                val eligible = state.results.filter { it.error == null }
                                val expected = if (group.effectiveSelectionMode == GroupSelectionMode.PRIORITY)
                                    group.memberIds.firstOrNull { id -> eligible.any { it.profileId == id } }
                                else eligible.minByOrNull { it.latencyMs }?.profileId
                                rows += mapOf("case" to name, "measured" to state.results.size,
                                    "successes" to eligible.size, "selectionMatches" to (state.selectedId == expected),
                                    "activationCount" to activations.size,
                                    "pass" to (state.results.size == selected.size && state.selectedId == expected &&
                                        if (name == "all-rejected") eligible.isEmpty() && activations.isEmpty() else eligible.isNotEmpty() && activations.size == 1))
                                save(false)
                            } finally { child.cancel() }
                        }
                        val child = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                        try {
                            activations.clear()
                            val runner = ConnectionGroupRunner(runtime, child)
                            runner.connect(base)
                            delay(500)
                            val began = System.currentTimeMillis()
                            runner.release()
                            withTimeout(10_000) { child.coroutineContext[Job]!!.children.toList().forEach { it.cancelAndJoin() } }
                            rows += mapOf("case" to "cancel", "pass" to (!runner.state.value.busy && activations.isEmpty()),
                                "elapsedMs" to System.currentTimeMillis() - began)
                        } finally { child.cancel() }
                    }
                }
                save(true)
            } catch (failure: Exception) { save(true, failure.javaClass.simpleName) }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
