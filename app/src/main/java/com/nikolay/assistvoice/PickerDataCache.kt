package com.nikolay.assistvoice

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Process-lifetime cache for the installed-app and contact lists used by
 * MainActivity's slot list (to resolve a LAUNCH_APP slot's package name to a
 * label) and by SlotEditActivity's app/contact pickers.
 *
 * Building these lists is not cheap: AppPickerHelper.listLaunchableApps()
 * calls loadLabel() for every launchable package — opening and reading each
 * APK's resources — and ContactsHelper.listContacts() queries the contacts
 * content provider. Both are slow enough on this watch's CPU to be a
 * noticeable delay if re-run on every app/contact picker open, for lists
 * that essentially never change between one slot edit and the next.
 *
 * Scope: lives for as long as the process does — a plain in-memory cache,
 * not persisted. Refreshing is entirely manual: the "Синхронизировать
 * приложения и контакты" button on the slot-list page (see
 * SlotsAdapter.SlotListViewHolder) is the only thing that invalidates it —
 * installing an app or editing a contact while the watch is being worn is
 * rare enough not to be worth an automatic invalidation path, and a button
 * gives an obvious, debuggable "did the sync actually happen" moment.
 *
 * IMPORTANT: ensureLoaded() calls [onUpdated] even when nothing needed to be
 * (re)loaded — i.e. when the cache was already warm. Callers such as
 * SlotEditActivity rely on this to copy the already-cached data into their
 * own fields on every call, not just on an actual fresh load.
 */
object PickerDataCache {

    @Volatile
    var apps: List<InstalledApp> = emptyList()
        private set

    @Volatile
    var contacts: List<Contact> = emptyList()
        private set

    @Volatile
    private var appsLoaded = false

    @Volatile
    private var contactsLoaded = false

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Ensures both lists are current for this caller and, either way, calls
     * [onUpdated] on the main thread — once immediately if a list was
     * already cached, once (later, off the load) for a list that needed a
     * fresh query. A caller that only needs contacts once permission is
     * granted passes [needContacts] = false until then, exactly as before
     * this cache existed.
     */
    fun ensureLoaded(context: Context, needContacts: Boolean, onUpdated: () -> Unit) {
        val appContext = context.applicationContext

        if (appsLoaded) {
            onUpdated()
        } else {
            executor.execute {
                val loaded = try {
                    AppPickerHelper.listLaunchableApps(appContext)
                } catch (e: Exception) {
                    emptyList()
                }
                apps = loaded
                appsLoaded = true
                mainHandler.post { onUpdated() }
            }
        }

        if (needContacts) {
            if (contactsLoaded) {
                onUpdated()
            } else {
                executor.execute {
                    val loaded = try {
                        ContactsHelper.listContacts(appContext)
                    } catch (e: Exception) {
                        emptyList()
                    }
                    contacts = loaded
                    contactsLoaded = true
                    mainHandler.post { onUpdated() }
                }
            }
        }
    }

    /** Forces the next ensureLoaded() call to re-query both lists. */
    fun invalidate() {
        appsLoaded = false
        contactsLoaded = false
    }
}
