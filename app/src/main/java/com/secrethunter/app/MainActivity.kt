package com.secrethunter.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import java.io.File
import java.io.FileOutputStream
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.secrethunter.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: AppDatabase
    private lateinit var secretAdapter: SecretAdapter

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) {
            Snackbar.make(binding.root, getString(R.string.pick_file_cancelled), Snackbar.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        scanPickedUri(uri)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.values.any { it }
        if (granted) {
            Snackbar.make(
                binding.root,
                getString(R.string.permissions_granted_hint),
                Snackbar.LENGTH_SHORT,
            ).show()
        } else {
            Snackbar.make(
                binding.root,
                getString(R.string.permissions_denied_hint),
                Snackbar.LENGTH_LONG,
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getInstance(this)
        secretAdapter = SecretAdapter()
        binding.recyclerSecrets.layoutManager = LinearLayoutManager(this)
        binding.recyclerSecrets.adapter = secretAdapter

        binding.toolbar.setNavigationOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_real_test -> runRealLocalTest()
                R.id.action_pick_file -> openDocumentLauncher.launch(
                    arrayOf(
                        "text/plain",
                        "text/*",
                        "application/json",
                        "application/xml",
                        "application/octet-stream",
                        "application/vnd.android.package-archive",
                        "application/zip",
                        "*/*",
                    ),
                )
                R.id.action_pick_apk -> openDocumentLauncher.launch(
                    arrayOf(
                        "application/vnd.android.package-archive",
                        "application/zip"
                    ),
                )
                else -> return@setOnMenuItemClickListener false
            }
            true
        }

        binding.navigationView.setCheckedItem(R.id.nav_scan)
        binding.navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_scan, R.id.nav_results -> showScanUi()
                R.id.nav_about -> showAboutUi()
            }
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        binding.fabScan.setOnClickListener {
            if (!ensureStoragePermissions()) return@setOnClickListener
            runScan(requirePermissions = true)
        }

        observeSecrets()
        showScanUi()
        requestStartupPermissions()
    }

    private fun observeSecrets() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                db.secretDao().observeAllSorted().collect { list ->
                    secretAdapter.submit(list)
                    val empty = list.isEmpty()
                    binding.textEmpty.visibility = if (empty) View.VISIBLE else View.GONE
                    binding.recyclerSecrets.visibility = if (empty) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun requestStartupPermissions() {
        val needed = permissionsNeeded().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun permissionsNeeded(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            )
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun ensureStoragePermissions(): Boolean {
        val missing = permissionsNeeded().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return true
        Snackbar.make(binding.root, getString(R.string.permission_rationale), Snackbar.LENGTH_LONG)
            .show()
        permissionLauncher.launch(missing.toTypedArray())
        return false
    }

    private fun scanPickedUri(uri: Uri) {
        lifecycleScope.launch {
            binding.progressScan.visibility = View.VISIBLE
            binding.fabScan.isEnabled = false
            try {
                val detector = RegexDetector(this@MainActivity)
                val items = FileSystemScanner.scanContentUri(this@MainActivity, uri, detector)
                if (items == null) {
                    Snackbar.make(
                        binding.root,
                        getString(R.string.pick_file_read_failed),
                        Snackbar.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    db.secretDao().deleteAll()
                    db.secretDao().insertAll(items)
                }
                Snackbar.make(
                    binding.root,
                    getString(R.string.scan_summary, items.size),
                    Snackbar.LENGTH_SHORT,
                ).show()
            } finally {
                binding.progressScan.visibility = View.GONE
                binding.fabScan.isEnabled = true
            }
        }
    }

    private fun runRealLocalTest() {
        val ok = try {
            assets.open("sample_test_secrets.txt").use { input ->
                val outFile = File(filesDir, "SecretHunter_test_reel.env")
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
            true
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            Snackbar.make(binding.root, getString(R.string.real_test_copy_failed), Snackbar.LENGTH_LONG).show()
            return
        }
        Snackbar.make(binding.root, getString(R.string.real_test_installed), Snackbar.LENGTH_SHORT).show()
        runScan(requirePermissions = false)
    }

    private fun runScan(requirePermissions: Boolean) {
        if (requirePermissions && !ensureStoragePermissions()) return
        lifecycleScope.launch {
            binding.progressScan.visibility = View.VISIBLE
            binding.fabScan.isEnabled = false
            try {
                val detector = RegexDetector(this@MainActivity)
                val items = FileSystemScanner.scan(this@MainActivity, detector) { status ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        binding.textStatus.text = getString(R.string.scanning_file, status)
                    }
                }
                withContext(Dispatchers.IO) {
                    db.secretDao().deleteAll()
                    db.secretDao().insertAll(items)
                }
                Snackbar.make(
                    binding.root,
                    getString(R.string.scan_summary, items.size),
                    Snackbar.LENGTH_SHORT,
                ).show()
            } finally {
                binding.progressScan.visibility = View.GONE
                binding.fabScan.isEnabled = true
            }
        }
    }

    private fun showScanUi() {
        binding.layoutScan.visibility = View.VISIBLE
        binding.layoutAbout.visibility = View.GONE
        binding.fabScan.visibility = View.VISIBLE
    }

    private fun showAboutUi() {
        binding.layoutScan.visibility = View.GONE
        binding.layoutAbout.visibility = View.VISIBLE
        binding.fabScan.visibility = View.GONE
    }
}
