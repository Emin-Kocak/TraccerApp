package com.example.traccerapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.traccerapp.ui.components.RealAppIcon
import com.example.traccerapp.ui.theme.TraccerAppTheme

class BlockingActivity : ComponentActivity() {

    private val packageNameState = mutableStateOf("")
    private val appNameState = mutableStateOf("Uygulama")
    private val reasonState = mutableStateOf("Süren doldu")

    private fun goHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(homeIntent)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Intent'ten gelen verileri state'e yaz
        updateFromIntent(intent)

        // Geri tuşu engelle
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goHome()
            }
        })

        setContent {
            val packageName = packageNameState.value
            val appName = appNameState.value
            val reason = reasonState.value
            TraccerAppTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0D0D14)),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    RealAppIcon(packageName = packageName, appName = appName, size = 80.dp, cornerRadius = 20.dp)

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "ENGELLENDİ",
                        color = Color.Red,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "$appName için $reason.",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(48.dp))

                    Button(
                        onClick = { goHome() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(0.6f)
                    ) {
                        Text("Eve Dön", color = Color.White)
                    }
                }
            }
        }
    }
    // singleTop modunda tekrar açılınca çağrılır
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateFromIntent(intent)
    }

    private fun updateFromIntent(intent: Intent) {
        packageNameState.value = intent.getStringExtra("packageName") ?: ""
        appNameState.value = intent.getStringExtra("appName") ?: "Uygulama"
        reasonState.value = intent.getStringExtra("reason") ?: "Süren doldu"
    }
}
