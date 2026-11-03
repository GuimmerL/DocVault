package com.docvault.app

import android.content.Intent
import android.os.Bundle
import android.content.res.ColorStateList
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.button.MaterialButton
import com.docvault.app.DocVaultApp
import com.docvault.app.model.Category
import com.docvault.app.ui.dialogs.ManageCategoriesDialog
import com.docvault.app.ui.dialogs.ProtectedCategoryDialog
import com.docvault.app.ui.dialogs.SettingsDialog
import com.docvault.app.ui.library.DocumentAdapter
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.docvault.app.ui.browser.BrowserFragment
import com.docvault.app.ui.library.LibraryFragment
import com.docvault.app.ui.search.SearchFragment

class MainActivity : AppCompatActivity() {

    private lateinit var bottomNav: BottomNavigationView
    private lateinit var drawer: DrawerLayout
    private lateinit var drawerCategoryList: LinearLayout
    private var categoryEditing = false
    private var selectedDrawerCategoryId: String? = null
    private val vaultTreePicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try { contentResolver.takePersistableUriPermission(uri, flags) } catch (_: SecurityException) { }
            getSharedPreferences("docvault_settings", 0).edit().putString("vault_tree_uri", uri.toString()).apply()
            Toast.makeText(this, "Vault folder selected. Use the file picker to import PDFs.", Toast.LENGTH_LONG).show()
        }
    }
    private val libraryFragment = LibraryFragment()
    private val browserFragment = BrowserFragment()
    private val searchFragment = SearchFragment()

    private var activeFragment: Fragment = libraryFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        val themeMode = getSharedPreferences("docvault_settings", 0).getInt(
            "theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        )
        AppCompatDelegate.setDefaultNightMode(themeMode)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bottomNav = findViewById(R.id.bottomNavigation)
        drawer = findViewById(R.id.mainDrawer)
        drawerCategoryList = findViewById(R.id.drawerCategoryList)
        setupDrawer()

        // Initialize fragments
        supportFragmentManager.beginTransaction().apply {
            add(R.id.fragmentContainer, searchFragment, "SEARCH").hide(searchFragment)
            add(R.id.fragmentContainer, browserFragment, "BROWSER").hide(browserFragment)
            add(R.id.fragmentContainer, libraryFragment, "LIBRARY")
            commit()
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_library -> {
                    switchFragment(libraryFragment)
                    true
                }
                R.id.nav_browser -> {
                    switchFragment(browserFragment)
                    true
                }
                R.id.nav_search -> {
                    switchFragment(searchFragment)
                    true
                }
                else -> false
            }
        }

        // Handle back button for browser navigation
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (activeFragment == browserFragment && browserFragment.canGoBack()) {
                    browserFragment.goBack()
                } else if (activeFragment != libraryFragment) {
                    bottomNav.selectedItemId = R.id.nav_library
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

    }

    fun openDrawer() {
        refreshDrawerCategories()
        drawer.openDrawer(findViewById(R.id.navigationDrawer))
    }

    private fun setupDrawer() {
        findViewById<MaterialButton>(R.id.drawerAllDocuments).setOnClickListener {
            selectedDrawerCategoryId = null
            libraryFragment.selectCategory(null)
            bottomNav.selectedItemId = R.id.nav_library
            drawer.closeDrawers()
        }
        findViewById<MaterialButton>(R.id.drawerEditCategories).setOnClickListener {
            categoryEditing = !categoryEditing
            it as MaterialButton
            it.text = if (categoryEditing) "Done editing" else "Edit categories"
            refreshDrawerCategories()
        }
        findViewById<MaterialButton>(R.id.drawerAddCategory).setOnClickListener {
            ManageCategoriesDialog.showAddCategoryDialog(this) { refreshDrawerCategories() }
        }
        findViewById<MaterialButton>(R.id.drawerSettings).setOnClickListener {
            drawer.closeDrawers()
            SettingsDialog.show(this, libraryFragment.getViewMode(), { mode -> libraryFragment.setViewMode(mode) }) {
                refreshDrawerCategories()
                libraryFragment.selectCategory(null)
            }
        }
        findViewById<MaterialButton>(R.id.drawerFolderView).setOnClickListener {
            val hasFolder = getSharedPreferences("docvault_settings", 0).contains("vault_tree_uri")
            val choices = if (hasFolder) arrayOf("Import PDFs from selected folder", "Export PDFs to selected folder", "Change selected folder") else arrayOf("Choose a folder")
            MaterialAlertDialogBuilder(this)
                .setTitle("Vault folder")
                .setItems(choices) { _, which ->
                    when {
                        !hasFolder -> vaultTreePicker.launch(null)
                        which == 0 -> lifecycleScope.launch {
                            val count = withContext(Dispatchers.IO) { DocVaultApp.instance.repository.importFromSelectedVaultFolder() }
                            Toast.makeText(this@MainActivity, "Imported $count PDF(s). Protected categories must be unlocked once to migrate their protected files.", Toast.LENGTH_LONG).show()
                            libraryFragment.refreshDocuments()
                        }
                        which == 1 -> lifecycleScope.launch {
                            try {
                                val count = DocVaultApp.instance.repository.exportToSelectedTree()
                                Toast.makeText(this@MainActivity, "Exported $count PDF(s)", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(this@MainActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                        else -> vaultTreePicker.launch(null)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        findViewById<MaterialButton>(R.id.drawerClose).setOnClickListener { drawer.closeDrawers() }
        refreshDrawerCategories()
    }

    private fun refreshDrawerCategories() {
        if (!::drawerCategoryList.isInitialized) return
        drawerCategoryList.removeAllViews()
        val repo = DocVaultApp.instance.repository
        val allCategories = repo.getCategories()
        val visible = allCategories.filter { !it.isHidden }
        val hidden = allCategories.filter { it.isHidden }

        fun addSectionLabel(text: String) {
            drawerCategoryList.addView(TextView(this).apply {
                this.text = text
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.on_surface_muted))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(12, 14, 8, 6)
            })
        }

        fun addCategoryRow(category: com.docvault.app.model.Category, isHidden: Boolean) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 5, 0, 5)
            }
            val label = TextView(this).apply {
                text = if (category.id == selectedDrawerCategoryId && !isHidden) "✓  ${category.icon}  ${category.name}" else "${category.icon}  ${category.name}"
                textSize = 18f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.on_surface))
                if (category.id == selectedDrawerCategoryId && !isHidden) {
                    setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.menu_button))
                }
                setPadding(12, 8, 8, 8)
                if (!isHidden) setOnClickListener {
                    if (category.isPasswordProtected && !repo.isCategoryUnlocked(category.id)) {
                        ProtectedCategoryDialog.showUnlock(this@MainActivity, category) { password ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val success = repo.unlockCategory(category.id, password)
                                runOnUiThread {
                                    password.fill(0.toChar())
                                    if (success) {
                                        selectedDrawerCategoryId = category.id
                                        libraryFragment.selectCategory(category.id)
                                        bottomNav.selectedItemId = R.id.nav_library
                                        drawer.closeDrawers()
                                    } else Toast.makeText(this@MainActivity, "Incorrect password", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } else {
                        selectedDrawerCategoryId = category.id
                        libraryFragment.selectCategory(category.id)
                        bottomNav.selectedItemId = R.id.nav_library
                        drawer.closeDrawers()
                    }
                }
            }
            if (!categoryEditing) row.addView(label)
            if (categoryEditing) {
                val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                fun addAction(text: String, width: Int, enabled: Boolean = true, dragHandle: Boolean = false, action: () -> Unit) {
                    val button = MaterialButton(this@MainActivity).apply {
                        this.text = text
                        this.isEnabled = enabled
                        textSize = 12f
                        minWidth = 0
                        minimumWidth = 0
                        minHeight = 44
                        minimumHeight = 44
                        setPadding(4, 0, 4, 0)
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.on_surface))
                        backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.menu_button))
                        cornerRadius = 10
                        contentDescription = if (dragHandle) "Drag to reorder ${category.name}" else text
                        if (!dragHandle) {
                            setOnClickListener { action() }
                        } else {
                            var lastY = 0f
                            var moving = false
                            setOnTouchListener { _, event ->
                                when (event.actionMasked) {
                                    android.view.MotionEvent.ACTION_DOWN -> {
                                        lastY = event.rawY
                                        row.alpha = 0.82f
                                        row.elevation = 12f
                                        true
                                    }
                                    android.view.MotionEvent.ACTION_MOVE -> {
                                        val delta = event.rawY - lastY
                                        if (kotlin.math.abs(delta) >= 32f && !moving) {
                                            moving = true
                                            val direction = if (delta < 0) -1 else 1
                                            row.animate()
                                                .translationY(if (direction < 0) -24f else 24f)
                                                .setDuration(120)
                                                .withEndAction {
                                                    moving = false
                                                    repo.moveCategory(category.id, direction)
                                                    lastY = event.rawY
                                                    refreshDrawerCategories()
                                                }
                                                .start()
                                        } else {
                                            row.translationY = delta.coerceIn(-24f, 24f)
                                        }
                                        true
                                    }
                                    android.view.MotionEvent.ACTION_UP,
                                    android.view.MotionEvent.ACTION_CANCEL -> {
                                        row.animate().translationY(0f).alpha(1f).setDuration(120).start()
                                        row.elevation = 0f
                                        true
                                    }
                                    else -> true
                                }
                            }
                        }
                    }
                    actions.addView(button, LinearLayout.LayoutParams(width, 44).apply { marginEnd = 5 })
                }
                if (isHidden) {
                    addAction("Unhide", 112) {
                        repo.setCategoryHidden(category.id, false)
                        refreshDrawerCategories()
                    }
                } else {
                    val index = allCategories.indexOfFirst { it.id == category.id }
                    addAction("☷", 64, true, true) {
                        // The handle is the compact reorder control; each tap moves the item down.
                        if (index < allCategories.lastIndex) repo.moveCategory(category.id, 1)
                        refreshDrawerCategories()
                    }
                    addAction("Hide", 92) {
                        repo.setCategoryHidden(category.id, true)
                        refreshDrawerCategories()
                    }
                    if (category.isPasswordProtected && repo.isCategoryUnlocked(category.id)) {
                        addAction("Lock", 100) {
                            repo.lockCategory(category.id)
                            refreshDrawerCategories()
                            libraryFragment.clearSensitiveThumbnails()
                            libraryFragment.selectCategory(null)
                            Toast.makeText(this@MainActivity, "${category.name} locked", Toast.LENGTH_SHORT).show()
                        }
                    } else if (category.isPasswordProtected) {
                        addAction("Unlock", 100) {
                            ProtectedCategoryDialog.showUnlock(this@MainActivity, category) { password ->
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val success = repo.unlockCategory(category.id, password)
                                    runOnUiThread {
                                        password.fill(0.toChar())
                                        if (success) {
                                            Toast.makeText(this@MainActivity, "${category.name} unlocked", Toast.LENGTH_SHORT).show()
                                            refreshDrawerCategories()
                                            libraryFragment.selectCategory(category.id)
                                        } else Toast.makeText(this@MainActivity, "Incorrect password", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    } else {
                        addAction("Protect", 100) {
                            ProtectedCategoryDialog.showProtect(this@MainActivity, category) { password ->
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val success = repo.protectCategory(category.id, password)
                                    runOnUiThread {
                                        password.fill(0.toChar())
                                        if (success) {
                                            MaterialAlertDialogBuilder(this@MainActivity)
                                                .setTitle("Encrypted category created")
                                                .setMessage("The files in ${category.name} are now encrypted. If you forget the password, the files cannot be recovered.")
                                                .setPositiveButton("I understand", null)
                                                .show()
                                            refreshDrawerCategories()
                                            libraryFragment.selectCategory(null)
                                        } else Toast.makeText(this@MainActivity, "Could not protect category", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    }
                    if (!category.isSystem) addAction("Delete", 100) {
                        MaterialAlertDialogBuilder(this).setTitle("Are you sure you want to delete '${category.name}'?")
                            .setMessage("All items under this category will be moved to the Other category. The category itself will then be deleted.")
                            .setPositiveButton("Delete") { _, _ ->
                                repo.deleteCategory(category.id)
                                selectedDrawerCategoryId = null
                                refreshDrawerCategories()
                                libraryFragment.selectCategory(null)
                            }.setNegativeButton("Cancel", null).show()
                    }
                }
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = android.view.Gravity.CENTER_VERTICAL
                row.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 48))
                row.addView(label, LinearLayout.LayoutParams(0, 48).apply { weight = 1f })
            }
            drawerCategoryList.addView(row)
        }

        visible.forEach { addCategoryRow(it, false) }
        if (categoryEditing && hidden.isNotEmpty()) {
            addSectionLabel("HIDDEN CATEGORIES")
            hidden.forEach { addCategoryRow(it, true) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::drawerCategoryList.isInitialized) refreshDrawerCategories()
    }


    private fun switchFragment(target: Fragment) {
        if (activeFragment != target) {
            supportFragmentManager.beginTransaction()
                .hide(activeFragment)
                .show(target)
                .commit()
            activeFragment = target
        }
    }
}
