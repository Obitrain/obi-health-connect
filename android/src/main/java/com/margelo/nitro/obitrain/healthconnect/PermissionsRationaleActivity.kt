package com.margelo.nitro.obitrain.healthconnect

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle

/** Health Connect's "privacy policy" link: opens the URL set in the app manifest meta-data. */
class PermissionsRationaleActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    @Suppress("DEPRECATION")
    val url = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
      .metaData?.getString(PRIVACY_POLICY_URL)
    if (url != null) runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    finish()
  }

  companion object {
    const val PRIVACY_POLICY_URL = "com.obitrain.healthconnect.PRIVACY_POLICY_URL"
  }
}
