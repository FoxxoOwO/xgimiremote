package cz.xgimiremote

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var ipEdit: EditText
    private lateinit var tokenEdit: EditText
    private lateinit var statusText: TextView

    private val prefs by lazy { getSharedPreferences("xgimi", MODE_PRIVATE) }

    private val advertisePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) doWake() else toast("Bez oprávnění k Bluetooth reklamě nelze projektor zapnout.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        ipEdit.setText(prefs.getString("ip", ""))
        tokenEdit.setText(prefs.getString("token", ""))
    }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun add(view: View) {
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(dp(16), dp(8), dp(16), dp(8))
            root.addView(view, lp)
        }

        add(TextView(this).apply {
            text = "XGIMI Remote (Elfin)"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
        })

        ipEdit = EditText(this).apply {
            hint = "IP adresa projektoru (např. 192.168.1.50)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        add(ipEdit)

        tokenEdit = EditText(this).apply {
            hint = "BLE token (hex, viz nápověda níže)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        add(tokenEdit)

        add(Button(this).apply {
            text = "❓ Jak získám BLE token?"
            setOnClickListener { showTokenHelp() }
        })

        statusText = TextView(this).apply { text = "Stav: neznámý" }
        add(statusText)

        add(Button(this).apply {
            text = "Zkontrolovat stav (TCP 554)"
            setOnClickListener { refreshStatus() }
        })

        add(Button(this).apply {
            text = "⏻  ZAPNOUT PROJEKTOR (BLE)"
            textSize = 20f
            setOnClickListener { onWakeClicked() }
        })

        add(Button(this).apply {
            text = "⏻  Vypnout (UDP)"
            setOnClickListener { sendUdp("poweroff") }
        })

        // Navigační kříž
        add(rowOf(
            "" to null,
            "▲" to "up",
            "" to null
        ))
        add(rowOf(
            "◀" to "left",
            "OK" to "ok",
            "▶" to "right"
        ))
        add(rowOf(
            "" to null,
            "▼" to "down",
            "" to null
        ))

        // Další tlačítka
        add(rowOf(
            "Zpět" to "back",
            "Domů" to "home",
            "Menu" to "menu"
        ))
        add(rowOf(
            "Hlasitost −" to "volumedown",
            "Ztlumit" to "volumemute",
            "Hlasitost +" to "volumeup"
        ))
        add(rowOf(
            "Auto-focus" to "autofocus",
            "" to null,
            "" to null
        ))

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun rowOf(vararg items: Pair<String, String?>): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, command) in items) {
            val b = Button(this).apply {
                text = label
                isEnabled = command != null
                if (command != null) setOnClickListener { sendUdp(command) }
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(4), dp(4), dp(4), dp(4))
            row.addView(b, lp)
        }
        return row
    }

    // ------------------------------------------------------------- Akce

    private fun savePrefs() {
        prefs.edit()
            .putString("ip", ipEdit.text.toString().trim())
            .putString("token", tokenEdit.text.toString().trim())
            .apply()
    }

    private fun onWakeClicked() {
        val token = XgimiController.parseToken(tokenEdit.text.toString())
        if (token == null) {
            toast("Zadej platný BLE token – sudý počet hex znaků (max 32), viz nápověda.")
            return
        }
        savePrefs()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = buildList {
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)
                    add(Manifest.permission.BLUETOOTH_ADVERTISE)
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                    add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (needed.isNotEmpty()) {
                advertisePermission.launch(needed.toTypedArray())
                return
            }
        }
        doWake()
    }

    private fun doWake() {
        val token = XgimiController.parseToken(tokenEdit.text.toString()) ?: return
        lifecycleScope.launch {
            statusText.text = "Stav: probouzím projektor (BLE reklama ~4 s)…"
            XgimiController.wake(this@MainActivity, token)
                .onSuccess {
                    toast("BLE wake odeslán – projektor by se měl zapnout.")
                    statusText.text = "Stav: probuzení odesláno, čekám na rozjezd…"
                    delay(10_000)
                    refreshStatus(silentIpCheck = true)
                }
                .onFailure { e ->
                    toast("Zapnutí selhalo: ${e.message}")
                    refreshStatus(silentIpCheck = true)
                }
        }
    }

    private fun refreshStatus(silentIpCheck: Boolean = false) {
        val ip = ipEdit.text.toString().trim()
        if (ip.isEmpty()) {
            if (!silentIpCheck) toast("Zadej IP adresu projektoru.")
            return
        }
        savePrefs()
        lifecycleScope.launch {
            statusText.text = "Stav: kontroluji…"
            val on = XgimiController.checkAlive(ip)
            statusText.text = if (on) {
                "Stav: ZAPNUTÝ ✅"
            } else {
                "Stav: vypnutý / nedostupný ❌ (v pohotovostním režimu neodpovídá na TCP – zapnutí funguje přes BLE)"
            }
        }
    }

    private fun sendUdp(command: String) {
        val ip = ipEdit.text.toString().trim()
        if (ip.isEmpty()) {
            toast("Zadej IP adresu projektoru.")
            return
        }
        savePrefs()
        lifecycleScope.launch {
            val ok = XgimiController.sendCommand(ip, command)
            toast(
                if (ok) "Odesláno: $command"
                else "Odeslání selhalo – projektor je vypnutý, nebo tento firmware nepodporuje UDP příkazy (typické pro mezinárodní verze)."
            )
        }
    }

    private fun showTokenHelp() {
        AlertDialog.Builder(this)
            .setTitle("Jak získat BLE token")
            .setMessage(
                "Projektor se zapíná BLE reklamou, kterou normálně posílá ovladač. Token je unikátní pro každý ovladač/projektorem a je potřeba ho zachytit:\n\n" +
                    "1. Nainstaluj do telefonu aplikaci nRF Connect (nebo nRF Connect for Mobile).\n" +
                    "2. Projektorem vypni (má být v pohotovostním režimu, ne odpojený od proudu).\n" +
                    "3. V nRF Connect otevři Scanner a sleduj reklamy.\n" +
                    "4. Stiskni na ovladači tlačítko napájení (projektor nemusíš zapínat).\n" +
                    "5. Najdi reklamu s Manufacturer data kódu 0x0046 (decimálně 70).\n" +
                    "6. Zkopíruj hex hodnotu za 0x0046 (např. e712973035f2 78ffffff3043524b544d) – bez samotného „46 00“.\n\n" +
                    "Alternativa: Home Assistant → Bluetooth → Advertisement Monitor zachytí stejný paket."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
