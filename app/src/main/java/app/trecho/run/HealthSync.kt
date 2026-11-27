package app.trecho.run

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.trecho.run.core.RunRecord
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Garmin 워치 기록 가져오기.
 * Garmin Connect 앱이 Health Connect에 써 둔 러닝(운동 세션 + 거리 + 걸음)을 읽어 이 앱 기록으로 저장한다.
 * Garmin은 GPS 경로를 Health Connect에 쓰지 않으므로 가져온 기록에는 지도가 없다.
 */
class HealthSync(private val ctx: Context, private val repo: RunRepository) {

    enum class Status { UNAVAILABLE, NEEDS_UPDATE, NO_PERMISSION, CONNECTED }

    data class Result(val imported: Int, val latestGarminMs: Long?)

    val permissions = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
    )

    private val client by lazy { HealthConnectClient.getOrCreate(ctx) }

    suspend fun status(): Status = when (HealthConnectClient.getSdkStatus(ctx)) {
        HealthConnectClient.SDK_AVAILABLE ->
            if (client.permissionController.getGrantedPermissions().containsAll(permissions)) Status.CONNECTED else Status.NO_PERMISSION
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Status.NEEDS_UPDATE
        else -> Status.UNAVAILABLE
    }

    /** 최근 [days]일의 러닝 세션 중 아직 없는 것만 가져온다. */
    suspend fun importRuns(days: Long = 30): Result {
        val end = Instant.now()
        val start = end.minus(days, ChronoUnit.DAYS)
        val sessions = client.readRecords(
            ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.between(start, end))
        ).records
        val running = setOf(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL)
        var imported = 0
        var latest: Long? = null
        // Personal API로 경로까지 가져온 같은 러닝은 건너뛴다
        val apiStarts = repo.all().filter { it.id.startsWith("gc-") }.map { it.startWallMs }
        for (s in sessions) {
            if (s.exerciseType !in running) continue
            val origin = s.metadata.dataOrigin
            if (origin.packageName == ctx.packageName) continue
            val sourceName = sourceName(origin.packageName)
            if (sourceName == "Garmin") latest = maxOf(latest ?: 0L, s.startTime.toEpochMilli())
            val id = "hc-" + s.metadata.id.filter { it.isLetterOrDigit() || it == '-' }
            if (repo.exists(id)) continue
            if (apiStarts.any { kotlin.math.abs(it - s.startTime.toEpochMilli()) < 120_000 }) continue
            val agg = client.aggregate(
                AggregateRequest(
                    metrics = setOf(DistanceRecord.DISTANCE_TOTAL, StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(s.startTime, s.endTime),
                    dataOriginFilter = setOf(origin),
                )
            )
            val dist = agg[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0
            val steps = (agg[StepsRecord.COUNT_TOTAL] ?: 0L).toInt()
            if (dist < 50) continue
            repo.save(
                RunRecord(
                    id = id,
                    startWallMs = s.startTime.toEpochMilli(),
                    distM = dist,
                    activeMs = Duration.between(s.startTime, s.endTime).toMillis(),
                    steps = steps,
                    strideM = if (steps > 0) dist / steps else 1.0,
                    points = emptyList(),
                    splits = emptyList(),
                    title = s.title,
                    source = sourceName,
                )
            )
            imported++
        }
        return Result(imported, latest)
    }

    private fun sourceName(pkg: String) = when {
        pkg.startsWith("com.garmin") -> "Garmin"
        pkg.startsWith("com.sec.android.app.shealth") -> "Samsung Health"
        pkg.startsWith("com.google.android.apps.fitness") -> "Google Fit"
        else -> pkg.substringAfterLast('.')
    }

    companion object {
    }
}
