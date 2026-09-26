package com.v4atune.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TuneUiState(
    val options: TuneOptions = TuneOptions(),
    val running: Boolean = false,
    val status: String = "正在检查环境…",
    val progress: Float = 0f,
    val rootReady: Boolean = false,
    val driverReady: Boolean = false,
    val driver: DriverStatus? = null,
    val result: TuneResult? = null,
    val error: String? = null,
    val restoreMessage: String? = null,
)

class TuneViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(TuneUiState())
    val state: StateFlow<TuneUiState> = _state.asStateFlow()

    init {
        refreshEnvironment()
    }

    fun refreshEnvironment() {
        viewModelScope.launch {
            val root = RootShell.available()
            val driver = withContext(Dispatchers.IO) {
                if (root && ViperControl.available()) ViperControl.status() else null
            }
            _state.update {
                it.copy(
                    rootReady = root,
                    driverReady = driver != null,
                    driver = driver,
                    status = when {
                        !root -> "等待 Root 授权"
                        driver == null -> "Root 已就绪 · 未找到 viper.control"
                        else -> "Root ✓ · ViPER AIDL ✓ · " + driver.sampleRate + " Hz"
                    },
                )
            }
        }
    }

    fun setScene(scene: Scene) {
        _state.update { state ->
            state.copy(options = ScenePresets.apply(scene, state.options))
        }
    }

    fun setTarget(target: Target) {
        _state.update { it.copy(options = it.options.copy(scene = Scene.Custom, target = target)) }
    }

    fun setMode(mode: TestMode) {
        _state.update { it.copy(options = it.options.copy(scene = Scene.Custom, mode = mode)) }
    }

    fun setEqBands(count: Int) {
        if (count !in listOf(10, 15, 25, 31)) return
        _state.update { it.copy(options = it.options.copy(scene = Scene.Custom, eqBands = count)) }
    }

    fun setFirTaps(taps: Int) {
        if (taps !in listOf(1024, 2048, 4096, 8192)) return
        _state.update { it.copy(options = it.options.copy(scene = Scene.Custom, firTaps = taps)) }
    }

    fun setPolicy(component: Component, policy: Policy) {
        _state.update {
            it.copy(
                options = it.options.copy(
                    scene = Scene.Custom,
                    policies = it.options.policies.toMutableMap().apply {
                        this[component] = policy
                    },
                ),
            )
        }
    }

    fun runTune() {
        if (_state.value.running) return
        val options = _state.value.options
        _state.update {
            it.copy(
                running = true,
                progress = 0f,
                result = null,
                error = null,
                restoreMessage = null,
            )
        }

        viewModelScope.launch {
            try {
                val result = AutoTuneEngine(getApplication()).run(options) { text, fraction ->
                    _state.update {
                        it.copy(status = text, progress = fraction.coerceIn(0f, 1f))
                    }
                }
                _state.update {
                    it.copy(
                        running = false,
                        status = "校准完成",
                        progress = 1f,
                        result = result,
                        driver = result.driverAfter,
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        running = false,
                        status = "校准失败",
                        error = (t::class.simpleName ?: "Error") + ": " + (t.message ?: "未知错误"),
                    )
                }
            }
        }
    }

    fun restoreLast() {
        if (_state.value.running) return
        _state.update { it.copy(running = true, restoreMessage = null, error = null) }
        viewModelScope.launch {
            val message = try {
                if (ViperPersistence(getApplication()).restoreLast()) {
                    "已恢复最近一次校准前的 ViPER 配置。"
                } else {
                    "没有可恢复的完整 ViPER 备份。"
                }
            } catch (t: Throwable) {
                "恢复失败：" + (t.message ?: t::class.simpleName)
            }
            _state.update {
                it.copy(
                    running = false,
                    restoreMessage = message,
                    status = "恢复操作完成",
                )
            }
            refreshEnvironment()
        }
    }
}
