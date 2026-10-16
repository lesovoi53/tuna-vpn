package com.wireturn.app

import android.app.Instrumentation
import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.lifecycleScope
import com.wireturn.app.viewmodel.MainViewModel
import kotlinx.coroutines.*
import java.lang.ref.WeakReference

/** No profile writes or connections: exercise the actual screen model's create/clear boundary. */
internal object ViewModelLifetimeAudit {
    suspend fun run(instrumentation: Instrumentation): String {
        check(!CoreServiceState.isRunning.value) { "Stop the tunnel before the lifecycle audit" }
        val app = instrumentation.targetContext.applicationContext as Application
        val refs = mutableListOf<WeakReference<MainViewModel>>()
        lateinit var processJob: Job
        instrumentation.runOnMainSync { processJob = ProcessLifecycleOwner.get().lifecycleScope.coroutineContext[Job]!! }
        delay(500)
        val before = processJob.children.count()
        repeat(9) {
            lateinit var store: ViewModelStore
            instrumentation.runOnMainSync {
                store = ViewModelStore()
                MainViewModel(app).also { model -> store.put("qa-lifetime", model); refs += WeakReference(model) }
            }
            delay(100)
            instrumentation.runOnMainSync { store.clear() }
        }
        repeat(3) { System.gc(); delay(200) }
        val after = processJob.children.count()
        val retained = refs.count { it.get() != null }
        val summary = "models=9 processJobsBefore=$before processJobsAfter=$after retainedModels=$retained"
        java.io.File(instrumentation.targetContext.filesDir, "qa-lifetime-result.txt").writeText(summary)
        check(after <= before + 5) { summary }
        check(retained == 0) { summary }
        return summary
    }
}
