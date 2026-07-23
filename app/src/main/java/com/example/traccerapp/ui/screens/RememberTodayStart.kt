package com.example.traccerapp.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import com.example.traccerapp.utils.startOfDay

/**
 * Bugünün başlangıcını (00:00 ms) döner ve uygulama her RESUME olduğunda yeniden hesaplar (madde 27).
 *
 * Neden: "bugün" değerini `remember {}` ile bir kez hesaplamak, gece yarısını aşan uzun ömürlü
 * composition'larda (kullanıcı uygulamayı açık bıraktığında) dünün gününde donup kalmasına yol açar —
 * bu yüzden ayın 13'ünde 12'nin kullanımı görünüyordu. Yaşam döngüsü akışına bağlanarak her geri
 * dönüşte doğru güne senkronize olur.
 *
 * Bilinen sınır: uygulama gece yarısı boyunca kesintisiz ön planda kalırsa (RESUME tetiklenmez) gün,
 * ekran kapanıp yeniden açılana dek güncellenmez — ekran süresi uygulaması için ihmal edilebilir.
 */
@Composable
fun rememberTodayStart(): Long {
    val lifecycleOwner = LocalLifecycleOwner.current
    var todayStart by remember { mutableLongStateOf(startOfDay(System.currentTimeMillis())) }
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(lifecycleState) {
        if (lifecycleState == Lifecycle.State.RESUMED) {
            todayStart = startOfDay(System.currentTimeMillis())
        }
    }
    return todayStart
}
