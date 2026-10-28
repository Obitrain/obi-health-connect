package com.margelo.nitro.obitrain.healthconnect

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.RemoteException
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.Keep
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.reflect.KClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray

@Keep
@DoNotStrip
class HealthConnect @DoNotStrip constructor() : HybridHealthConnectSpec() {
  private var _client: HealthConnectClient? = null

  private fun context(): Context =
    NitroModules.applicationContext ?: throw IllegalStateException("No React context")

  private fun sdkStatus(): Int =
    if (Build.VERSION.SDK_INT < MIN_SDK) HealthConnectClient.SDK_UNAVAILABLE
    else HealthConnectClient.getSdkStatus(context())

  private fun client(): HealthConnectClient {
    _client?.let { return it }
    if (sdkStatus() != HealthConnectClient.SDK_AVAILABLE) {
      throw CodedException(UNAVAILABLE, "Health Connect is not available")
    }
    return HealthConnectClient.getOrCreate(context()).also { _client = it }
  }

  override fun getStatus(): Promise<HealthConnectStatus> = Promise.async {
    when (sdkStatus()) {
      HealthConnectClient.SDK_AVAILABLE -> HealthConnectStatus.AVAILABLE
      HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectStatus.UPDATEREQUIRED
      // built into the platform from Android 14, an installable app before
      else -> if (Build.VERSION.SDK_INT in MIN_SDK until Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        HealthConnectStatus.NOTINSTALLED
      } else {
        HealthConnectStatus.UNSUPPORTEDDEVICE
      }
    }
  }

  override fun resolveStatus(): Promise<Unit> = Promise.async {
    val context = context()
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PROVIDER&url=healthconnect%3A%2F%2Fonboarding"))
      .setPackage("com.android.vending")
      .putExtra("overlay", true)
      .putExtra("callerId", context.packageName)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
      context.startActivity(market)
    } catch (e: ActivityNotFoundException) {
      context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PROVIDER"))
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
    }
  }

  override fun openSettings(): Promise<Unit> = Promise.async {
    client()
    val context = context()
    context.startActivity(
      HealthConnectClient.getHealthConnectManageDataIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
  }

  override fun isFeatureAvailable(feature: HealthConnectFeature): Promise<Boolean> = Promise.async {
    if (sdkStatus() != HealthConnectClient.SDK_AVAILABLE) return@async false
    val id = when (feature) {
      HealthConnectFeature.BACKGROUNDREAD -> HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
      HealthConnectFeature.HISTORY -> HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY
    }
    client().features.getFeatureStatus(id) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
  }

  override fun getGrantedPermissions(
    permissions: Array<HealthConnectPermission>
  ): Promise<Array<HealthConnectPermission>> = Promise.async {
    hcCall {
      val granted = client().permissionController.getGrantedPermissions()
      permissions.filter { permissionOf(it) in granted }.toTypedArray()
    }
  }

  override fun requestPermissions(
    permissions: Array<HealthConnectPermission>
  ): Promise<Array<HealthConnectPermission>> = Promise.async {
    hcCall {
      val wanted = permissions.map(::permissionOf).toSet()
      val controller = client().permissionController
      if (!controller.getGrantedPermissions().containsAll(wanted)) {
        launch(PermissionController.createRequestPermissionResultContract(), wanted)
      }
      val granted = controller.getGrantedPermissions()
      permissions.filter { permissionOf(it) in granted }.toTypedArray()
    }
  }

  override fun readRecords(
    type: HealthConnectPointType,
    startMs: Double,
    endMs: Double,
    pageSize: Double,
    pageToken: String?
  ): Promise<DataPage> = Promise.async {
    hcCall {
      val range = TimeRangeFilter.between(Instant.ofEpochMilli(startMs.toLong()), Instant.ofEpochMilli(endMs.toLong()))
      val size = pageSize.toInt()
      when (type) {
        HealthConnectPointType.EXERCISE -> page(ExerciseSessionRecord::class, range, size, pageToken)
        HealthConnectPointType.SLEEP -> page(SleepSessionRecord::class, range, size, pageToken)
        HealthConnectPointType.WEIGHT -> page(WeightRecord::class, range, size, pageToken)
        HealthConnectPointType.BODYFAT -> page(BodyFatRecord::class, range, size, pageToken)
        HealthConnectPointType.HEIGHT -> page(HeightRecord::class, range, size, pageToken)
        HealthConnectPointType.RESTINGHEARTRATE -> page(RestingHeartRateRecord::class, range, size, pageToken)
        HealthConnectPointType.HEARTRATEVARIABILITY ->
          page(HeartRateVariabilityRmssdRecord::class, range, size, pageToken)
        HealthConnectPointType.VO2MAX -> page(Vo2MaxRecord::class, range, size, pageToken)
      }
    }
  }

  private suspend fun <T : Record> page(
    type: KClass<T>,
    range: TimeRangeFilter,
    pageSize: Int,
    pageToken: String?
  ): DataPage {
    val response = client().readRecords(
      ReadRecordsRequest(type, range, ascendingOrder = true, pageSize = pageSize, pageToken = pageToken)
    )
    val json = pointJson()
    val points = JSONArray()
    response.records.forEach { record -> json.of(record)?.let(points::put) }
    return DataPage(pointsJson = points.toString(), nextPageToken = response.pageToken?.takeIf { it.isNotEmpty() })
  }

  override fun getChangesToken(types: Array<HealthConnectPointType>): Promise<String> = Promise.async {
    hcCall { client().getChangesToken(ChangesTokenRequest(types.map(::recordTypeOf).toSet())) }
  }

  override fun readChanges(changesToken: String): Promise<ChangesPage> = Promise.async {
    hcCall {
      val response = client().getChanges(changesToken)
      val json = pointJson()
      val upserts = JSONArray()
      val deleted = mutableListOf<String>()
      response.changes.forEach { change ->
        when (change) {
          is UpsertionChange -> json.of(change.record)?.let(upserts::put)
          is DeletionChange -> deleted.add(change.recordId)
        }
      }
      ChangesPage(
        upsertsJson = upserts.toString(),
        deletedUids = deleted.toTypedArray(),
        nextChangesToken = response.nextChangesToken,
        hasMore = response.hasMore,
        changesTokenExpired = response.changesTokenExpired
      )
    }
  }

  override fun requestExerciseRoute(uid: String): Promise<String?> = Promise.async {
    hcCall {
      client()
      launch(ExerciseRouteRequestContract(), uid)?.let { PointJson.route(it).toString() }
    }
  }

  override fun aggregateDaily(
    metric: HealthConnectDailyMetric,
    startDate: String,
    endDate: String
  ): Promise<Array<DailyValue>> = Promise.async {
    hcCall {
      val range = TimeRangeFilter.between(
        LocalDate.parse(startDate).atStartOfDay(),
        LocalDate.parse(endDate).plusDays(1).atStartOfDay()
      )
      when (metric) {
        HealthConnectDailyMetric.STEPS -> daily(StepsRecord.COUNT_TOTAL, range) { it.toDouble() }
        HealthConnectDailyMetric.ACTIVECALORIES ->
          daily(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, range) { it.inKilocalories }
        HealthConnectDailyMetric.TOTALCALORIES ->
          daily(TotalCaloriesBurnedRecord.ENERGY_TOTAL, range) { it.inKilocalories }
        HealthConnectDailyMetric.DISTANCE -> daily(DistanceRecord.DISTANCE_TOTAL, range) { it.inMeters }
        HealthConnectDailyMetric.ACTIVETIME ->
          daily(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL, range) { it.seconds.toDouble() }
        HealthConnectDailyMetric.FLOORSCLIMBED -> daily(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL, range) { it }
      }.toTypedArray()
    }
  }

  private suspend fun <T : Any> daily(
    metric: AggregateMetric<T>,
    range: TimeRangeFilter,
    toValue: (T) -> Double
  ): List<DailyValue> =
    client().aggregateGroupByPeriod(AggregateGroupByPeriodRequest(setOf(metric), range, Period.ofDays(1)))
      .mapNotNull { bucket ->
        bucket.result[metric]?.let { DailyValue(bucket.startTime.toLocalDate().toString(), toValue(it)) }
      }

  private suspend fun pointJson(): PointJson =
    PointJson(client(), client().permissionController.getGrantedPermissions())

  private suspend fun <I, O> launch(contract: ActivityResultContract<I, O>, input: I): O {
    val activity = NitroModules.applicationContext?.currentActivity as? ComponentActivity
      ?: throw CodedException(NO_ACTIVITY, "No foreground activity")
    return withContext(Dispatchers.Main) {
      suspendCancellableCoroutine { continuation ->
        var launcher: ActivityResultLauncher<I>? = null
        launcher = activity.activityResultRegistry.register(
          "obi-health-connect-${requestCount.incrementAndGet()}",
          contract
        ) { result ->
          launcher?.unregister()
          if (continuation.isActive) continuation.resume(result)
        }
        continuation.invokeOnCancellation { launcher.unregister() }
        launcher.launch(input)
      }
    }
  }

  private inline fun <T> hcCall(block: () -> T): T {
    try {
      return block()
    } catch (e: CodedException) {
      throw e
    } catch (e: SecurityException) {
      throw CodedException(PERMISSION, e.message)
    } catch (e: RemoteException) {
      val message = e.message.orEmpty()
      val rateLimited = RATE_LIMIT_HINTS.any { message.contains(it, ignoreCase = true) }
      throw CodedException(if (rateLimited) RATE_LIMITED else REMOTE, e.message)
    } catch (e: IOException) {
      throw CodedException(IO, e.message)
    } catch (e: IllegalArgumentException) {
      throw CodedException(INVALID_ARGUMENT, e.message)
    }
  }

  // JS parses the "[code]" prefix into HealthConnectError.code
  private class CodedException(code: String, message: String?) : Exception("[$code] ${message ?: code}")

  companion object {
    private const val MIN_SDK = Build.VERSION_CODES.P
    private const val PROVIDER = "com.google.android.apps.healthdata"

    private const val PERMISSION = "permission"
    private const val RATE_LIMITED = "rateLimited"
    private const val REMOTE = "remote"
    private const val IO = "io"
    private const val INVALID_ARGUMENT = "invalidArgument"
    private const val UNAVAILABLE = "unavailable"
    private const val NO_ACTIVITY = "noActivity"
    private val RATE_LIMIT_HINTS = listOf("quota", "rate limit")

    private val requestCount = AtomicInteger()

    private fun recordTypeOf(type: HealthConnectPointType): KClass<out Record> = when (type) {
      HealthConnectPointType.EXERCISE -> ExerciseSessionRecord::class
      HealthConnectPointType.SLEEP -> SleepSessionRecord::class
      HealthConnectPointType.WEIGHT -> WeightRecord::class
      HealthConnectPointType.BODYFAT -> BodyFatRecord::class
      HealthConnectPointType.HEIGHT -> HeightRecord::class
      HealthConnectPointType.RESTINGHEARTRATE -> RestingHeartRateRecord::class
      HealthConnectPointType.HEARTRATEVARIABILITY -> HeartRateVariabilityRmssdRecord::class
      HealthConnectPointType.VO2MAX -> Vo2MaxRecord::class
    }

    private fun permissionOf(permission: HealthConnectPermission): String = when (permission) {
      HealthConnectPermission.BACKGROUNDREAD -> HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
      HealthConnectPermission.HISTORY -> HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
      HealthConnectPermission.EXERCISE -> HealthPermission.getReadPermission(ExerciseSessionRecord::class)
      HealthConnectPermission.HEARTRATE -> HealthPermission.getReadPermission(HeartRateRecord::class)
      HealthConnectPermission.SPEED -> HealthPermission.getReadPermission(SpeedRecord::class)
      HealthConnectPermission.POWER -> HealthPermission.getReadPermission(PowerRecord::class)
      HealthConnectPermission.CYCLINGPEDALINGCADENCE ->
        HealthPermission.getReadPermission(CyclingPedalingCadenceRecord::class)
      HealthConnectPermission.STEPSCADENCE -> HealthPermission.getReadPermission(StepsCadenceRecord::class)
      HealthConnectPermission.DISTANCE -> HealthPermission.getReadPermission(DistanceRecord::class)
      HealthConnectPermission.ELEVATIONGAINED -> HealthPermission.getReadPermission(ElevationGainedRecord::class)
      HealthConnectPermission.ACTIVECALORIES -> HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)
      HealthConnectPermission.TOTALCALORIES -> HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)
      HealthConnectPermission.STEPS -> HealthPermission.getReadPermission(StepsRecord::class)
      HealthConnectPermission.FLOORSCLIMBED -> HealthPermission.getReadPermission(FloorsClimbedRecord::class)
      HealthConnectPermission.SLEEP -> HealthPermission.getReadPermission(SleepSessionRecord::class)
      HealthConnectPermission.WEIGHT -> HealthPermission.getReadPermission(WeightRecord::class)
      HealthConnectPermission.BODYFAT -> HealthPermission.getReadPermission(BodyFatRecord::class)
      HealthConnectPermission.HEIGHT -> HealthPermission.getReadPermission(HeightRecord::class)
      HealthConnectPermission.RESTINGHEARTRATE -> HealthPermission.getReadPermission(RestingHeartRateRecord::class)
      HealthConnectPermission.HEARTRATEVARIABILITY ->
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class)
      HealthConnectPermission.VO2MAX -> HealthPermission.getReadPermission(Vo2MaxRecord::class)
    }
  }
}
