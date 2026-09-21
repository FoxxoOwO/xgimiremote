# XGIMI Remote (Elfin)

Jednoduchá Android aplikace pro ovládání projektoru XGIMI Elfin po místní síti.
Primární funkce je **zapnutí projektoru z pohotovostního režimu přes Bluetooth LE**,
stejným způsobem jako integrace
[manymuch/Xgimi-4-Home-Assistant](https://github.com/manymuch/Xgimi-4-Home-Assistant).

## Jak to funguje

### Zapnutí (BLE wake)
Projektor v pohotovostním režimu naslouchá BLE reklamám. Telefon po stisku tlačítka
~4 s vysílá reklamu identickou s tím, co pošle originální ovladač:

| Parametr | Hodnota |
|---|---|
| Manufacturer ID | `0x0046` (little-endian: `46 00`) |
| Manufacturer payload | BLE token z ovladače (max 16 bajtů) |
| Service UUID | `00001812-0000-1000-8000-00805f9b34fb` (HID) |
| Local name | `Bluetooth 4.0 RC` |
| Appearance | 961 |

### Ostatní příkazy (UDP, jen když je projektor zapnutý)
`KEYPRESSES:<kód>` na UDP port **16735** – vypnutí, hlasitost, navigace, autofocus…
(Převzato z `pyxgimi.py` výše zmíněné integrace.)

⚠️ Na některých **mezinárodních verzích** firmware (což Elfin často je) tyto UDP porty
otevřené nejsou – pak funguje jen zapnutí přes BLE a případně protokol Android TV Remote.

### Stav projektoru
TCP pokus o spojení na port **554** (RTSP). Vypnutý projektor v pohotovostním režimu
nereaguje, proto „nedostupný“ neznamená, že by zapnutí přes BLE nefungovalo.

## Získání BLE tokenu

Token je unikátní pro každý ovladač a musí se zachytit:

1. Nainstaluj **nRF Connect** do telefonu.
2. Projektorem vypni (pohotovostní režim, ne odpojený od proudu – jinak nelze zapnout).
3. V nRF Connect otevři **Scanner**.
4. Stiskni tlačítko napájení na ovladači (projektor nemusíš zapínat).
5. Najdi reklamu s **Manufacturer data 0x0046** (decimálně 70).
6. Zkopíruj hex hodnotu **za** `46 00` – např. pro Elfin (Global) typicky
   `e712973035f278ffffff3043524b544d`.

Alternativa: Home Assistant → Bluetooth → Advertisement Monitor.

## Build a instalace

1. Otevři složku `XgimiRemote` v **Android Studio**.
2. Nech Gradle sync proběhnout (stáhne AGP 8.5.2 / Kotlin 1.9.24).
3. Připoj telefon s povoleným USB laděním a stiskni **Run**.
   (Nebo Build → Build APK a nainstaluj APK ručně.)

Minimální Android 8.0 (API 26).

## Použití

1. Zadej **IP adresu projektoru** (zjistíš v routeru, nebo v projektoru Nastavení → Síť).
2. Zadej **BLE token**.
3. Tlačítkem **ZAPNOUT PROJEKTOR (BLE)** projektor probudíš – vyžaduje zapnutý Bluetooth
   a oprávnění k Bluetooth reklamě (Android 12+ se zeptá).
4. Zbytek ovládání funguje jen, když je projektor zapnutý a firmware podporuje UDP.

Nastavení (IP + token) se ukládá do zařízení.

## Struktura

```
app/src/main/java/cz/xgimiremote/
├── MainActivity.kt      # UI + logika obrazovky
└── XgimiController.kt   # BLE wake, UDP příkazy, TCP stav
```
