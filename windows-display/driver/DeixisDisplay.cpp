// Deixis Display — виртуальный монитор Windows для Deixis.
//
// Windows видит его как настоящий второй монитор 1920×1080: Holyrics, PowerPoint и любые программы выводят туда
// свой «проектор», а Deixis снимает этот монитор и раздаёт на экраны в сети и зрителям.
//
// Драйвер написан для Deixis по документации Microsoft к Indirect Display Driver (IddCx, UMDF 2):
// https://learn.microsoft.com/windows-hardware/drivers/display/indirect-display-driver-model-overview
// Код примера Microsoft (MS-PL) не использовался. Лицензия — GPL-3.0-or-later, как у всего Deixis.
//
// Как устроен:
//  - PnP создаёт устройство Root\DeixisDisplay (его заводит помощник установки, deixis-display.exe);
//  - при включении (D0) драйвер регистрирует адаптер IddCx, а когда тот готов — подключает один монитор с EDID «Deixis»;
//  - Windows присылает цепочку кадров (swap chain). Кадры здесь не нужны — Deixis снимает рабочий стол сам, —
//    но их обязательно забирать и отпускать, иначе монитор «зависает». Этим занят отдельный поток.

#define NOMINMAX
#include <windows.h>
#include <bugcodes.h>
#include <wudfwdm.h>
#include <wdf.h>
#include <iddcx.h>

#include <dxgi1_5.h>
#include <d3d11_2.h>
#include <avrt.h>
#include <wrl/client.h>

using Microsoft::WRL::ComPtr;

namespace {

// Режимы монитора. Первый — основной: в нём Windows включает монитор.
struct Mode { UINT width, height, hz; };
constexpr Mode kModes[] = { {1920, 1080, 60}, {1600, 900, 60}, {1280, 720, 60}, {2560, 1440, 30}, {3840, 2160, 30} };
constexpr UINT kModeCount = ARRAYSIZE(kModes);

// EDID 1.4 на 128 байт: производитель «DXS», модель 1, имя «Deixis», основной режим 1920×1080 при 60 Гц.
// Собирается по стандарту VESA E-EDID, контрольная сумма — в последнем байте.
struct Edid {
    BYTE data[128] = {};

    Edid() {
        const BYTE header[8] = {0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x00};
        memcpy(data, header, 8);
        // Код производителя — три буквы по 5 бит: D=4, X=24, S=19.
        const UINT16 maker = (4 << 10) | (24 << 5) | 19;
        data[8] = maker >> 8; data[9] = maker & 0xFF;
        data[10] = 0x01; data[11] = 0x00;                    // модель 1
        data[12] = 0x01;                                      // серийный номер 1
        data[16] = 1; data[17] = 2026 - 1990;                 // неделя и год выпуска
        data[18] = 1; data[19] = 4;                           // EDID 1.4
        data[20] = 0xA5;                                      // цифровой вход, 8 бит на цвет, DisplayPort
        data[21] = 53; data[22] = 30;                         // размер экрана, см (как у 24″)
        data[23] = 120;                                       // гамма 2.2
        data[24] = 0x06;                                      // RGB, основной режим — в первом описании
        const BYTE srgb[10] = {0xEE, 0x91, 0xA3, 0x54, 0x4C, 0x99, 0x26, 0x0F, 0x50, 0x54};
        memcpy(data + 25, srgb, 10);                          // цвета sRGB
        for (int i = 38; i < 54; i++) data[i] = 0x01;          // стандартные режимы не заданы

        // Основной режим: 1920×1080, 60 Гц (CEA-861, тактовая 148,5 МГц).
        BYTE* t = data + 54;
        const UINT clock = 14850;                             // в единицах 10 кГц
        t[0] = clock & 0xFF; t[1] = clock >> 8;
        const UINT ha = 1920, hb = 280, va = 1080, vb = 45;
        t[2] = ha & 0xFF; t[3] = hb & 0xFF; t[4] = ((ha >> 8) << 4) | (hb >> 8);
        t[5] = va & 0xFF; t[6] = vb & 0xFF; t[7] = ((va >> 8) << 4) | (vb >> 8);
        t[8] = 88; t[9] = 44; t[10] = (4 << 4) | 5; t[11] = 0;
        const UINT wmm = 531, hmm = 299;
        t[12] = wmm & 0xFF; t[13] = hmm & 0xFF; t[14] = ((wmm >> 8) << 4) | (hmm >> 8);
        t[17] = 0x1E;                                         // раздельная синхронизация, обе положительные

        // Имя монитора.
        BYTE* n = data + 72;
        n[3] = 0xFC;
        const char name[13] = {'D', 'e', 'i', 'x', 'i', 's', '\n', ' ', ' ', ' ', ' ', ' ', ' '};
        memcpy(n + 5, name, 13);

        // Пределы частот: 24–75 Гц по вертикали, 15–81 кГц по горизонтали, до 600 МГц.
        BYTE* r = data + 90;
        r[3] = 0xFD; r[5] = 24; r[6] = 75; r[7] = 15; r[8] = 81; r[9] = 60; r[10] = 0x00; r[11] = 0x0A;
        for (int i = 12; i < 18; i++) r[i] = 0x20;

        // Пустое описание.
        data[108 + 3] = 0x10;

        BYTE sum = 0;
        for (int i = 0; i < 127; i++) sum += data[i];
        data[127] = static_cast<BYTE>(0x100 - sum);
    }
};

const Edid kEdid;

DISPLAYCONFIG_VIDEO_SIGNAL_INFO Signal(const Mode& m) {
    DISPLAYCONFIG_VIDEO_SIGNAL_INFO s = {};
    s.activeSize.cx = s.totalSize.cx = m.width;
    s.activeSize.cy = s.totalSize.cy = m.height;
    s.vSyncFreq.Numerator = m.hz;
    s.vSyncFreq.Denominator = 1;
    s.hSyncFreq.Numerator = m.hz * m.height;
    s.hSyncFreq.Denominator = 1;
    s.pixelRate = static_cast<UINT64>(m.hz) * m.width * m.height;
    s.AdditionalSignalInfo.videoStandard = 255;
    s.AdditionalSignalInfo.vSyncFreqDivider = 1;
    s.scanLineOrdering = DISPLAYCONFIG_SCANLINE_ORDERING_PROGRESSIVE;
    return s;
}

// Забирает кадры монитора и сразу отпускает их: Windows ждёт, что кадры потребляются.
class FrameDrain {
public:
    FrameDrain(IDDCX_SWAPCHAIN swapChain, HANDLE frameReady, LUID adapter)
        : swapChain_(swapChain), frameReady_(frameReady), adapter_(adapter) {
        stop_ = CreateEventW(nullptr, TRUE, FALSE, nullptr);
        thread_ = CreateThread(nullptr, 0, &FrameDrain::Run, this, 0, nullptr);
    }

    ~FrameDrain() {
        if (stop_) SetEvent(stop_);
        if (thread_) { WaitForSingleObject(thread_, INFINITE); CloseHandle(thread_); }
        if (stop_) CloseHandle(stop_);
    }

private:
    static DWORD CALLBACK Run(LPVOID self) {
        static_cast<FrameDrain*>(self)->Loop();
        return 0;
    }

    void Loop() {
        DWORD taskIndex = 0;
        HANDLE task = AvSetMmThreadCharacteristicsW(L"Distribution", &taskIndex);

        ComPtr<ID3D11Device> device;
        if (!CreateDevice(device)) {
            // Без устройства цепочка кадров бесполезна: удаляем её, Windows выдаст новую.
            WdfObjectDelete(reinterpret_cast<WDFOBJECT>(swapChain_));
        } else {
            ComPtr<IDXGIDevice> dxgi;
            device.As(&dxgi);
            IDARG_IN_SWAPCHAINSETDEVICE set = {};
            set.pDevice = dxgi.Get();
            if (FAILED(IddCxSwapChainSetDevice(swapChain_, &set))) {
                WdfObjectDelete(reinterpret_cast<WDFOBJECT>(swapChain_));
            } else {
                Drain();
            }
        }
        if (task) AvRevertMmThreadCharacteristics(task);
    }

    bool CreateDevice(ComPtr<ID3D11Device>& device) {
        ComPtr<IDXGIFactory5> factory;
        if (FAILED(CreateDXGIFactory2(0, IID_PPV_ARGS(&factory)))) return false;
        ComPtr<IDXGIAdapter1> adapter;
        if (FAILED(factory->EnumAdapterByLuid(adapter_, IID_PPV_ARGS(&adapter)))) return false;
        return SUCCEEDED(D3D11CreateDevice(adapter.Get(), D3D_DRIVER_TYPE_UNKNOWN, nullptr, D3D11_CREATE_DEVICE_BGRA_SUPPORT,
                                           nullptr, 0, D3D11_SDK_VERSION, &device, nullptr, nullptr));
    }

    void Drain() {
        HANDLE waits[] = {frameReady_, stop_};
        for (;;) {
            IDARG_OUT_RELEASEANDACQUIREBUFFER buffer = {};
            HRESULT hr = IddCxSwapChainReleaseAndAcquireBuffer(swapChain_, &buffer);
            if (hr == E_PENDING) {
                DWORD w = WaitForMultipleObjects(ARRAYSIZE(waits), waits, FALSE, 16);
                if (w == WAIT_OBJECT_0 + 1) break;      // монитор отключают
                continue;
            }
            if (FAILED(hr)) break;                       // цепочка потеряна: Windows выдаст новую
            if (buffer.MetaData.pSurface) buffer.MetaData.pSurface->Release();
            IddCxSwapChainFinishedProcessingFrame(swapChain_);
            if (WaitForSingleObject(stop_, 0) == WAIT_OBJECT_0) break;
        }
    }

    IDDCX_SWAPCHAIN swapChain_;
    HANDLE frameReady_;
    LUID adapter_;
    HANDLE stop_ = nullptr;
    HANDLE thread_ = nullptr;
};

// Один адаптер и один монитор на устройство: состояние — здесь.
IDDCX_ADAPTER g_adapter = nullptr;
IDDCX_MONITOR g_monitor = nullptr;
FrameDrain* g_drain = nullptr;
SRWLOCK g_lock = SRWLOCK_INIT;

void FillMonitorModes(IDDCX_MONITOR_MODE* out, IDDCX_MONITOR_MODE_ORIGIN origin) {
    for (UINT i = 0; i < kModeCount; i++) {
        out[i] = {};
        out[i].Size = sizeof(IDDCX_MONITOR_MODE);
        out[i].Origin = origin;
        out[i].MonitorVideoSignalInfo = Signal(kModes[i]);
    }
}

}  // namespace

extern "C" {

DRIVER_INITIALIZE DriverEntry;
EVT_WDF_DRIVER_DEVICE_ADD DeixisDeviceAdd;
EVT_WDF_DEVICE_D0_ENTRY DeixisD0Entry;
EVT_IDD_CX_ADAPTER_INIT_FINISHED DeixisAdapterInitFinished;
EVT_IDD_CX_ADAPTER_COMMIT_MODES DeixisCommitModes;
EVT_IDD_CX_PARSE_MONITOR_DESCRIPTION DeixisParseMonitorDescription;
EVT_IDD_CX_MONITOR_GET_DEFAULT_DESCRIPTION_MODES DeixisDefaultModes;
EVT_IDD_CX_MONITOR_QUERY_TARGET_MODES DeixisQueryTargetModes;
EVT_IDD_CX_MONITOR_ASSIGN_SWAPCHAIN DeixisAssignSwapChain;
EVT_IDD_CX_MONITOR_UNASSIGN_SWAPCHAIN DeixisUnassignSwapChain;

NTSTATUS DriverEntry(PDRIVER_OBJECT driverObject, PUNICODE_STRING registryPath) {
    WDF_DRIVER_CONFIG config;
    WDF_DRIVER_CONFIG_INIT(&config, DeixisDeviceAdd);
    return WdfDriverCreate(driverObject, registryPath, WDF_NO_OBJECT_ATTRIBUTES, &config, WDF_NO_HANDLE);
}

NTSTATUS DeixisDeviceAdd(WDFDRIVER, PWDFDEVICE_INIT init) {
    WDF_PNPPOWER_EVENT_CALLBACKS pnp;
    WDF_PNPPOWER_EVENT_CALLBACKS_INIT(&pnp);
    pnp.EvtDeviceD0Entry = DeixisD0Entry;
    WdfDeviceInitSetPnpPowerEventCallbacks(init, &pnp);

    IDD_CX_CLIENT_CONFIG config;
    IDD_CX_CLIENT_CONFIG_INIT(&config);
    config.EvtIddCxAdapterInitFinished = DeixisAdapterInitFinished;
    config.EvtIddCxAdapterCommitModes = DeixisCommitModes;
    config.EvtIddCxParseMonitorDescription = DeixisParseMonitorDescription;
    config.EvtIddCxMonitorGetDefaultDescriptionModes = DeixisDefaultModes;
    config.EvtIddCxMonitorQueryTargetModes = DeixisQueryTargetModes;
    config.EvtIddCxMonitorAssignSwapChain = DeixisAssignSwapChain;
    config.EvtIddCxMonitorUnassignSwapChain = DeixisUnassignSwapChain;

    NTSTATUS status = IddCxDeviceInitConfig(init, &config);
    if (!NT_SUCCESS(status)) return status;

    WDF_OBJECT_ATTRIBUTES attributes;
    WDF_OBJECT_ATTRIBUTES_INIT(&attributes);
    WDFDEVICE device = nullptr;
    status = WdfDeviceCreate(&init, &attributes, &device);
    if (!NT_SUCCESS(status)) return status;
    return IddCxDeviceInitialize(device);
}

// Устройство включилось: регистрируем адаптер. Монитор подключится, когда адаптер будет готов.
NTSTATUS DeixisD0Entry(WDFDEVICE device, WDF_POWER_DEVICE_STATE) {
    static IDDCX_ENDPOINT_VERSION version = {sizeof(IDDCX_ENDPOINT_VERSION), 1, 0, 0, 0};

    IDDCX_ADAPTER_CAPS caps = {};
    caps.Size = sizeof(caps);
    caps.MaxMonitorsSupported = 1;
    caps.EndPointDiagnostics.Size = sizeof(caps.EndPointDiagnostics);
    caps.EndPointDiagnostics.GammaSupport = IDDCX_FEATURE_IMPLEMENTATION_NONE;
    caps.EndPointDiagnostics.TransmissionType = IDDCX_TRANSMISSION_TYPE_WIRED_OTHER;
    caps.EndPointDiagnostics.pEndPointFriendlyName = L"Deixis";
    caps.EndPointDiagnostics.pEndPointManufacturerName = L"Deixis";
    caps.EndPointDiagnostics.pEndPointModelName = L"Deixis Display";
    caps.EndPointDiagnostics.pFirmwareVersion = &version;
    caps.EndPointDiagnostics.pHardwareVersion = &version;

    WDF_OBJECT_ATTRIBUTES attributes;
    WDF_OBJECT_ATTRIBUTES_INIT(&attributes);
    IDARG_IN_ADAPTER_INIT in = {};
    in.WdfDevice = device;
    in.pCaps = &caps;
    in.ObjectAttributes = &attributes;
    IDARG_OUT_ADAPTER_INIT out = {};
    NTSTATUS status = IddCxAdapterInitAsync(&in, &out);
    if (NT_SUCCESS(status)) g_adapter = out.AdapterObject;
    return status;
}

NTSTATUS DeixisAdapterInitFinished(IDDCX_ADAPTER adapter, const IDARG_IN_ADAPTER_INIT_FINISHED* args) {
    if (!NT_SUCCESS(args->AdapterInitStatus)) return STATUS_SUCCESS;

    IDDCX_MONITOR_INFO info = {};
    info.Size = sizeof(info);
    info.MonitorType = DISPLAYCONFIG_OUTPUT_TECHNOLOGY_HDMI;
    info.ConnectorIndex = 0;
    info.MonitorDescription.Size = sizeof(info.MonitorDescription);
    info.MonitorDescription.Type = IDDCX_MONITOR_DESCRIPTION_TYPE_EDID;
    info.MonitorDescription.DataSize = sizeof(kEdid.data);
    info.MonitorDescription.pData = const_cast<BYTE*>(kEdid.data);
    // Постоянный id контейнера: Windows запоминает расположение монитора между запусками.
    info.MonitorContainerId = {0x6d1f2a40, 0x3b7c, 0x4e1a, {0x9c, 0x55, 0x0d, 0xe1, 0x15, 0xd1, 0x5f, 0x01}};

    WDF_OBJECT_ATTRIBUTES attributes;
    WDF_OBJECT_ATTRIBUTES_INIT(&attributes);
    IDARG_IN_MONITORCREATE in = {};
    in.ObjectAttributes = &attributes;
    in.pMonitorInfo = &info;
    IDARG_OUT_MONITORCREATE out = {};
    NTSTATUS status = IddCxMonitorCreate(adapter, &in, &out);
    if (!NT_SUCCESS(status)) return status;
    g_monitor = out.MonitorObject;

    IDARG_OUT_MONITORARRIVAL arrival = {};
    return IddCxMonitorArrival(out.MonitorObject, &arrival);
}

NTSTATUS DeixisCommitModes(IDDCX_ADAPTER, const IDARG_IN_COMMITMODES*) {
    // Режим меняет сама Windows; кадры забирает поток FrameDrain при любом режиме.
    return STATUS_SUCCESS;
}

NTSTATUS DeixisParseMonitorDescription(const IDARG_IN_PARSEMONITORDESCRIPTION* in, IDARG_OUT_PARSEMONITORDESCRIPTION* out) {
    out->MonitorModeBufferOutputCount = kModeCount;
    if (in->MonitorModeBufferInputCount < kModeCount) {
        // Сначала Windows спрашивает только число режимов (буфер пустой).
        return in->MonitorModeBufferInputCount > 0 ? STATUS_BUFFER_TOO_SMALL : STATUS_SUCCESS;
    }
    FillMonitorModes(in->pMonitorModes, IDDCX_MONITOR_MODE_ORIGIN_MONITORDESCRIPTOR);
    out->PreferredMonitorModeIdx = 0;
    return STATUS_SUCCESS;
}

NTSTATUS DeixisDefaultModes(IDDCX_MONITOR, const IDARG_IN_GETDEFAULTDESCRIPTIONMODES* in, IDARG_OUT_GETDEFAULTDESCRIPTIONMODES* out) {
    // Вызывается, только если у монитора нет EDID; у нашего он есть, но ответ должен быть верным.
    out->DefaultMonitorModeBufferOutputCount = kModeCount;
    if (in->DefaultMonitorModeBufferInputCount < kModeCount) {
        return in->DefaultMonitorModeBufferInputCount > 0 ? STATUS_BUFFER_TOO_SMALL : STATUS_SUCCESS;
    }
    FillMonitorModes(in->pDefaultMonitorModes, IDDCX_MONITOR_MODE_ORIGIN_DRIVER);
    out->PreferredMonitorModeIdx = 0;
    return STATUS_SUCCESS;
}

NTSTATUS DeixisQueryTargetModes(IDDCX_MONITOR, const IDARG_IN_QUERYTARGETMODES* in, IDARG_OUT_QUERYTARGETMODES* out) {
    out->TargetModeBufferOutputCount = kModeCount;
    if (in->TargetModeBufferInputCount < kModeCount) {
        return in->TargetModeBufferInputCount > 0 ? STATUS_BUFFER_TOO_SMALL : STATUS_SUCCESS;
    }
    for (UINT i = 0; i < kModeCount; i++) {
        IDDCX_TARGET_MODE mode = {};
        mode.Size = sizeof(mode);
        mode.TargetVideoSignalInfo.targetVideoSignalInfo = Signal(kModes[i]);
        in->pTargetModes[i] = mode;
    }
    return STATUS_SUCCESS;
}

NTSTATUS DeixisAssignSwapChain(IDDCX_MONITOR, const IDARG_IN_SETSWAPCHAIN* in) {
    AcquireSRWLockExclusive(&g_lock);
    delete g_drain;
    g_drain = new FrameDrain(in->hSwapChain, in->hNextSurfaceAvailable, in->RenderAdapterLuid);
    ReleaseSRWLockExclusive(&g_lock);
    return STATUS_SUCCESS;
}

NTSTATUS DeixisUnassignSwapChain(IDDCX_MONITOR) {
    AcquireSRWLockExclusive(&g_lock);
    delete g_drain;
    g_drain = nullptr;
    ReleaseSRWLockExclusive(&g_lock);
    return STATUS_SUCCESS;
}

}  // extern "C"
