package com.privacy.app.watch

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.privacy.app.AppGraph
import com.privacy.app.data.HealthEntryEntity
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pulls steps and active calories that the smart watch wrote into Health Connect (Samsung Health, Fitbit, Mi Fitness, Google Fit … all sync there)
 * and keeps ONE entry per day up to date in the health log ("Watch activity").
 */
object WatchSync {
    val PERMISSIONS = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)
    )

    fun available(ctx: Context) = HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    suspend fun granted(ctx: Context): Boolean =
        runCatching { HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions().containsAll(PERMISSIONS) }.getOrDefault(false)

    /** Returns a short status line. */
    suspend fun sync(g: AppGraph): String {
        val ctx = g.context
        if (!available(ctx)) return "Health Connect is not installed on this phone."
        if (!granted(ctx)) return "Allow Health Connect access first."
        val hc = HealthConnectClient.getOrCreate(ctx)
        val zone = ZoneId.systemDefault()
        val day = LocalDate.now()
        val r = hc.aggregate(AggregateRequest(
            setOf(StepsRecord.COUNT_TOTAL, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
            TimeRangeFilter.between(day.atStartOfDay(zone).toInstant(), java.time.Instant.now())
        ))
        val steps = r[StepsRecord.COUNT_TOTAL] ?: 0L
        val kcal = r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0
        if (steps == 0L && kcal <= 0.0) return "No watch data for today yet."
        val dao = g.db.healthDao()
        val key = "watch-${day.toEpochDay()}"
        val ex = dao.forDay(day.toEpochDay()).firstOrNull { it.dedupeKey == key }
        val now = System.currentTimeMillis()
        if (ex == null) dao.insert(HealthEntryEntity(day = day.toEpochDay(), category = "ACTIVITY", label = "Watch activity", amount = steps.toDouble(), unit = "steps",
            kcal = kcal.takeIf { it > 0 }, source = "watch", estimated = false, note = "From your watch via Health Connect", dedupeKey = key))
        else dao.update(ex.copy(amount = steps.toDouble(), kcal = kcal.takeIf { it > 0 }, updatedAt = now))
        return "Synced: $steps steps, ${kcal.toInt()} kcal active."
    }
}
