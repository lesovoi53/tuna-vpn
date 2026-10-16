package com.wireturn.app.domain

import com.wireturn.app.*
import com.wireturn.app.data.*
import com.wireturn.app.viewmodel.XrayState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class InternetAccessStatus(val label: Int) {
    CHECKING(R.string.internet_access_checking),
    AVAILABLE(R.string.internet_access_available),
    UNCONFIRMED(R.string.internet_access_unconfirmed)
}

/** Bounded HTTPS check through the server, shared by notification and home screen.
 * The app UID is excluded from its VPN, so this is not an Android TUN check.
 * AVAILABLE must be presented as server reachability, never proof of the entire VPN path.
 */
object InternetAccessMonitor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableStatus = MutableStateFlow(InternetAccessStatus.CHECKING)
    val status = mutableStatus.asStateFlow()
    private val retry = MutableStateFlow(0L)
    private var job: Job? = null
    private var checkedGeneration: Long? = null

    fun currentStatus(): InternetAccessStatus = visibleStatus(
        ready(), CoreServiceState.session.value?.generation, checkedGeneration, status.value)

    internal fun visibleStatus(ready: Boolean, generation: Long?, checked: Long?, result: InternetAccessStatus): InternetAccessStatus =
        if (ready && generation != null && generation == checked) result else InternetAccessStatus.CHECKING

    private fun ready(): Boolean = CoreServiceState.status.value == CoreStatus.Connected ||
        (CoreServiceState.status.value == CoreStatus.Suppressed &&
            XrayServiceState.state.value in listOf(XrayState.Running, XrayState.DirectRoute))

    fun retry() { retry.value++ }

    @Synchronized fun start(context: android.content.Context) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        job = scope.launch {
            combine(CoreServiceState.session, CoreServiceState.status, XrayServiceState.state, retry) { session, _, _, request ->
                Triple(session, ready(), request)
            }.distinctUntilChanged().collectLatest { (session, ready, _) ->
                checkedGeneration = null
                mutableStatus.value = InternetAccessStatus.CHECKING
                if (!ready || session == null) return@collectLatest
                while (currentCoroutineContext().isActive) {
                    checkedGeneration = session.generation
                    mutableStatus.value = InternetAccessStatus.CHECKING
                    delay(1200)
                    val success = try {
                        IsolatedSnellResolver.withActiveKernel(appContext, session.clientConfig) { config ->
                            ServerAccessProbe.check { url ->
                                val group = ConnectionGroup("health", "Health", ConnectionCategory.VPN, GroupTestType.URL_TEST,
                                    listOf("active"), timeoutSeconds = if (config.kernelConfig is KernelConfig.MasterDns) 30 else 8,
                                    samples = 1, testUrl = url)
                                GroupProbe.measure(group, "active", config)
                            }
                        }
                    } catch (_: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        false
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                    currentCoroutineContext().ensureActive()
                    mutableStatus.value = if (success) InternetAccessStatus.AVAILABLE else InternetAccessStatus.UNCONFIRMED
                    delay(60_000)
                }
            }
        }
    }
}
