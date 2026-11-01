package com.example.hub

import android.os.Bundle
import androidx.activity.ComponentActivity

/** Empty host so instrumentation can compose a vault without opening hub_database. */
class VaultProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}
