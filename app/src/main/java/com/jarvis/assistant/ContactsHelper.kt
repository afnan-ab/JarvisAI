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
}
