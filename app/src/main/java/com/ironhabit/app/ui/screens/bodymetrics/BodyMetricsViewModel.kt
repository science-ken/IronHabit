package com.ironhabit.app.ui.screens.bodymetrics

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.BodyMetric
import com.ironhabit.app.domain.model.BodyMetricType
import com.ironhabit.app.domain.repository.BodyMetricRepository
import com.ironhabit.app.domain.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * 「身体数据」UI 状态（不可变）。
 *
 * @property isLoading 首帧加载中
 * @property selectedType 当前指标类型
 * @property records 当前类型的记录（按日期倒序）
 * @property latest 当前类型最新记录（顶部当前值）
 * @property valueText 输入数值（文本）
 * @property unitText 输入单位（文本）
 * @property numberErrorRes 数字校验错误资源 id
 * @property errorRes 页面级错误资源 id
 * @property snackbarRes 一次性 Snackbar 资源 id
 */
data class BodyMetricsUiState(
    val isLoading: Boolean = true,
    val selectedType: BodyMetricType = BodyMetricType.WEIGHT,
    val records: List<BodyMetric> = emptyList(),
    val latest: BodyMetric? = null,
    val valueText: String = "",
    val unitText: String = defaultUnit(BodyMetricType.WEIGHT),
    @StringRes val numberErrorRes: Int? = null,
    @StringRes val errorRes: Int? = null,
    @StringRes val snackbarRes: Int? = null,
)

/** 输入表单（与数据流分离，避免编辑时被 Room 重发射覆盖）。 */
private data class BodyMetricsForm(
    val valueText: String = "",
    val unitText: String = defaultUnit(BodyMetricType.WEIGHT),
    @StringRes val numberErrorRes: Int? = null,
    @StringRes val errorRes: Int? = null,
    @StringRes val snackbarRes: Int? = null,
)

/**
 * 「身体数据」ViewModel（P1）：按类型观察记录 + 新增/删除。
 *
 * 趋势图数据即 [BodyMetricsUiState.records]（倒序，绘制前由 UI 反转）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BodyMetricsViewModel @Inject constructor(
    private val bodyMetricRepository: BodyMetricRepository,
    private val clock: Clock,
    private val timeZone: TimeZone,
) : ViewModel() {

    private var selectedType: BodyMetricType = BodyMetricType.WEIGHT
    private val typeFlow = MutableStateFlow(selectedType)
    private val _form = MutableStateFlow(BodyMetricsForm())

    private val _uiState = MutableStateFlow(BodyMetricsUiState())
    val uiState: StateFlow<BodyMetricsUiState> = _uiState.asStateFlow()

    private val dataState: StateFlow<BodyMetricsUiState> =
        combine(
            typeFlow.flatMapLatest { type ->
                bodyMetricRepository.observeByType(type).map { records -> type to records }
            },
            _form,
        ) { typeRecords, form ->
            val type = typeRecords.first
            val records = typeRecords.second
            BodyMetricsUiState(
                isLoading = false,
                selectedType = type,
                records = records,
                latest = bodyMetricRepository.latest(type),
                valueText = form.valueText,
                unitText = form.unitText,
                numberErrorRes = form.numberErrorRes,
                errorRes = form.errorRes,
                snackbarRes = form.snackbarRes,
            )
        }
            .catch {
                emit(BodyMetricsUiState(isLoading = false, errorRes = R.string.error_load_failed))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = BodyMetricsUiState(),
            )

    init {
        viewModelScope.launch {
            dataState.collect { data -> _uiState.value = data }
        }
    }

    /** 切换指标类型（同步刷新默认单位）。 */
    fun onSelectType(type: BodyMetricType) {
        selectedType = type
        typeFlow.value = type
        _form.update { it.copy(unitText = defaultUnit(type), numberErrorRes = null) }
    }

    fun onValueChange(value: String) = _form.update { it.copy(valueText = value, numberErrorRes = null) }

    fun onUnitChange(unit: String) = _form.update { it.copy(unitText = unit) }

    fun onConsumeSnackbar() = _form.update { it.copy(snackbarRes = null) }

    /** 新增一条身体数据（日期 = 今天）。 */
    fun onAddRecord() {
        val value = _form.value.valueText.trim().toFloatOrNull()
        if (value == null) {
            _form.update { it.copy(numberErrorRes = R.string.error_invalid_number) }
            return
        }
        viewModelScope.launch {
            try {
                val epochDay = DateUtils.todayEpochDay(clock, timeZone)
                val metric = BodyMetric(
                    type = selectedType,
                    value = value,
                    unit = _form.value.unitText.trim().ifEmpty { defaultUnit(selectedType) },
                    dateEpochDay = epochDay,
                    dateStartMillis = DateUtils.startOfDayMillis(epochDay, timeZone),
                    createdAt = clock.now().toEpochMilliseconds(),
                )
                bodyMetricRepository.upsert(metric)
                _form.update { it.copy(valueText = "", snackbarRes = R.string.msg_saved) }
            } catch (throwable: Throwable) {
                _form.update { it.copy(snackbarRes = R.string.error_save_failed) }
            }
        }
    }

    /** 删除一条身体数据。 */
    fun onDeleteRecord(id: Long) {
        viewModelScope.launch {
            try {
                bodyMetricRepository.delete(id)
                _form.update { it.copy(snackbarRes = R.string.msg_deleted) }
            } catch (throwable: Throwable) {
                _form.update { it.copy(snackbarRes = R.string.error_generic) }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** 各指标类型的默认单位（数据口径，非用户文案）。 */
private fun defaultUnit(type: BodyMetricType): String = when (type) {
    BodyMetricType.BODY_FAT -> "%"
    BodyMetricType.WEIGHT, BodyMetricType.MUSCLE_MASS -> "kg"
    BodyMetricType.WAIST, BodyMetricType.CHEST, BodyMetricType.ARM, BodyMetricType.HIP -> "cm"
}
