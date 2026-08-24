package com.d35p4c1t0.piffbackup

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.d35p4c1t0.piffbackup.databinding.ActivityMainBinding
import com.d35p4c1t0.piffbackup.rsync.NativeFeasibilityProbe
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.runProbeButton.setOnClickListener {
            binding.runProbeButton.isEnabled = false
            binding.probeResult.text = getString(R.string.probe_running)
            executor.execute {
                val result = runCatching { NativeFeasibilityProbe(applicationContext).runLocalOnly() }
                runOnUiThread {
                    binding.runProbeButton.isEnabled = true
                    binding.probeResult.text = result.fold(
                        onSuccess = { it.summary },
                        onFailure = { getString(R.string.probe_failed, it.message ?: it.javaClass.simpleName) },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
