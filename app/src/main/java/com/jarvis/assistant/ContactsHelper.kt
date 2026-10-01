package com.jarvis.assistant

import android.content.Context
import android.provider.ContactsContract

class ContactsHelper(private val context: Context) {
    fun findPhoneNumber(name: String): String? {
        val resolver = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$name%")
        try {
            resolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (idx >= 0) {
                        return cursor.getString(idx)?.replace(" ", "")?.replace("-", "")
                    }
                }
            }
        } catch (e: Exception) {
            // permission not granted or other lookup failure
        }
        return null
    }

    /**
     * Finds the longest saved contact name that appears in the command tail.
     * This lets commands such as "message Haris Fnd hi" keep "Haris Fnd"
     * as the recipient while "hi" remains the message.
     */
    fun findBestDisplayNameInText(text: String): String? {
        val normalizedText = text.lowercase().replace(Regex("\\s+"), " ").trim()
        if (normalizedText.isBlank()) return null

        val resolver = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val names = linkedSetOf<String>()

        try {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                if (idx >= 0) {
                    while (cursor.moveToNext()) {
                        cursor.getString(idx)?.trim()?.takeIf { it.isNotBlank() }?.let(names::add)
                    }
                }
            }
        } catch (_: Exception) {
            return null
        }

        return names
            .filter { normalizedText.contains(it.lowercase().replace(Regex("\\s+"), " ").trim()) }
            .maxByOrNull { it.length }
    }
}
