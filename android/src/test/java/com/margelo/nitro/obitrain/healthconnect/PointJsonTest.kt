package com.margelo.nitro.obitrain.healthconnect

import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.testing.AggregationResult
import androidx.health.connect.client.testing.FakeHealthConnectClient
import androidx.health.connect.client.testing.stubs.stub
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Velocity
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PointJsonTest {
  private val start = Instant.parse("2026-10-20T07:00:00Z")
  private val end = start.plusSeconds(3600)
  private val offset = ZoneOffset.ofHours(2)
  private val client = FakeHealthConnectClient()
  private val granted = setOf(
    HealthPermission.getReadPermission(HeartRateRecord::class),
    HealthPermission.getReadPermission(DistanceRecord::class),
  )

  private fun insertAs(packageName: String, vararg records: Record) = runBlocking {
    client.setPackageName(packageName)
    client.insertRecords(records.toList())
  }

  private fun <T : Record> readOne(type: KClass<T>): T = runBlocking {
    client.readRecords(ReadRecordsRequest(type, TimeRangeFilter.after(Instant.EPOCH))).records.single()
  }

  private fun heartRate(vararg samples: Pair<Long, Long>) = HeartRateRecord(
    startTime = start.minusSeconds(60),
    startZoneOffset = offset,
    endTime = end.plusSeconds(60),
    endZoneOffset = offset,
    samples = samples.map { (s, bpm) -> HeartRateRecord.Sample(start.plusSeconds(s), bpm) },
    metadata = Metadata.manualEntry(),
  )

  @Test
  fun exerciseKeepsTheWriterAppsSeriesWithinTheSession() = runBlocking {
    insertAs(
      GARMIN,
      ExerciseSessionRecord(
        startTime = start,
        startZoneOffset = offset,
        endTime = end,
        endZoneOffset = offset,
        metadata = Metadata.manualEntry(),
        exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        title = "Morning run",
      ),
      // the last sample is after the session end
      heartRate(10L to 140L, 20L to 150L, 4000L to 99L),
      SpeedRecord(
        startTime = start,
        startZoneOffset = offset,
        endTime = end,
        endZoneOffset = offset,
        samples = listOf(SpeedRecord.Sample(start.plusSeconds(10), Velocity.metersPerSecond(3.0))),
        metadata = Metadata.manualEntry(),
      ),
    )
    insertAs(OTHER_APP, heartRate(15L to 60L))
    @Suppress("UNCHECKED_CAST")
    client.overrides.aggregate = stub(
      AggregationResult(metrics = mapOf(DistanceRecord.DISTANCE_TOTAL as AggregateMetric<Any> to Length.meters(10_000.0)))
    )

    val json = PointJson(client, granted).of(readOne(ExerciseSessionRecord::class))!!

    assertEquals("exercise", json.getString("type"))
    assertEquals("RUNNING", json.getString("exercise_type"))
    assertEquals("Morning run", json.getString("title"))
    assertEquals(start.toEpochMilli(), json.getLong("start_time"))
    assertEquals(7200, json.getInt("zone_offset_s"))
    assertEquals(GARMIN, json.getString("app_id"))
    assertEquals("noData", json.getString("route_status"))
    assertEquals(10_000.0, json.getDouble("distance"), 0.0)
    val hr = json.getJSONArray("heart_rate")
    assertEquals(listOf(140.0, 150.0), (0 until hr.length()).map { hr.getJSONObject(it).getDouble("v") })
    // speed permission not granted
    assertFalse(json.has("speed"))
  }

  @Test
  fun sleepMapsStageNames() = runBlocking {
    insertAs(
      GARMIN,
      SleepSessionRecord(
        startTime = start,
        startZoneOffset = offset,
        endTime = end,
        endZoneOffset = offset,
        metadata = Metadata.manualEntry(),
        stages = listOf(
          SleepSessionRecord.Stage(start, start.plusSeconds(600), SleepSessionRecord.STAGE_TYPE_LIGHT),
          SleepSessionRecord.Stage(start.plusSeconds(600), end, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED),
        ),
      ),
    )

    val stages = PointJson(client, granted).of(readOne(SleepSessionRecord::class))!!.getJSONArray("stages")

    assertEquals(listOf("LIGHT", "AWAKE_IN_BED"), (0 until stages.length()).map { stages.getJSONObject(it).getString("stage") })
  }

  @Test
  fun weightIsInKilograms() = runBlocking {
    insertAs(GARMIN, WeightRecord(start, offset, Mass.pounds(154.0), Metadata.manualEntry()))

    val json = PointJson(client, granted).of(readOne(WeightRecord::class))!!

    assertEquals("weight", json.getString("type"))
    assertEquals(69.85, json.getDouble("weight"), 0.01)
    assertEquals(start.toEpochMilli(), json.getLong("start_time"))
  }

  companion object {
    private const val GARMIN = "com.garmin.android.apps.connectmobile"
    private const val OTHER_APP = "com.example.other"
  }
}
