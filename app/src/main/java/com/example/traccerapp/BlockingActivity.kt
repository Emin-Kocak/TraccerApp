package com.example.traccerapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val packageName = intent.getStringExtra("packageName") ?: ""
        val appName = intent.getStringExtra("appName") ?: "Uygulama"
        val reason = intent.getStringExtra("reason") ?: "Süren doldu"

        setContent {
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
                        onClick = {
                            val startMain = Intent(Intent.ACTION_MAIN)
                            startMain.addCategory(Intent.CATEGORY_HOME)
                            startMain.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            startActivity(startMain)
                            finish()
                        },
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
}
