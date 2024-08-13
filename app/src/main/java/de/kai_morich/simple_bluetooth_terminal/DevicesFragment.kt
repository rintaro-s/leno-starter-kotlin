package de.kai_morich.simple_bluetooth_terminal

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.Fragment
import androidx.fragment.app.ListFragment
import de.kai_morich.simple_bluetooth_terminal.BluetoothUtil.PermissionGrantedCallback
import de.kai_morich.simple_bluetooth_terminal.BluetoothUtil.hasPermissions
import de.kai_morich.simple_bluetooth_terminal.BluetoothUtil.onPermissionsResult
import java.util.Collections

class DevicesFragment : ListFragment() {
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val listItems = ArrayList<BluetoothDevice>()
    private var listAdapter: ArrayAdapter<BluetoothDevice>? = null
    var requestBluetoothPermissionLauncherForRefresh: ActivityResultLauncher<String?>? = null
    private var menu: Menu? = null
    private var permissionMissing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        if (activity!!.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) bluetoothAdapter =
            BluetoothAdapter.getDefaultAdapter()
        listAdapter = object : ArrayAdapter<BluetoothDevice?>(activity!!, 0, listItems) {
            override fun getView(position: Int, view: View?, parent: ViewGroup): View {
                var view = view
                val device = listItems[position]
                if (view == null) view =
                    activity!!.layoutInflater.inflate(R.layout.device_list_item, parent, false)
                val text1 = view!!.findViewById<TextView>(R.id.text1)
                val text2 = view.findViewById<TextView>(R.id.text2)
                @SuppressLint("MissingPermission") val deviceName = device.name
                text1.text = deviceName
                text2.text = device.address
                return view
            }
        }
        requestBluetoothPermissionLauncherForRefresh = registerForActivityResult<String?, Boolean>(
            RequestPermission()
        ) { granted: Boolean? ->
            onPermissionsResult(
                this,
                granted!!,
                PermissionGrantedCallback { this.refresh() })
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        setListAdapter(null)
        val header = activity!!.layoutInflater.inflate(R.layout.device_list_header, null, false)
        listView.addHeaderView(header, null, false)
        setEmptyText("initializing...")
        (listView.emptyView as TextView).textSize = 18f
        setListAdapter(listAdapter)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        this.menu = menu
        inflater.inflate(R.menu.menu_devices, menu)
        if (permissionMissing) menu.findItem(R.id.bt_refresh).setVisible(true)
        if (bluetoothAdapter == null) menu.findItem(R.id.bt_settings).setEnabled(false)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        if (id == R.id.bt_settings) {
            val intent = Intent()
            intent.setAction(Settings.ACTION_BLUETOOTH_SETTINGS)
            startActivity(intent)
            return true
        } else if (id == R.id.bt_refresh) {
            if (hasPermissions(this, requestBluetoothPermissionLauncherForRefresh!!)) refresh()
            return true
        } else {
            return super.onOptionsItemSelected(item)
        }
    }

    @SuppressLint("MissingPermission")
    fun refresh() {
        listItems.clear()
        if (bluetoothAdapter != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissionMissing =
                    activity!!.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                if (menu != null && menu!!.findItem(R.id.bt_refresh) != null) menu!!.findItem(R.id.bt_refresh)
                    .setVisible(permissionMissing)
            }
            if (!permissionMissing) {
                for (device in bluetoothAdapter!!.bondedDevices) if (device.type != BluetoothDevice.DEVICE_TYPE_LE) listItems.add(
                    device
                )
                Collections.sort(
                    listItems,
                    java.util.Comparator { obj: BluetoothDevice, a: BluetoothDevice? ->
                        obj.compareTo(a)
                    })
            }
        }
        if (bluetoothAdapter == null) setEmptyText("<bluetooth not supported>")
        else if (!bluetoothAdapter!!.isEnabled) setEmptyText("<bluetooth is disabled>")
        else if (permissionMissing) setEmptyText("<permission missing, use REFRESH>")
        else setEmptyText("<no bluetooth devices found>")
        listAdapter!!.notifyDataSetChanged()
    }

    override fun onListItemClick(l: ListView, v: View, position: Int, id: Long) {
        val device = listItems[position - 1]
        val args = Bundle()
        args.putString("device", device.address)
        val fragment: Fragment = TerminalFragment()
        fragment.arguments = args
        parentFragmentManager.beginTransaction().replace(R.id.fragment, fragment, "terminal")
            .addToBackStack(null).commit()
    }
}
