package de.kai_morich.simple_bluetooth_terminal

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import de.kai_morich.simple_bluetooth_terminal.SerialService.SerialBinder
import de.kai_morich.simple_bluetooth_terminal.TextUtil.HexWatcher
import java.util.ArrayDeque
import java.util.Arrays

class TerminalFragment : Fragment(), ServiceConnection, SerialListener {
    private enum class Connected {
        False, Pending, True
    }

    private var deviceAddress: String? = null
    private var service: SerialService? = null

    private var receiveText: TextView? = null
    private var ssid_input: TextView? = null
    private var password_input: TextView? = null
    private var line_token_input: TextView? = null
    private var hexWatcher: HexWatcher? = null

    private var connected = Connected.False
    private var initialStart = true
    private var hexEnabled = false
    private var pendingNewline = false
    private var newline: String? = TextUtil.newline_crlf

    /*
     * Lifecycle
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        retainInstance = true
        deviceAddress = arguments!!.getString("device")
    }

    override fun onDestroy() {
        if (connected != Connected.False) disconnect()
        activity!!.stopService(Intent(activity, SerialService::class.java))
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        if (service != null) service!!.attach(this)
        else activity!!.startService(
            Intent(
                activity,
                SerialService::class.java
            )
        ) // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    override fun onStop() {
        if (service != null && !activity!!.isChangingConfigurations) service!!.detach()
        super.onStop()
    }

    @Suppress("deprecation") // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    override fun onAttach(activity: Activity) {
        super.onAttach(activity)
        getActivity()!!.bindService(
            Intent(getActivity(), SerialService::class.java),
            this,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onDetach() {
        try {
            activity!!.unbindService(this)
        } catch (ignored: Exception) {
        }
        super.onDetach()
    }

    override fun onResume() {
        super.onResume()
        if (initialStart && service != null) {
            initialStart = false
            activity!!.runOnUiThread { this.connect() }
        }
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        service = (binder as SerialBinder).service
        service!!.attach(this)
        if (initialStart && isResumed) {
            initialStart = false
            activity!!.runOnUiThread { this.connect() }
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        service = null
    }

    /*
     * UI
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_terminal, container, false)
        receiveText =
            view.findViewById(R.id.receive_text) // TextView performance decreases with number of spans
        receiveText.setTextColor(resources.getColor(R.color.colorRecieveText)) // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance())


        //
//        sendText = view.findViewById(R.id.send_text);
//        hexWatcher = new TextUtil.HexWatcher(sendText);
//        hexWatcher.enable(hexEnabled);
//        sendText.addTextChangedListener(hexWatcher);
//        sendText.setHint(hexEnabled ? "HEX mode" : "");
        ssid_input = view.findViewById(R.id.ssid_input)
        hexWatcher = HexWatcher(ssid_input)
        hexWatcher!!.enable(hexEnabled)
        ssid_input.addTextChangedListener(hexWatcher)
        ssid_input.setHint(if (hexEnabled) "HEX mode" else "")

        password_input = view.findViewById(R.id.password_input)
        hexWatcher = HexWatcher(password_input)
        hexWatcher!!.enable(hexEnabled)
        password_input.addTextChangedListener(hexWatcher)
        password_input.setHint(if (hexEnabled) "HEX mode" else "")

        line_token_input = view.findViewById(R.id.line_token_input)
        hexWatcher = HexWatcher(line_token_input)
        hexWatcher!!.enable(hexEnabled)
        line_token_input.addTextChangedListener(hexWatcher)
        line_token_input.setHint(if (hexEnabled) "HEX mode" else "")

        val sendBtn = view.findViewById<View>(R.id.send_btn)
        sendBtn.setOnClickListener { v: View? ->
            // ここに複数の文を追加
            val ssid = ssid_input.getText().toString()
            send(ssid) // 既存の処理
            try {
                Thread.sleep(500) // 500ミリ秒 = 0.5秒
            } catch (e: InterruptedException) {
            }
            val password = password_input.getText().toString()
            send(password)
            try {
                Thread.sleep(500) // 500ミリ秒 = 0.5秒
            } catch (e: InterruptedException) {
            }
            val token = line_token_input.getText().toString()
            // 1秒後にトークンを送信
            send(token)
        }
        return view
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_terminal, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.findItem(R.id.hex).setChecked(hexEnabled)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            menu.findItem(R.id.backgroundNotification)
                .setChecked(service != null && service!!.areNotificationsEnabled())
        } else {
            menu.findItem(R.id.backgroundNotification).setChecked(true)
            menu.findItem(R.id.backgroundNotification).setEnabled(false)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        if (id == R.id.clear) {
            receiveText!!.text = ""
            return true
        } else if (id == R.id.newline) {
            val newlineNames = resources.getStringArray(R.array.newline_names)
            val newlineValues = resources.getStringArray(R.array.newline_values)
            val pos = Arrays.asList(*newlineValues).indexOf(newline)
            val builder = AlertDialog.Builder(activity)
            builder.setTitle("Newline")
            builder.setSingleChoiceItems(newlineNames, pos) { dialog: DialogInterface, item1: Int ->
                newline = newlineValues[item1]
                dialog.dismiss()
            }
            builder.create().show()
            return true
        } else if (id == R.id.hex) {
            hexEnabled = !hexEnabled
            line_token_input!!.text = ""
            hexWatcher!!.enable(hexEnabled)
            line_token_input!!.hint = if (hexEnabled) "HEX mode" else ""
            item.setChecked(hexEnabled)
            return true
        } else if (id == R.id.backgroundNotification) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!service!!.areNotificationsEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
                } else {
                    showNotificationSettings()
                }
            }
            return true
        } else {
            return super.onOptionsItemSelected(item)
        }
    }

    /*
     * Serial + UI
     */
    private fun connect() {
        try {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
            status("connecting...")
            connected = Connected.Pending
            val socket = SerialSocket(activity!!.applicationContext, device)
            service!!.connect(socket)
        } catch (e: Exception) {
            onSerialConnectError(e)
        }
    }

    private fun disconnect() {
        connected = Connected.False
        service!!.disconnect()
    }

    private fun send(str: String) {
        if (connected != Connected.True) {
            Toast.makeText(activity, "not connected", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val msg: String
            val data: ByteArray?
            if (hexEnabled) {
                val sb = StringBuilder()
                toHexString(sb, TextUtil.fromHexString(str))
                toHexString(sb, newline!!.toByteArray())
                msg = sb.toString()
                data = TextUtil.fromHexString(msg)
            } else {
                msg = str
                data = (str + newline).toByteArray()
            }
            val spn = SpannableStringBuilder(msg + '\n')
            spn.setSpan(
                ForegroundColorSpan(resources.getColor(R.color.colorSendText)),
                0,
                spn.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            receiveText!!.append(spn)
            service!!.write(data)
        } catch (e: Exception) {
            onSerialIoError(e)
        }
    }

    private fun receive(datas: ArrayDeque<ByteArray>?) {
        val spn = SpannableStringBuilder()
        for (data in datas!!) {
            if (hexEnabled) {
                spn.append(toHexString(data)).append('\n')
            } else {
                var msg = String(data)
                if (newline == TextUtil.newline_crlf && msg.length > 0) {
                    // don't show CR as ^M if directly before LF
                    msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf)
                    // special handling if CR and LF come in separate fragments
                    if (pendingNewline && msg[0] == '\n') {
                        if (spn.length >= 2) {
                            spn.delete(spn.length - 2, spn.length)
                        } else {
                            val edt = receiveText!!.editableText
                            if (edt != null && edt.length >= 2) edt.delete(
                                edt.length - 2,
                                edt.length
                            )
                        }
                    }
                    pendingNewline = msg[msg.length - 1] == '\r'
                }
                spn.append(toCaretString(msg, newline!!.length != 0))
            }
        }
        receiveText!!.append(spn)
    }

    private fun status(str: String) {
        val spn = SpannableStringBuilder(str + '\n')
        spn.setSpan(
            ForegroundColorSpan(resources.getColor(R.color.colorStatusText)),
            0,
            spn.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        receiveText!!.append(spn)
    }

    /*
     * starting with Android 14, notifications are not shown in notification bar by default when App is in background
     */
    private fun showNotificationSettings() {
        val intent = Intent()
        intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS")
        intent.putExtra("android.provider.extra.APP_PACKAGE", activity!!.packageName)
        startActivity(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (permissions.contentEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !service!!.areNotificationsEnabled()) showNotificationSettings()
    }

    /*
     * SerialListener
     */
    override fun onSerialConnect() {
        status("connected")
        connected = Connected.True
    }

    override fun onSerialConnectError(e: Exception?) {
        status("connection failed: " + e!!.message)
        disconnect()
    }

    override fun onSerialRead(data: ByteArray) {
        val datas = ArrayDeque<ByteArray>()
        datas.add(data)
        receive(datas)
    }

    override fun onSerialRead(datas: ArrayDeque<ByteArray>?) {
        receive(datas)
    }

    override fun onSerialIoError(e: Exception?) {
        status("connection lost: " + e!!.message)
        disconnect()
    }
}
