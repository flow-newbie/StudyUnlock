package com.example.studyunlock

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class StudyExitActivity : ComponentActivity() {
    private val verify = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val store = StudyStore.get(this)
            val day = store.today()
            if (day != null && day.isLearning() && day.taskId() == intent.getStringExtra("task") &&
                store.date().toString() == intent.getStringExtra("day")) {
                when (intent.getStringExtra("action")) {
                    "pass" -> store.pass(day)
                    "end" -> {
                        store.endLearning(day)
                        startActivity(Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    }
                }
            }
        }
        finish()
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val end = intent.getStringExtra("action") == "end"
        val prompt = getSystemService(KeyguardManager::class.java).createConfirmDeviceCredentialIntent(
            if (end) "结束本次学习" else "紧急放行",
            if (end) "结束后按未达标规则结算" else "使用手机锁屏凭据确认"
        )
        if (prompt == null) finish() else verify.launch(prompt)
    }

    companion object {
        internal fun intent(context: Context, end: Boolean) = Intent(context, StudyExitActivity::class.java)
            .putExtra("day", StudyStore.get(context).date().toString()).putExtra("action", if (end) "end" else "pass")
            .putExtra("task", StudyStore.get(context).today()?.taskId())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
