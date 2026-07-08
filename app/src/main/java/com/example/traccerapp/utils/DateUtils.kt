package com.example.traccerapp.utils

import java.util.Calendar

const val DAY_MS: Long = 24 * 60 * 60 * 1000L

fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Verilen günün ait olduğu haftanın Pazartesi'sini (gün başlangıcı) döner. */
fun mondayOfWeek(dayStartMs: Long): Long {
    val cal = Calendar.getInstance().apply { timeInMillis = dayStartMs }
    val dow = cal.get(Calendar.DAY_OF_WEEK) // Calendar.SUNDAY=1 .. Calendar.SATURDAY=7
    val offsetFromMonday = (dow + 5) % 7
    cal.add(Calendar.DAY_OF_YEAR, -offsetFromMonday)
    return cal.timeInMillis
}

/** Verilen günün ait olduğu ayın 1'ini (gün başlangıcı) döner. */
fun startOfMonth(dayStartMs: Long): Long = Calendar.getInstance().apply {
    timeInMillis = dayStartMs
    set(Calendar.DAY_OF_MONTH, 1)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis
