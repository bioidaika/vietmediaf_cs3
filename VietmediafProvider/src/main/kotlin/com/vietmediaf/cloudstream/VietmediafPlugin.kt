package com.vietmediaf.cloudstream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.EditText
import android.text.InputType
import com.lagradost.cloudstream3.AcraApplication

@CloudstreamPlugin
class VietmediafPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(VietmediafProvider())

        openSettings = { ctx ->
            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(50, 40, 50, 40)
            }

            val emailInput = EditText(ctx).apply {
                hint = "Email Fshare"
                inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                setText(AcraApplication.getKey<String>("fshare_email") ?: "")
            }

            val passwordInput = EditText(ctx).apply {
                hint = "Mật khẩu Fshare"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setText(AcraApplication.getKey<String>("fshare_password") ?: "")
            }

            layout.addView(emailInput)
            layout.addView(passwordInput)

            AlertDialog.Builder(ctx)
                .setTitle("Cấu hình Fshare")
                .setView(layout)
                .setPositiveButton("Lưu") { _, _ ->
                    AcraApplication.setKey("fshare_email", emailInput.text.toString().trim())
                    AcraApplication.setKey("fshare_password", passwordInput.text.toString())
                }
                .setNegativeButton("Hủy", null)
                .show()
        }
    }
}
