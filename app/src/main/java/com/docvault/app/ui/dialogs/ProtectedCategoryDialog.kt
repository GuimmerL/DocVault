package com.docvault.app.ui.dialogs

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.docvault.app.model.Category
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object ProtectedCategoryDialog {
    fun showProtect(context: Context, category: Category, onPassword: (CharArray) -> Unit) {
        val fields = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 0, 32, 0) }
        val password = EditText(context).apply { hint = "Password"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val confirm = EditText(context).apply { hint = "Confirm password"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        fields.addView(password); fields.addView(confirm)
        MaterialAlertDialogBuilder(context).setTitle("Protect ${category.name}")
            .setMessage("Documents will be encrypted and removed from the normal DocVault folder. Losing this password may make them unrecoverable.")
            .setView(fields)
            .setPositiveButton("Encrypt") { _, _ ->
                val first = password.text.toString()
                if (first.isBlank() || first != confirm.text.toString()) {
                    Toast.makeText(context, "Passwords do not match", Toast.LENGTH_SHORT).show()
                } else onPassword(first.toCharArray())
            }.setNegativeButton("Cancel", null).show()
    }

    fun showUnlock(context: Context, category: Category, onPassword: (CharArray) -> Unit) {
        val password = EditText(context).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        MaterialAlertDialogBuilder(context).setTitle("Unlock ${category.name}")
            .setView(password)
            .setPositiveButton("Unlock") { _, _ -> onPassword(password.text.toString().toCharArray()) }
            .setNegativeButton("Cancel", null).show()
    }
}
