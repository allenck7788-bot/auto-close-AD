package com.autoad.closer

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val btnEnable = Button(this).apply {
            text = "開啟無障礙服務權限"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        setContentView(btnEnable)
        Toast.makeText(this, "請先開啟 AutoAdCloser 服務", Toast.LENGTH_LONG).show()
    }
}
