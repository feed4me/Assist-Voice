package com.nikolay.assistvoice

import android.content.Context
import android.provider.ContactsContract

data class Contact(
    val name: String,
    val phoneNumber: String
)

/**
 * Lists contacts (name + phone number) for the contact-picker spinner
 * on a CALL slot. Requires READ_CONTACTS to already be granted —
 * callers should check that first.
 */
object ContactsHelper {

    fun listContacts(context: Context): List<Contact> {
        val result = mutableListOf<Contact>()
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )
        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name = if (nameIndex >= 0) it.getString(nameIndex) else null
                val number = if (numberIndex >= 0) it.getString(numberIndex) else null
                if (!name.isNullOrBlank() && !number.isNullOrBlank()) {
                    result.add(Contact(name, number))
                }
            }
        }
        // A person can have multiple numbers; keep the list as-is rather
        // than deduplicating by name, so all numbers remain selectable.
        return result
    }
}
