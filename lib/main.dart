import "dart:async";
import "package:flutter/material.dart";
import "package:flutter/services.dart";
import "package:shared_preferences/shared_preferences.dart";

void main() => runApp(const RamCleanerApp());

class RamCleanerApp extends StatelessWidget {
  const RamCleanerApp({super.key});
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: "RAM Force Cleaner",
      debugShowCheckedModeBanner: false,
      theme: ThemeData(brightness: Brightness.dark, primarySwatch: Colors.cyan, scaffoldBackgroundColor: const Color(0xFF0A0E27)),
      home: const RamPage(),
    );
  }
}

class RamPage extends StatefulWidget {
  const RamPage({super.key});
  @override
  State<RamPage> createState() => _RamPageState();
}

class _RamPageState extends State<RamPage> {
  static const _channel = MethodChannel("ram_cleaner/native");
  int _totalRamMb = 0, _availRamMb = 0, _lastKilled = 0, _intervalMinutes = 0;
  bool _autoClean = false, _serviceRunning = false, _focusEnabled = false, _ignoreBattery = false;
  double _threshold = 0.80;
  String? _focusPkg, _focusLabel;
  Timer? _timer;
  bool _cleaning = false, _zramLoading = false;

  int get _usedMb => (_totalRamMb - _availRamMb).clamp(0, _totalRamMb);
  double get _usedRatio => _totalRamMb == 0 ? 0 : _usedMb / _totalRamMb;

  @override
  void initState() {
    super.initState();
    _loadStaticInfo(); _loadRuntimeRam(); _loadPrefs(); _loadFocusState();
    _timer = Timer.periodic(const Duration(seconds: 5), (_) { _loadRuntimeRam(); _checkServiceStatus(); });
  }

  Future<void> _loadStaticInfo() async {
    final total = await _channel.invokeMethod<int>("getTotalRamMb") ?? 0;
    if (mounted) setState(() => _totalRamMb = total);
  }

  Future<void> _loadRuntimeRam() async {
    try {
      final avail = await _channel.invokeMethod<int>("getAvailableRamMb") ?? 0;
      if (mounted) setState(() => _availRamMb = avail);
    } on PlatformException {
      if (mounted) setState(() => _availRamMb = (_totalRamMb * 0.5).round());
    }
  }

  Future<void> _loadPrefs() async {
    final prefs = await SharedPreferences.getInstance();
    if (mounted) setState(() {
      _autoClean = prefs.getBool("auto_clean") ?? false;
      _threshold = prefs.getDouble("threshold") ?? 0.80;
      _intervalMinutes = prefs.getInt("interval_min") ?? 0;
    });
    try { await _channel.invokeMethod("updateThreshold", {"threshold": (_threshold * 100).round()}); } catch (_) {}
  }

  Future<void> _savePrefs() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool("auto_clean", _autoClean);
    await prefs.setDouble("threshold", _threshold);
    await prefs.setInt("interval_min", _intervalMinutes);
    try { await _channel.invokeMethod("updateThreshold", {"threshold": (_threshold * 100).round()}); } catch (_) {}
  }

  Future<void> _checkServiceStatus() async {
    try {
      final running = await _channel.invokeMethod<bool>("isServiceRunning") ?? false;
      final bat = await _channel.invokeMethod<bool>("isIgnoringBattery") ?? false;
      if (mounted) setState(() { _serviceRunning = running; _ignoreBattery = bat; });
    } catch (_) {}
  }

  Future<void> _loadFocusState() async {
    try {
      final m = await _channel.invokeMapMethod<String, dynamic>("getFocusMode");
      if (m != null && mounted) setState(() {
        _focusEnabled = m["enabled"] as bool? ?? false;
        _focusPkg = m["package"] as String?;
        _focusLabel = m["label"] as String?;
      });
    } catch (_) {}
  }

  Future<void> _toggleAutoClean(bool value) async {
    setState(() => _autoClean = value);
    await _savePrefs();
    try {
      await _channel.invokeMethod("toggleAutoClean", {"enabled": value, "threshold": (_threshold * 100).round()});
      await _checkServiceStatus();
    } on PlatformException catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text("Gagal: ${e.message}")));
    }
  }

  Future<void> _toggleFocus(bool v) async {
    setState(() => _focusEnabled = v);
    await _channel.invokeMethod("setFocusMode", {"enabled": v, "package": _focusPkg, "label": _focusLabel});
  }

  Future<void> _pickFocusApp() async {
    try {
      final apps = await _channel.invokeListMethod<Map>("getInstalledApps");
      if (apps == null || !mounted) return;
      final selected = await showDialog<Map>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text("Pilih Aplikasi Fokus"),
          content: SizedBox(
            width: double.maxFinite, height: 400,
            child: ListView.builder(
              itemCount: apps.length,
              itemBuilder: (_, i) {
                final a = apps[i];
                return ListTile(
                  leading: const Icon(Icons.apps),
                  title: Text(a["label"]?.toString() ?? ""),
                  subtitle: Text(a["package"]?.toString() ?? "", style: const TextStyle(fontSize: 10)),
                  onTap: () => Navigator.pop(ctx, Map<String, dynamic>.from(a)),
                );
              },
            ),
          ),
        ),
      );
      if (selected != null) {
        setState(() { _focusPkg = selected["package"]?.toString(); _focusLabel = selected["label"]?.toString(); });
        await _channel.invokeMethod("setFocusMode", {"enabled": _focusEnabled, "package": _focusPkg, "label": _focusLabel});
      }
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text("Gagal: $e")));
    }
  }

  Future<void> _clearZram() async {
    if (_zramLoading) return;
    setState(() => _zramLoading = true);
    try {
      final res = await _channel.invokeMapMethod<String, dynamic>("clearZram");
      if (res != null && mounted) {
        final freed = res["freed"] as int? ?? 0;
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(freed > 0 ? "ZRAM dibersihkan! RAM +${freed} MB" : "ZRAM sudah optimal."), backgroundColor: Colors.green.shade700));
        _loadRuntimeRam();
      }
    } on PlatformException catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text("Gagal: ${e.message}"), backgroundColor: Colors.red.shade700));
    } finally {
      if (mounted) setState(() => _zramLoading = false);
    }
  }

  Future<void> _cleanRam() async {
    if (_cleaning) return;
    setState(() => _cleaning = true);
    try {
      final killed = await _channel.invokeMethod<int>("cleanAll") ?? 0;
      await Future.delayed(const Duration(milliseconds: 500));
      await _loadRuntimeRam();
      if (mounted) {
        setState(() => _lastKilled = killed);
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text("Berhasil membersihkan $killed aplikasi"), backgroundColor: Colors.green.shade700));
      }
    } on PlatformException catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text("Gagal: ${e.message}"), backgroundColor: Colors.red.shade700));
    } finally {
      if (mounted) setState(() => _cleaning = false);
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text("RAM Force Cleaner"), backgroundColor: Colors.transparent, elevation: 0),
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(20),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              if (!_ignoreBattery)
                GestureDetector(
                  onTap: () => _channel.invokeMethod("requestIgnoreBattery"),
                  child: Container(
                    margin: const EdgeInsets.only(bottom: 16),
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(color: Colors.red.withOpacity(0.2), borderRadius: BorderRadius.circular(12)),
                    child: const Row(children: [
                      Icon(Icons.battery_alert, color: Colors.redAccent),
                      SizedBox(width: 8),
                      Expanded(child: Text("Matikan Battery Optimization agar auto-clean berjalan lancar. Tap di sini.", style: TextStyle(fontSize: 12))),
                    ]),
                  ),
                ),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                decoration: BoxDecoration(color: _serviceRunning ? Colors.green.withOpacity(0.2) : Colors.white10, borderRadius: BorderRadius.circular(20)),
                child: Row(mainAxisSize: MainAxisSize.min, children: [
                  Icon(_serviceRunning ? Icons.check_circle : Icons.pause_circle, color: _serviceRunning ? Colors.greenAccent : Colors.white54, size: 16),
                  const SizedBox(width: 6),
                  Text(_serviceRunning ? "Service aktif" : "Service nonaktif", style: TextStyle(color: _serviceRunning ? Colors.greenAccent : Colors.white54, fontSize: 12)),
                ]),
              ),
              const SizedBox(height: 24),
              SizedBox(
                width: 200, height: 200,
                child: Stack(alignment: Alignment.center, children: [
                  SizedBox(
                    width: 200, height: 200,
                    child: CircularProgressIndicator(
                      value: _usedRatio, strokeWidth: 13, backgroundColor: Colors.white12,
                      valueColor: AlwaysStoppedAnimation(_usedRatio > 0.8 ? Colors.redAccent : _usedRatio > 0.6 ? Colors.orangeAccent : Colors.cyanAccent),
                    ),
                  ),
                  Column(mainAxisSize: MainAxisSize.min, children: [
                    Text(_cleaning ? "..." : "${(_usedRatio * 100).round()}%", style: const TextStyle(fontSize: 40, fontWeight: FontWeight.bold, color: Colors.white)),
                    const Text("digunakan", style: TextStyle(color: Colors.white54, fontSize: 12)),
                  ]),
                ]),
              ),
              const SizedBox(height: 24),
              Row(mainAxisAlignment: MainAxisAlignment.spaceEvenly, children: [
                _ramInfo("Total", "$_totalRamMb MB", Colors.cyanAccent),
                _ramInfo("Terpakai", "$_usedMb MB", Colors.orangeAccent),
                _ramInfo("Tersedia", "$_availRamMb MB", Colors.greenAccent),
              ]),
              if (_lastKilled > 0) ...[
                const SizedBox(height: 12),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
                  decoration: BoxDecoration(color: Colors.green.withOpacity(0.2), borderRadius: BorderRadius.circular(12)),
                  child: Text("✓ Terakhir: $_lastKilled aplikasi", style: const TextStyle(color: Colors.greenAccent, fontSize: 12)),
                ),
              ],
              const SizedBox(height: 24),
              Container(
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(color: Colors.white.withOpacity(0.05), borderRadius: BorderRadius.circular(16)),
                child: Column(children: [
                  Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
                    const Text("Auto-clean (RAM %)", style: TextStyle(fontSize: 14)),
                    Switch(value: _autoClean, onChanged: _toggleAutoClean, activeColor: Colors.cyanAccent),
                  ]),
                  if (_autoClean) Row(children: [
                    const Text("RAM >", style: TextStyle(fontSize: 12, color: Colors.white54)),
                    Expanded(child: Slider(
                      value: _threshold, min: 0.30, max: 0.95, divisions: 13, label: "${(_threshold * 100).round()}%", activeColor: Colors.cyanAccent,
                      onChanged: (v) async { setState(() => _threshold = v); await _savePrefs(); },
                    )),
                    Text("${(_threshold * 100).round()}%", style: const TextStyle(fontSize: 12)),
                  ]),
                  const SizedBox(height: 8),
                  Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
                    const Text("Timer Custom", style: TextStyle(fontSize: 14)),
                    DropdownButton<int>(
                      value: _intervalMinutes, dropdownColor: const Color(0xFF0A0E27), underline: const SizedBox(),
                      items: const [
                        DropdownMenuItem(value: 0, child: Text("Nonaktif", style: TextStyle(fontSize: 13))),
                        DropdownMenuItem(value: 5, child: Text("Tiap 5 Menit", style: TextStyle(fontSize: 13))),
                        DropdownMenuItem(value: 15, child: Text("Tiap 15 Menit", style: TextStyle(fontSize: 13))),
                        DropdownMenuItem(value: 30, child: Text("Tiap 30 Menit", style: TextStyle(fontSize: 13))),
                        DropdownMenuItem(value: 60, child: Text("Tiap 1 Jam", style: TextStyle(fontSize: 13))),
                      ],
                      onChanged: (v) async {
                        if (v == null) return;
                        setState(() => _intervalMinutes = v);
                        await _savePrefs();
                        await _channel.invokeMethod("updateInterval", {"interval": v});
                      },
                    ),
                  ]),
                ]),
              ),
              const SizedBox(height: 12),
              Container(
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(color: Colors.white.withOpacity(0.05), borderRadius: BorderRadius.circular(16)),
                child: Row(children: [
                  const Icon(Icons.center_focus_strong, color: Colors.white54, size: 24),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      const Text("Focus Mode", style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
                      const SizedBox(height: 2),
                      Text(_focusPkg == null ? "Belum pilih aplikasi" : "Fokus: ${_focusLabel ?? _focusPkg} (Kebal)", style: const TextStyle(fontSize: 11, color: Colors.white54)),
                    ]),
                  ),
                  TextButton(onPressed: _pickFocusApp, child: Text(_focusPkg == null ? "Pilih" : "Ganti", style: const TextStyle(fontSize: 12))),
                  Switch(value: _focusEnabled, onChanged: (_focusPkg == null) ? null : _toggleFocus, activeColor: Colors.cyanAccent),
                ]),
              ),
              const SizedBox(height: 24),
              SizedBox(
                width: double.infinity, height: 56,
                child: ElevatedButton.icon(
                  onPressed: _cleaning ? null : _cleanRam,
                  icon: _cleaning ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.black)) : const Icon(Icons.bolt),
                  label: Text(_cleaning ? "Membersihkan..." : "Bersihkan RAM Sekarang", style: const TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
                  style: ElevatedButton.styleFrom(backgroundColor: Colors.cyanAccent, foregroundColor: Colors.black, shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16))),
                ),
              ),
              const SizedBox(height: 10),
              SizedBox(
                width: double.infinity, height: 50,
                child: OutlinedButton.icon(
                  onPressed: _zramLoading ? null : _clearZram,
                  icon: _zramLoading ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)) : const Icon(Icons.memory, size: 20),
                  label: Text(_zramLoading ? "Memproses..." : "Bersihkan ZRAM & Cache", style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
                  style: OutlinedButton.styleFrom(foregroundColor: Colors.cyanAccent, side: const BorderSide(color: Colors.cyanAccent), shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16))),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _ramInfo(String label, String value, Color color) {
    return Column(children: [
      Text(value, style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: color)),
      const SizedBox(height: 3),
      Text(label, style: const TextStyle(fontSize: 11, color: Colors.white54)),
    ]);
  }
}
