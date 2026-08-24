package com.roktober.samsunghealthbridge

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView

class PrivacyPolicyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.privacy_title)

        val padding = (24 * resources.displayMetrics.density).toInt()
        val content =
            TextView(this).apply {
                text = getString(R.string.privacy_body)
                textSize = 17f
                setPadding(padding, padding, padding, padding)
            }
        setContentView(
            ScrollView(this).apply {
                addView(
                    content,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
        )
    }
}
