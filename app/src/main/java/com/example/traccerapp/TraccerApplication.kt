package com.example.traccerapp
import android.app.Application
import com.google.firebase.FirebaseApp

class TraccerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
    }
}
