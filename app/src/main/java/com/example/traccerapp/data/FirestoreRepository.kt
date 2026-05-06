package com.example.traccerapp.data

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.text.SimpleDateFormat
import java.util.*

class FirestoreRepository(context: Context) {
    private val firestore = FirebaseFirestore.getInstance()
    private val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    private val logsCollection = firestore.collection("devices").document(deviceId).collection("usage_logs")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun saveUsageLog(log: UsageLog) {
        val dateStr = dateFormat.format(Date(log.date))
        val docId = "${log.packageName}_$dateStr"

        val data = hashMapOf(
            "packageName" to log.packageName,
            "appName" to log.appName,
            "date" to dateStr,
            "durationMs" to log.durationMs,
            "lastUpdated" to Timestamp.now()
        )

        logsCollection.document(docId).set(data, SetOptions.merge())
            .addOnFailureListener { e ->
                Log.e("FirestoreRepository", "saveUsageLog failed for $docId", e)
            }
    }

    // ✅ FIX: Real-time listener ile canlı veri
    fun getTodayLogs(): Flow<List<UsageLog>> = callbackFlow {
        val todayStr = dateFormat.format(Date())

        val listener = logsCollection.whereEqualTo("date", todayStr)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e("FirestoreRepository", "getTodayLogs error", e)
                    return@addSnapshotListener
                }

                val logs = snapshot?.documents?.mapNotNull { doc ->
                    val pkg = doc.getString("packageName") ?: return@mapNotNull null
                    val app = doc.getString("appName") ?: ""
                    val dur = doc.getLong("durationMs") ?: 0L

                    val cal = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }
                    UsageLog(packageName = pkg, appName = app, date = cal.timeInMillis, durationMs = dur)
                } ?: emptyList()

                trySend(logs)
                Log.d("FirestoreRepository", "getTodayLogs: ${logs.size} apps found")
            }
        awaitClose {
            listener.remove()
            Log.d("FirestoreRepository", "getTodayLogs listener removed")
        }
    }

    fun getLogsForDateRange(startDate: String, endDate: String): Flow<List<UsageLog>> = callbackFlow {
        val listener = logsCollection
            .whereGreaterThanOrEqualTo("date", startDate)
            .whereLessThanOrEqualTo("date", endDate)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e("FirestoreRepository", "getLogsForDateRange error", e)
                    return@addSnapshotListener
                }

                val logs = snapshot?.documents?.mapNotNull { doc ->
                    val pkg = doc.getString("packageName") ?: return@mapNotNull null
                    val app = doc.getString("appName") ?: ""
                    val dur = doc.getLong("durationMs") ?: 0L
                    val dateStr = doc.getString("date") ?: ""
                    val dateMs = try { dateFormat.parse(dateStr)?.time ?: 0L } catch (ex: Exception) { 0L }

                    UsageLog(packageName = pkg, appName = app, date = dateMs, durationMs = dur)
                } ?: emptyList()

                trySend(logs)
            }
        awaitClose { listener.remove() }
    }
}