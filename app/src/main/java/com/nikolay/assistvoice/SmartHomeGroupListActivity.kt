package com.nikolay.assistvoice

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
 * Lists the person's Yandex-side device groups (GET /v1.0/user/info's
 * `groups` array — see SmartHomeGroup's doc), filtered to those with an
 * on_off capability. A group's on_off fans out to every device in it — this
 * screen exists specifically so a command can target that fan-out
 * deliberately (e.g. "весь свет в кабинете") instead of it only ever
 * showing up as a surprise when a single device turns out to be one.
 *
 * Reached from SmartHomeHubActivity's "Группы устройств" button. Tapping a
 * group opens SmartHomeDeviceCommandsActivity for it, same screen a device
 * uses — SmartHomeCommand.targetType is what tells it (and
 * VoiceAccessibilityService/YandexIotClient later) this is a group, not a
 * device.
 */
class SmartHomeGroupListActivity : AppCompatActivity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, SmartHomeGroupListActivity::class.java)
    }

    private lateinit var recycler: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_smart_home_list)

        recycler = findViewById(R.id.itemsRecycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.enableRotaryScroll()
        findViewById<CurvedScrollIndicatorView>(R.id.scrollIndicator).attachTo(recycler)
        recycler.post { recycler.requestRotaryFocus() }

        showStatus("Загружаю список групп…")
        loadGroups()
    }

    override fun onStart() {
        super.onStart()
        OwnAppForegroundTracker.onActivityStarted()
    }

    override fun onStop() {
        OwnAppForegroundTracker.onActivityStopped()
        super.onStop()
    }

    private fun loadGroups() {
        YandexOAuthDeviceFlow.getValidAccessToken(this) { token ->
            if (isFinishing || isDestroyed) return@getValidAccessToken
            if (token == null) {
                showStatus("Авторизация истекла — подключи заново на предыдущем экране")
                return@getValidAccessToken
            }
            YandexIotClient.getDevices(token) { result ->
                if (isFinishing || isDestroyed) return@getDevices
                when (result) {
                    is DevicesResult.Success -> onGroupsLoaded(result.groups.filter { it.hasOnOff })
                    is DevicesResult.Error -> showStatus(result.message)
                }
            }
        }
    }

    private fun onGroupsLoaded(groups: List<SmartHomeGroup>) {
        val status = if (groups.isEmpty()) "Нет групп с поддержкой вкл/выкл" else ""
        recycler.adapter = GroupRowAdapter(this, status, groups) { group ->
            startActivity(
                SmartHomeDeviceCommandsActivity.intent(
                    this, group.id, group.name, SmartHomeCommand.TARGET_GROUP
                )
            )
        }
    }

    private fun showStatus(text: String) {
        recycler.adapter = GroupRowAdapter(this, text, emptyList()) {}
    }
}

private class GroupRowAdapter(
    private val context: Context,
    private val status: String,
    groups: List<SmartHomeGroup>,
    private val onGroupClicked: (SmartHomeGroup) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    // Mutable so a successful manual toggle (rowOnOffToggle) can update just
    // that one group's isOn locally instead of re-fetching the whole list.
    private val groups = groups.toMutableList()

    // Same reasoning as DeviceRowAdapter.togglesInFlight — keyed by id
    // rather than a held ViewHolder/position, since the list can scroll
    // (recycling that ViewHolder onto a different group) while the network
    // call is still pending.
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
            holder.title.text = "Группы устройств"
            holder.status.text = status
            return
        }
        if (holder !is RowViewHolder) return

        val group = groups[position - 1]
        holder.badge.text = "Группа · ${YandexIotClient.readableType(group.type)}"
        holder.name.text = group.name

        val commandCount = SmartHomePrefs.commandsForDevice(context, group.id).size
        holder.subtitle.text = if (commandCount == 0) "нет команд" else "команд: $commandCount"
        holder.root.setOnClickListener { onGroupClicked(group) }

        // Every group in this list already has hasOnOff == true (filtered
        // in loadGroups), so the toggle is always shown here.
        holder.toggle.visibility = View.VISIBLE
        holder.toggle.isEnabled = group.id !in togglesInFlight
        holder.toggle.setColorFilter(
            ContextCompat.getColor(context, if (group.isOn == true) R.color.success else R.color.text_muted)
        )
        holder.toggle.setOnClickListener { toggleGroup(group) }
    }

    /** Same reasoning as DeviceRowAdapter.toggleDevice() — fresh token per
     * tap, group looked back up by id when the result arrives rather than
     * holding a position/ViewHolder across the async gap. Fans out to every
     * member device, same as a voice command on this group would (see
     * SmartHomeGroup's doc). */
    private fun toggleGroup(group: SmartHomeGroup) {
        if (group.id in togglesInFlight) return
        togglesInFlight.add(group.id)
        notifyGroupChanged(group.id)

        val newValue = !(group.isOn ?: false)
        YandexOAuthDeviceFlow.getValidAccessToken(context) { token ->
            if (token == null) {
                togglesInFlight.remove(group.id)
                notifyGroupChanged(group.id)
                Toast.makeText(context, "Авторизация истекла — подключи заново", Toast.LENGTH_SHORT).show()
                return@getValidAccessToken
            }
            val throwawayCommand = SmartHomeCommand(
                id = "",
                deviceId = group.id,
                deviceName = group.name,
                phrase = "",
                value = newValue,
                targetType = SmartHomeCommand.TARGET_GROUP
            )
            YandexIotClient.sendAction(token, throwawayCommand) { result ->
                togglesInFlight.remove(group.id)
                when (result) {
                    is SendActionResult.Success -> {
                        val index = groups.indexOfFirst { it.id == group.id }
                        if (index >= 0) groups[index] = groups[index].copy(isOn = newValue)
                    }
                    is SendActionResult.Error -> {
                        Toast.makeText(context, "«${group.name}»: ${result.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                notifyGroupChanged(group.id)
            }
        }
    }

    private fun notifyGroupChanged(groupId: String) {
        val index = groups.indexOfFirst { it.id == groupId }
        if (index >= 0) notifyItemChanged(index + 1)
    }

    override fun getItemCount(): Int = groups.size + 1
}
