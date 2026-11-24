package com.jarvis.assistant.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone

/** Reads the phone's contacts locally. Call from a worker thread. */
class ContactResolver(context: Context) {
    private val app = context.applicationContext

    fun hasContactsPermission() = granted(Manifest.permission.READ_CONTACTS)

    private fun granted(p: String) = app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun contacts(): List<ContactEntry> = query()

    fun stillHasNumber(contact: ContactEntry, number: String): Boolean =
        query(contact.id).singleOrNull()?.let { current ->
            current.name == contact.name && current.numbers.any { ContactMatcher.dialableNumber(it.number) == number }
        } == true

    private fun query(contactId: Long? = null): List<ContactEntry> {
        if (!hasContactsPermission()) return emptyList()
        val byId = LinkedHashMap<Long, Pair<String, MutableList<PhoneNumber>>>()
        val projection = arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.IS_PRIMARY)
        app.contentResolver.query(
            Phone.CONTENT_URI, projection,
            contactId?.let { "${Phone.CONTACT_ID} = ?" },
            contactId?.let { arrayOf(it.toString()) },
            "${Phone.DISPLAY_NAME} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: continue
                val number = c.getString(2)?.trim().orEmpty()
                if (number.isEmpty()) continue
                val type = when (c.getInt(3)) {
                    Phone.TYPE_MOBILE -> "mobile"
                    Phone.TYPE_HOME -> "home"
                    Phone.TYPE_WORK, Phone.TYPE_WORK_MOBILE -> "work"
                    else -> "other"
                }
                val entry = byId.getOrPut(id) { name to ArrayList() }
                entry.second += PhoneNumber(number, type, c.getInt(4) != 0)
            }
        }
        return byId.map { (id, v) -> ContactEntry(id, v.first, ContactMatcher.distinctNumbers(v.second)) }
    }
}
