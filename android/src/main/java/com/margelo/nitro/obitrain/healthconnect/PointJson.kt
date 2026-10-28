package com.margelo.nitro.obitrain.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
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
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KClass
import org.json.JSONArray
import org.json.JSONObject

/** Records as the JSON points of src/types.ts; exercises also carry the writer app's series over the session. */
internal class PointJson(private val client: HealthConnectClient, private val granted: Set<String>) {
  suspend fun of(record: Record): JSONObject? = when (record) {
    is ExerciseSessionRecord -> exercise(record)
    is SleepSessionRecord -> sleep(record)
    is WeightRecord ->
      base("weight", record.metadata, record.time, record.zoneOffset).put("weight", record.weight.inKilograms)
    is BodyFatRecord ->
      base("bodyFat", record.metadata, record.time, record.zoneOffset).put("body_fat", record.percentage.value)
    is HeightRecord ->
      base("height", record.metadata, record.time, record.zoneOffset).put("height", record.height.inMeters)
    is RestingHeartRateRecord ->
      base("restingHeartRate", record.metadata, record.time, record.zoneOffset)
        .put("heart_rate", record.beatsPerMinute)
    is HeartRateVariabilityRmssdRecord ->
      base("heartRateVariability", record.metadata, record.time, record.zoneOffset)
        .put("rmssd_ms", record.heartRateVariabilityMillis)
    is Vo2MaxRecord ->
      base("vo2Max", record.metadata, record.time, record.zoneOffset)
        .put("vo2max", record.vo2MillilitersPerMinuteKilogram)
        .put("measurement_method", record.measurementMethod)
    else -> null
  }

  private suspend fun exercise(record: ExerciseSessionRecord): JSONObject {
    val json = base(
      "exercise", record.metadata, record.startTime, record.startZoneOffset, record.endTime, record.endZoneOffset
    )
      .put("exercise_type", EXERCISE_TYPES[record.exerciseType] ?: "OTHER_WORKOUT")
      .put("exercise_type_id", record.exerciseType)
      .putOpt("title", record.title)
      .putOpt("notes", record.notes)
    when (val route = record.exerciseRouteResult) {
      is ExerciseRouteResult.Data -> json.put("route_status", "data").put("route", route(route.exerciseRoute))
      is ExerciseRouteResult.ConsentRequired -> json.put("route_status", "consentRequired")
      else -> json.put("route_status", "noData")
    }
    if (record.laps.isNotEmpty()) {
      json.put("laps", JSONArray(record.laps.map {
        JSONObject()
          .put("start_time", it.startTime.toEpochMilli())
          .put("end_time", it.endTime.toEpochMilli())
          .putOpt("length", it.length?.inMeters)
      }))
    }
    if (record.segments.isNotEmpty()) {
      json.put("segments", JSONArray(record.segments.map {
        JSONObject()
          .put("start_time", it.startTime.toEpochMilli())
          .put("end_time", it.endTime.toEpochMilli())
          .put("segment_type", it.segmentType)
          .put("repetitions", it.repetitions)
      }))
    }

    // only the writer app's records: other apps may have recorded the same workout
    val origin = setOf(record.metadata.dataOrigin)
    val range = TimeRangeFilter.between(record.startTime, record.endTime)
    totals(json, range, origin)
    // padded: a series record crossing a session edge can be filtered out, its samples are trimmed below
    val seriesRange = TimeRangeFilter.between(record.startTime.minus(SERIES_PADDING), record.endTime.plus(SERIES_PADDING))
    json.putOpt("heart_rate", samples(HeartRateRecord::class, seriesRange, record, origin) { r ->
      r.samples.map { it.time to it.beatsPerMinute.toDouble() }
    })
    json.putOpt("speed", samples(SpeedRecord::class, seriesRange, record, origin) { r ->
      r.samples.map { it.time to it.speed.inMetersPerSecond }
    })
    json.putOpt("power", samples(PowerRecord::class, seriesRange, record, origin) { r ->
      r.samples.map { it.time to it.power.inWatts }
    })
    json.putOpt("cycling_cadence", samples(CyclingPedalingCadenceRecord::class, seriesRange, record, origin) { r ->
      r.samples.map { it.time to it.revolutionsPerMinute }
    })
    json.putOpt("steps_cadence", samples(StepsCadenceRecord::class, seriesRange, record, origin) { r ->
      r.samples.map { it.time to it.rate }
    })
    return json
  }

  private suspend fun totals(json: JSONObject, range: TimeRangeFilter, origin: Set<DataOrigin>) {
    val metrics = TOTALS.filter { (_, total) -> total.permission in granted }
    if (metrics.isEmpty()) return
    val result = client.aggregate(AggregateRequest(metrics.map { it.second.metric }.toSet(), range, origin))
    metrics.forEach { (key, total) -> json.putOpt(key, total.value(result[total.metric])) }
  }

  private suspend fun <T : Record> samples(
    type: KClass<T>,
    range: TimeRangeFilter,
    session: ExerciseSessionRecord,
    origin: Set<DataOrigin>,
    extract: (T) -> List<Pair<Instant, Double>>
  ): JSONArray? {
    if (HealthPermission.getReadPermission(type) !in granted) return null
    val start = session.startTime
    val end = session.endTime
    val out = JSONArray()
    var pageToken: String? = null
    do {
      val page = client.readRecords(
        ReadRecordsRequest(type, range, origin, ascendingOrder = true, pageSize = SERIES_PAGE_SIZE, pageToken = pageToken)
      )
      page.records.forEach { record ->
        extract(record).forEach { (time, value) ->
          if (!time.isBefore(start) && !time.isAfter(end)) {
            out.put(JSONObject().put("t", time.toEpochMilli()).put("v", value))
          }
        }
      }
      pageToken = page.pageToken
    } while (!pageToken.isNullOrEmpty())
    return out.takeIf { it.length() > 0 }
  }

  private fun sleep(record: SleepSessionRecord): JSONObject =
    base("sleep", record.metadata, record.startTime, record.startZoneOffset, record.endTime, record.endZoneOffset)
      .putOpt("title", record.title)
      .putOpt("notes", record.notes)
      .put("stages", JSONArray(record.stages.map {
        JSONObject()
          .put("start_time", it.startTime.toEpochMilli())
          .put("end_time", it.endTime.toEpochMilli())
          .put("stage", SLEEP_STAGES[it.stage] ?: "UNKNOWN")
      }))

  private class Total<T : Any>(
    val metric: AggregateMetric<T>,
    val permission: String,
    private val toValue: (T) -> Double
  ) {
    @Suppress("UNCHECKED_CAST")
    fun value(raw: Any?): Double? = (raw as T?)?.let(toValue)
  }

  companion object {
    private const val SERIES_PAGE_SIZE = 1000
    private val SERIES_PADDING: Duration = Duration.ofHours(1)

    private val TOTALS: List<Pair<String, Total<*>>> = listOf(
      "distance" to Total(
        DistanceRecord.DISTANCE_TOTAL, HealthPermission.getReadPermission(DistanceRecord::class)
      ) { it.inMeters },
      "elevation_gained" to Total(
        ElevationGainedRecord.ELEVATION_GAINED_TOTAL, HealthPermission.getReadPermission(ElevationGainedRecord::class)
      ) { it.inMeters },
      "active_calories" to Total(
        ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)
      ) { it.inKilocalories },
      "total_calories" to Total(
        TotalCaloriesBurnedRecord.ENERGY_TOTAL, HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)
      ) { it.inKilocalories },
      "steps" to Total(StepsRecord.COUNT_TOTAL, HealthPermission.getReadPermission(StepsRecord::class)) {
        it.toDouble()
      },
    )

    fun route(route: ExerciseRoute): JSONArray = JSONArray(route.route.map {
      JSONObject()
        .put("t", it.time.toEpochMilli())
        .put("lat", it.latitude)
        .put("lng", it.longitude)
        .putOpt("alt", it.altitude?.inMeters)
        .putOpt("horizontal_accuracy", it.horizontalAccuracy?.inMeters)
        .putOpt("vertical_accuracy", it.verticalAccuracy?.inMeters)
    })

    private fun base(
      type: String,
      metadata: Metadata,
      start: Instant,
      startOffset: ZoneOffset?,
      end: Instant? = null,
      endOffset: ZoneOffset? = null
    ): JSONObject = JSONObject()
      .put("type", type)
      .put("uid", metadata.id)
      .put("start_time", start.toEpochMilli())
      .putOpt("end_time", end?.toEpochMilli())
      .putOpt("zone_offset_s", startOffset?.totalSeconds)
      .putOpt("end_zone_offset_s", endOffset?.totalSeconds)
      .put("update_time", metadata.lastModifiedTime.toEpochMilli())
      .put("app_id", metadata.dataOrigin.packageName)
      .putOpt("client_record_id", metadata.clientRecordId)
      .put("recording_method", RECORDING_METHODS[metadata.recordingMethod] ?: "unknown")
      .putOpt("device", metadata.device?.let {
        JSONObject()
          .put("type", DEVICE_TYPES[it.type] ?: "unknown")
          .putOpt("manufacturer", it.manufacturer)
          .putOpt("model", it.model)
      })

    // the SDK's own int -> name maps are @RestrictTo
    private val EXERCISE_TYPES = mapOf(
      ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON to "BADMINTON",
      ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL to "BASEBALL",
      ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL to "BASKETBALL",
      ExerciseSessionRecord.EXERCISE_TYPE_BIKING to "BIKING",
      ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY to "BIKING_STATIONARY",
      ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP to "BOOT_CAMP",
      ExerciseSessionRecord.EXERCISE_TYPE_BOXING to "BOXING",
      ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS to "CALISTHENICS",
      ExerciseSessionRecord.EXERCISE_TYPE_CRICKET to "CRICKET",
      ExerciseSessionRecord.EXERCISE_TYPE_DANCING to "DANCING",
      ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL to "ELLIPTICAL",
      ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS to "EXERCISE_CLASS",
      ExerciseSessionRecord.EXERCISE_TYPE_FENCING to "FENCING",
      ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN to "FOOTBALL_AMERICAN",
      ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AUSTRALIAN to "FOOTBALL_AUSTRALIAN",
      ExerciseSessionRecord.EXERCISE_TYPE_FRISBEE_DISC to "FRISBEE_DISC",
      ExerciseSessionRecord.EXERCISE_TYPE_GOLF to "GOLF",
      ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING to "GUIDED_BREATHING",
      ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS to "GYMNASTICS",
      ExerciseSessionRecord.EXERCISE_TYPE_HANDBALL to "HANDBALL",
      ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to "HIGH_INTENSITY_INTERVAL_TRAINING",
      ExerciseSessionRecord.EXERCISE_TYPE_HIKING to "HIKING",
      ExerciseSessionRecord.EXERCISE_TYPE_ICE_HOCKEY to "ICE_HOCKEY",
      ExerciseSessionRecord.EXERCISE_TYPE_ICE_SKATING to "ICE_SKATING",
      ExerciseSessionRecord.EXERCISE_TYPE_INT_TO_STRING_MAP to "INT_TO_STRING_MAP",
      ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS to "MARTIAL_ARTS",
      ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT to "OTHER_WORKOUT",
      ExerciseSessionRecord.EXERCISE_TYPE_PADDLING to "PADDLING",
      ExerciseSessionRecord.EXERCISE_TYPE_PARAGLIDING to "PARAGLIDING",
      ExerciseSessionRecord.EXERCISE_TYPE_PILATES to "PILATES",
      ExerciseSessionRecord.EXERCISE_TYPE_RACQUETBALL to "RACQUETBALL",
      ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING to "ROCK_CLIMBING",
      ExerciseSessionRecord.EXERCISE_TYPE_ROLLER_HOCKEY to "ROLLER_HOCKEY",
      ExerciseSessionRecord.EXERCISE_TYPE_ROWING to "ROWING",
      ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE to "ROWING_MACHINE",
      ExerciseSessionRecord.EXERCISE_TYPE_RUGBY to "RUGBY",
      ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to "RUNNING",
      ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL to "RUNNING_TREADMILL",
      ExerciseSessionRecord.EXERCISE_TYPE_SAILING to "SAILING",
      ExerciseSessionRecord.EXERCISE_TYPE_SCUBA_DIVING to "SCUBA_DIVING",
      ExerciseSessionRecord.EXERCISE_TYPE_SKATING to "SKATING",
      ExerciseSessionRecord.EXERCISE_TYPE_SKIING to "SKIING",
      ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING to "SNOWBOARDING",
      ExerciseSessionRecord.EXERCISE_TYPE_SNOWSHOEING to "SNOWSHOEING",
      ExerciseSessionRecord.EXERCISE_TYPE_SOCCER to "SOCCER",
      ExerciseSessionRecord.EXERCISE_TYPE_SOFTBALL to "SOFTBALL",
      ExerciseSessionRecord.EXERCISE_TYPE_SQUASH to "SQUASH",
      ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING to "STAIR_CLIMBING",
      ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE to "STAIR_CLIMBING_MACHINE",
      ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING to "STRENGTH_TRAINING",
      ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING to "STRETCHING",
      ExerciseSessionRecord.EXERCISE_TYPE_STRING_TO_INT_MAP to "STRING_TO_INT_MAP",
      ExerciseSessionRecord.EXERCISE_TYPE_SURFING to "SURFING",
      ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER to "SWIMMING_OPEN_WATER",
      ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL to "SWIMMING_POOL",
      ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS to "TABLE_TENNIS",
      ExerciseSessionRecord.EXERCISE_TYPE_TENNIS to "TENNIS",
      ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL to "VOLLEYBALL",
      ExerciseSessionRecord.EXERCISE_TYPE_WALKING to "WALKING",
      ExerciseSessionRecord.EXERCISE_TYPE_WATER_POLO to "WATER_POLO",
      ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING to "WEIGHTLIFTING",
      ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR to "WHEELCHAIR",
      ExerciseSessionRecord.EXERCISE_TYPE_YOGA to "YOGA",
    )

    private val SLEEP_STAGES = mapOf(
      SleepSessionRecord.STAGE_TYPE_UNKNOWN to "UNKNOWN",
      SleepSessionRecord.STAGE_TYPE_AWAKE to "AWAKE",
      SleepSessionRecord.STAGE_TYPE_SLEEPING to "SLEEPING",
      SleepSessionRecord.STAGE_TYPE_OUT_OF_BED to "OUT_OF_BED",
      SleepSessionRecord.STAGE_TYPE_LIGHT to "LIGHT",
      SleepSessionRecord.STAGE_TYPE_DEEP to "DEEP",
      SleepSessionRecord.STAGE_TYPE_REM to "REM",
      SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED to "AWAKE_IN_BED",
    )

    private val DEVICE_TYPES = mapOf(
      Device.TYPE_UNKNOWN to "unknown",
      Device.TYPE_WATCH to "watch",
      Device.TYPE_PHONE to "phone",
      Device.TYPE_SCALE to "scale",
      Device.TYPE_RING to "ring",
      Device.TYPE_HEAD_MOUNTED to "headMounted",
      Device.TYPE_FITNESS_BAND to "fitnessBand",
      Device.TYPE_CHEST_STRAP to "chestStrap",
      Device.TYPE_SMART_DISPLAY to "smartDisplay",
    )

    private val RECORDING_METHODS = mapOf(
      Metadata.RECORDING_METHOD_UNKNOWN to "unknown",
      Metadata.RECORDING_METHOD_ACTIVELY_RECORDED to "activelyRecorded",
      Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED to "automaticallyRecorded",
      Metadata.RECORDING_METHOD_MANUAL_ENTRY to "manualEntry",
    )
  }
}
