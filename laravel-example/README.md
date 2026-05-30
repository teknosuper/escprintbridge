# Contoh Integrasi Laravel

Contoh ini memakai satu tabel antrian print sederhana. Endpoint hanya contoh dasar; sesuaikan dengan auth, logging, dan struktur POS Anda.

## 1. Migration

```php
Schema::create('print_jobs', function (Blueprint $table) {
    $table->uuid('id')->primary();
    $table->string('device_id')->index();
    $table->string('status')->default('queued')->index();
    $table->json('payload');
    $table->json('printer')->nullable();
    $table->timestamp('reserved_at')->nullable();
    $table->timestamp('completed_at')->nullable();
    $table->timestamp('failed_at')->nullable();
    $table->text('failure_reason')->nullable();
    $table->timestamps();
});
```

## 2. Routes

Tambahkan ke `routes/api.php`:

```php
use App\Http\Controllers\Api\PrintBridgeController;

Route::prefix('print-bridge')->group(function () {
    Route::get('/devices/{deviceId}/next-job', [PrintBridgeController::class, 'nextJob']);
    Route::post('/jobs/{job}/complete', [PrintBridgeController::class, 'complete']);
    Route::post('/jobs/{job}/failed', [PrintBridgeController::class, 'failed']);
});
```

## 3. Controller

```php
<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\PrintJob;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;

class PrintBridgeController extends Controller
{
    private function authorizeBridge(Request $request): void
    {
        abort_unless(
            hash_equals((string) config('services.print_bridge.key'), (string) $request->header('X-Bridge-Key')),
            401,
            'Invalid bridge key'
        );
    }

    public function nextJob(Request $request, string $deviceId)
    {
        $this->authorizeBridge($request);

        $job = DB::transaction(function () use ($deviceId) {
            $job = PrintJob::query()
                ->where('device_id', $deviceId)
                ->where('status', 'queued')
                ->orderBy('created_at')
                ->lockForUpdate()
                ->first();

            if (! $job) {
                return null;
            }

            $job->update([
                'status' => 'processing',
                'reserved_at' => now(),
            ]);

            return $job->fresh();
        });

        if (! $job) {
            return response()->json(['job' => null]);
        }

        return response()->json([
            'job' => [
                'id' => $job->id,
                'printer' => $job->printer,
                'payload' => $job->payload,
            ],
        ]);
    }

    public function complete(Request $request, PrintJob $job)
    {
        $this->authorizeBridge($request);

        $job->update([
            'status' => 'completed',
            'completed_at' => now(),
            'failure_reason' => null,
        ]);

        return response()->json(['ok' => true]);
    }

    public function failed(Request $request, PrintJob $job)
    {
        $this->authorizeBridge($request);

        $job->update([
            'status' => 'failed',
            'failed_at' => now(),
            'failure_reason' => $request->string('reason')->toString(),
        ]);

        return response()->json(['ok' => true]);
    }
}
```

## 4. Menambah job print dari Laravel

Jika Android hanya berperan sebagai bridge printer, kirim payload final dari server dan biarkan Android hanya meneruskan byte ke printer.

Contoh prioritas tertinggi: `raw_base64` ESC/POS dari backend.

```php
use App\Models\PrintJob;
use Illuminate\Support\Str;

PrintJob::create([
    'id' => (string) Str::uuid(),
    'device_id' => 'kasir-android-01',
    'status' => 'queued',
    'printer' => [
        'mode' => 'tcp',
        'host' => '192.168.1.50',
        'port' => 9100,
        'timeout_ms' => 5000,
    ],
    'payload' => [
        'lines' => [
            ['text' => 'TOKO MAJU', 'align' => 'center', 'bold' => true],
            ['text' => '1x Kopi Susu      18.000'],
            ['text' => 'TOTAL             18.000', 'bold' => true],
            ['feed' => 3],
            ['cut' => 'full'],
        ],
    ],
]);
```

Catatan:

- Untuk queue POS yang ingin hasil print persis mengikuti sistem web, jangan kirim `layout` untuk dirender Android.
- Kirim `payload.raw_base64` sebagai hasil final ESC/POS dari backend.
- Alternatif kedua adalah `payload.lines`, tetapi format akhirnya tetap dibentuk dari baris yang Anda kirim.

Contoh job queue POS yang mengikuti format web/server sepenuhnya:

```php
PrintJob::create([
    'id' => (string) Str::uuid(),
    'device_id' => 'kasir-android-01',
    'status' => 'queued',
    'printer' => [
        'mode' => 'bluetooth',
        'bluetooth_mac_address' => 'DC:0D:30:12:34:56',
    ],
    'payload' => [
        // Hasil akhir ESC/POS dari sistem web/server Anda
        'raw_base64' => base64_encode($escPosBytes),
    ],
]);
```

Contoh job ke printer Bluetooth yang sudah pernah dipairing di device Android:

```php
PrintJob::create([
    'id' => (string) Str::uuid(),
    'device_id' => 'kasir-android-01',
    'status' => 'queued',
    'printer' => [
        'mode' => 'bluetooth',
        'bluetooth_mac_address' => 'DC:0D:30:12:34:56',
    ],
    'payload' => [
        'lines' => [
            ['text' => 'Bluetooth Print', 'align' => 'center', 'bold' => true],
            ['feed' => 3],
            ['cut' => 'full'],
        ],
    ],
]);
```

## 5. Config

Tambahkan ke `config/services.php`:

```php
'print_bridge' => [
    'key' => env('PRINT_BRIDGE_KEY'),
],
```

Tambahkan ke `.env`:

```env
PRINT_BRIDGE_KEY=isi_dengan_token_acak_yang_panjang
```
