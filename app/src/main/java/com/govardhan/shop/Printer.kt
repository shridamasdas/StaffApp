package com.govardhan.shop

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.UUID

/** Sends ESC/POS bytes to a paired Bluetooth thermal printer (58 mm). */
class Printer(private val act: AppCompatActivity) {

    private val prefs = act.getSharedPreferences("shop", Context.MODE_PRIVATE)
    private val spp: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var pending: ByteArray? = null
    private var wantChoose = false

    private val permission = act.registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val data = pending
        val showList = wantChoose
        pending = null
        wantChoose = false
        if (!ok) {
            toast("Bluetooth permission is needed for printing")
        } else if (showList) {
            choose(data)
        } else if (data != null) {
            print(data)
        }
    }

    private fun adapter(): BluetoothAdapter? =
        (act.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(act, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun askPermission(data: ByteArray?, choose: Boolean) {
        pending = data
        wantChoose = choose
        permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun bluetoothReady(): BluetoothAdapter? {
        val bt = adapter()
        if (bt == null) { toast("This phone has no Bluetooth"); return null }
        if (!bt.isEnabled) { toast("Please turn on Bluetooth and try again"); return null }
        return bt
    }

    fun print(data: ByteArray) {
        if (!hasPermission()) { askPermission(data, false); return }
        bluetoothReady() ?: return
        val address = prefs.getString("printer", null)
        if (address == null) choose(data) else send(address, data)
    }

    @SuppressLint("MissingPermission")
    fun choose(data: ByteArray?) {
        if (!hasPermission()) { askPermission(data, true); return }
        val bt = bluetoothReady() ?: return
        val devices = bt.bondedDevices.toList()
        if (devices.isEmpty()) {
            toast("No paired printer. Pair it in the phone's Bluetooth settings first.")
            return
        }
        val names = devices.map { (it.name ?: "Unknown device") + "\n" + it.address }.toTypedArray()
        AlertDialog.Builder(act)
            .setTitle("Choose your printer")
            .setItems(names) { _, i ->
                val dev = devices[i]
                prefs.edit().putString("printer", dev.address).apply()
                toast("Printer saved: " + (dev.name ?: dev.address))
                if (data != null) send(dev.address, data)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun send(address: String, data: ByteArray) {
        toast("Printing…")
        Thread {
            val error: String? = try {
                val device = adapter()!!.getRemoteDevice(address)
                var socket: BluetoothSocket = device.createRfcommSocketToServiceRecord(spp)
                try {
                    socket.connect()
                } catch (first: Exception) {
                    socket.close()
                    socket = device.createInsecureRfcommSocketToServiceRecord(spp)   // some cheap printers need this
                    socket.connect()
                }
                socket.use {
                    it.outputStream.write(data)
                    it.outputStream.flush()
                    Thread.sleep(800)       // let the printer receive everything before closing
                }
                null
            } catch (e: Exception) {
                e.message ?: "Unknown error"
            }

            act.runOnUiThread {
                if (error == null) {
                    toast("Printed")
                } else {
                    AlertDialog.Builder(act)
                        .setTitle("Print failed")
                        .setMessage("Make sure the printer is switched on and near the phone.\n\n($error)")
                        .setPositiveButton("Try again") { _, _ -> send(address, data) }
                        .setNeutralButton("Change printer") { _, _ -> choose(data) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }.start()
    }

    private fun toast(msg: String) = Toast.makeText(act, msg, Toast.LENGTH_SHORT).show()
}
