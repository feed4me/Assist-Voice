package com.nikolay.assistvoice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Lists the person's Yandex Smart Home devices (GET /v1.0/user/info),
 * filtered to those with an on_off capability — this app only ever sends
 * on_off actions (see SmartHomeCommand's doc), so a device without that
 * capability has nothing here it could be given a voice command for.
 *
 * Each row shows the device's type, room, which Yandex-side group(s) (if
 * any) it belongs to, and whether it already has voice commands — reached
 * from SmartHomeHubActivity's "Устройства" button. Tapping a device opens
 * SmartHomeDeviceCommandsActivity for it.
 */
class SmartHomeDeviceListActivity : AppCompatActivity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, SmartHomeDeviceListActivity::class.java)
    }

    private lateinit var recycler: RecyclerView
    private var adapter: DeviceRowAdapter? = null

    /** Patches just the one row a voice command changed, live, while this
     * screen happens to be the one on screen — see SmartHomeCommandEvents'
     * doc. Registered in onStart(), unregistered in onStop(), so a
     * backgrounded instance of this screen never receives it. */
    private val commandListener = SmartHomeCommandEvents.Listener { deviceId, targetType, newValue ->
        if (targetType == SmartHomeCommand.TARGET_DEVICE) {
            adapter?.updateState(deviceId, newValue)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_smart_home_list)

        recycler = findViewById(R.id.itemsRecycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.enableRotaryScroll()
        findViewById<CurvedScrollIndicatorView>(R.id.scrollIndicator).attachTo(recycler)
        recycler.post { recycler.requestRotaryFocus() }

        showStatus("Загружаю список устройств…")
    }

    /**
     * Reloads on every entry to this screen, not just the first — a smart-
     * home voice command can only ever fire while listening on the watch
     * face (see VoiceAccessibilityService's class doc), so this screen is
     * never open at the moment one executes. Refetching here is what makes
     * the manual on/off toggle (rowOnOffToggle) show the command's actual
     * result the next time someone looks, rather than whatever was true
     * when the screen was first opened. commandListener covers the other
     * case — a command firing while this screen is already open.
     */
    override fun onStart() {
        super.onStart()
        OwnAppForegroundTracker.onActivityStarted()
        loadDevices()
        SmartHomeCommandEvents.register(commandListener)
    }

    override fun onStop() {
        SmartHomeCommandEvents.unregister(commandListener)
        OwnAppForegroundTracker.onActivityStopped()
        super.onStop()
    }

    private fun loadDevices() {
        YandexOAuthDeviceFlow.getValidAccessToken(this) { token ->
            if (isFinishing || isDestroyed) return@getValidAccessToken
            if (token == null) {
                showStatus("Авторизация истекла — подключи заново на предыдущем экране")
                return@getValidAccessToken
            }
            YandexIotClient.getDevices(token) { result ->
                if (isFinishing || isDestroyed) return@getDevices
                when (result) {
                    is DevicesResult.Success -> onDevicesLoaded(result)
                    is DevicesResult.Error -> showStatus(result.message)
                }
            }
        }
    }

    private fun onDevicesLoaded(result: DevicesResult.Success) {
        val devices = result.devices.filter { it.hasOnOff }
        val groupNameById = result.groups.associate { it.id to it.name }
        val status = if (devices.isEmpty()) "Нет устройств с поддержкой вкл/выкл" else ""
        showDevices(status, devices, groupNameById)
    }

    private fun showStatus(text: String) {
        showDevices(text, emptyList(), emptyMap())
    }

    /**
     * Updates the existing adapter's data in place when there is one,
     * rather than swapping in a fresh RecyclerView.Adapter instance on
     * every onStart() reload — replacing the adapter resets the
     * RecyclerView's scroll position to the top, which was annoying if you
     * scrolled down, opened a device, and came straight back.
     */
    private fun showDevices(status: String, devices: List<SmartHomeDevice>, groupNameById: Map<String, String>) {
        val existing = adapter
        if (existing != null) {
            existing.updateData(status, devices, groupNameById)
            return
        }
        val newAdapter = DeviceRowAdapter(this, "Устройства", status, devices, groupNameById) { device ->
            startActivity(
                SmartHomeDeviceCommandsActivity.intent(
                    this, device.id, device.name, SmartHomeCommand.TARGET_DEVICE
                )
            )
        }
        adapter = newAdapter
        recycler.adapter = newAdapter
    }
}

private class DeviceRowAdapter(
    private val context: Context,
    private val title: String,
    status: String,
    devices: List<SmartHomeDevice>,
    groupNameById: Map<String, String>,
    private val onDeviceClicked: (SmartHomeDevice) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var status = status
    private var groupNameById = groupNameById

    // Mutable so a successful manual toggle (rowOnOffToggle) can update just
    // that one device's isOn locally instead of re-fetching the whole list.
    private val devices = devices.toMutableList()

    /** Replaces the data in place — see SmartHomeDeviceListActivity.showDevices()
     * for why this is preferred over creating a new adapter on every reload. */
    fun updateData(status: String, devices: List<SmartHomeDevice>, groupNameById: Map<String, String>) {
        this.status = status
        this.groupNameById = groupNameById
        this.devices.clear()
        this.devices.addAll(devices)
        notifyDataSetChanged()
    }

    // Device ids with a toggle request currently in flight — keyed by id
    // rather than holding a ViewHolder/position across the async gap, since
    // the list can scroll (and recycle that ViewHolder onto a different
    // device entirely) while the network call is still pending.
    private val togglesInFlight = mutableSetOf<String>()

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ROW = 1
    }

    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.headerTitle)
        val status: TextView = itemView.findViewById(R.id.headerStatus)
    }

    class RowViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val root: View = itemView.findViewById(R.id.rowRoot)
        val badge: TextView = itemView.findViewById(R.id.rowDeviceBadge)
        val name: TextView = itemView.findViewById(R.id.rowDeviceName)
        val subtitle: TextView = itemView.findViewById(R.id.rowDeviceSubtitle)
        val toggle: ImageView = itemView.findViewById(R.id.rowOnOffToggle)
    }

    override fun getItemViewType(position: Int): Int = if (position == 0) TYPE_HEADER else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(R.layout.item_smart_home_list_header, parent, false))
        } else {
            RowViewHolder(inflater.inflate(R.layout.item_smart_home_device_row, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is HeaderViewHolder) {
            holder.title.text = title
            holder.status.text = status
            return
        }
        if (holder !is RowViewHolder) return

        val device = devices[position - 1]
        holder.badge.text = YandexIotClient.readableType(device.type)
        holder.name.text = device.name

        val groupNames = device.groupIds.mapNotNull { groupNameById[it] }
        val hasCommands = SmartHomePrefs.commandsForDevice(context, device.id).isNotEmpty()

        holder.subtitle.text = SpannableStringBuilder().apply {
            append(device.room.ifBlank { "Без комнаты" })
            if (groupNames.isNotEmpty()) {
                append(" · группа: ")
                append(groupNames.joinToString(", "))
            }
            append(" · команды: ")
            appendCommandStatus(context, hasCommands)
        }
        holder.root.setOnClickListener { onDeviceClicked(device) }

        // Every device in this list already has hasOnOff == true (filtered
        // in onDevicesLoaded), so the toggle is always shown here.
        holder.toggle.visibility = View.VISIBLE
        holder.toggle.isEnabled = device.id !in togglesInFlight
        holder.toggle.setColorFilter(
            ContextCompat.getColor(context, if (device.isOn == true) R.color.success else R.color.text_muted)
        )
        holder.toggle.setOnClickListener { toggleDevice(device) }
    }

    /** Always asks for a freshly-valid token at the moment of the tap
     * (same as VoiceAccessibilityService.onSmartHomeCommand) rather than
     * reusing one captured when the list first loaded, which could have
     * gone stale by the time someone taps a toggle. Looks the device back
     * up by id when the result comes back instead of holding a position or
     * ViewHolder across the async gap — see togglesInFlight's doc. */
    private fun toggleDevice(device: SmartHomeDevice) {
        if (device.id in togglesInFlight) return
        togglesInFlight.add(device.id)
        notifyDeviceChanged(device.id)

        val newValue = !(device.isOn ?: false)
        YandexOAuthDeviceFlow.getValidAccessToken(context) { token ->
            if (token == null) {
                togglesInFlight.remove(device.id)
                notifyDeviceChanged(device.id)
                Toast.makeText(context, "Авторизация истекла — подключи заново", Toast.LENGTH_SHORT).show()
                return@getValidAccessToken
            }
            val throwawayCommand = SmartHomeCommand(
                id = "",
                deviceId = device.id,
                deviceName = device.name,
                phrase = "",
                value = newValue,
                targetType = SmartHomeCommand.TARGET_DEVICE
            )
            YandexIotClient.sendAction(token, throwawayCommand) { result ->
                togglesInFlight.remove(device.id)
                when (result) {
                    is SendActionResult.Success -> updateState(device.id, newValue)
                    is SendActionResult.Error -> {
                        Toast.makeText(context, "«${device.name}»: ${result.message}", Toast.LENGTH_SHORT).show()
                        notifyDeviceChanged(device.id)
                    }
                }
            }
        }
    }

    /** Patches just this device's on/off state and re-binds its row —
     * used both by the manual toggle's own result above and by
     * SmartHomeCommandEvents when a voice command changes this device
     * while this screen happens to be the one on screen (see
     * SmartHomeDeviceListActivity's commandListener). No-ops if the
     * device isn't in this list — a different device's command, or the
     * list hasn't loaded yet. */
    fun updateState(deviceId: String, isOn: Boolean) {
        val index = devices.indexOfFirst { it.id == deviceId }
        if (index < 0) return
        devices[index] = devices[index].copy(isOn = isOn)
        notifyItemChanged(index + 1)
    }

    private fun notifyDeviceChanged(deviceId: String) {
        val index = devices.indexOfFirst { it.id == deviceId }
        if (index >= 0) notifyItemChanged(index + 1)
    }

    override fun getItemCount(): Int = devices.size + 1
}
