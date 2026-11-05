// deixis-display.exe — помощник Deixis: включает и выключает виртуальный монитор Deixis Display.
// GPL-3.0-or-later.
//
//   deixis-display.exe install <путь к DeixisDisplay.inf> <путь к DeixisDisplay.cer>
//       ставит сертификат драйвера в «Доверенные корневые» и «Доверенные издатели» этого компьютера,
//       заводит устройство Root\DeixisDisplay и ставит на него драйвер — в Windows появляется монитор;
//   deixis-display.exe remove
//       убирает устройство (монитор пропадает); драйвер остаётся в хранилище Windows до удаления Deixis;
//   deixis-display.exe status
//       код 0 — монитор есть, 1 — нет.
//
// Запускается Deixis с запросом прав администратора (один раз на включение или выключение).
// Закрытый ключ сертификата существует только во время сборки в CI и сразу удаляется: подписать им
// что-то другое нельзя, поэтому доверие к нему не открывает компьютер для чужих программ.

#define NOMINMAX
#include <windows.h>
#include <setupapi.h>
#include <newdev.h>
#include <devguid.h>
#include <wincrypt.h>
#include <cstdio>
#include <string>
#include <vector>

namespace {

const wchar_t kHardwareId[] = L"Root\\DeixisDisplay";

bool AddCertificate(const std::wstring& cerPath) {
    HANDLE f = CreateFileW(cerPath.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, 0, nullptr);
    if (f == INVALID_HANDLE_VALUE) return false;
    DWORD size = GetFileSize(f, nullptr);
    std::vector<BYTE> data(size);
    DWORD read = 0;
    bool ok = ReadFile(f, data.data(), size, &read, nullptr) && read == size;
    CloseHandle(f);
    if (!ok) return false;
    for (const wchar_t* storeName : {L"Root", L"TrustedPublisher"}) {
        HCERTSTORE store = CertOpenStore(CERT_STORE_PROV_SYSTEM_W, 0, 0, CERT_SYSTEM_STORE_LOCAL_MACHINE, storeName);
        if (!store) return false;
        BOOL added = CertAddEncodedCertificateToStore(store, X509_ASN_ENCODING, data.data(), size, CERT_STORE_ADD_REPLACE_EXISTING, nullptr);
        CertCloseStore(store, 0);
        if (!added) return false;
    }
    return true;
}

// Ищет наше устройство; найдено — вызывает [onFound] с ним.
template <typename F>
bool FindDevice(F onFound) {
    HDEVINFO set = SetupDiGetClassDevsW(&GUID_DEVCLASS_DISPLAY, nullptr, nullptr, 0);
    if (set == INVALID_HANDLE_VALUE) return false;
    bool found = false;
    SP_DEVINFO_DATA info = {sizeof(info)};
    for (DWORD i = 0; SetupDiEnumDeviceInfo(set, i, &info); i++) {
        wchar_t ids[512] = {};
        if (!SetupDiGetDeviceRegistryPropertyW(set, &info, SPDRP_HARDWAREID, nullptr, reinterpret_cast<BYTE*>(ids), sizeof(ids) - 2, nullptr)) continue;
        for (const wchar_t* p = ids; *p; p += wcslen(p) + 1) {
            if (_wcsicmp(p, kHardwareId) == 0) { found = true; onFound(set, info); break; }
        }
    }
    SetupDiDestroyDeviceInfoList(set);
    return found;
}

bool CreateDevice() {
    HDEVINFO set = SetupDiCreateDeviceInfoList(&GUID_DEVCLASS_DISPLAY, nullptr);
    if (set == INVALID_HANDLE_VALUE) return false;
    SP_DEVINFO_DATA info = {sizeof(info)};
    bool ok = SetupDiCreateDeviceInfoW(set, L"Display", &GUID_DEVCLASS_DISPLAY, nullptr, nullptr, DICD_GENERATE_ID, &info);
    // Список id оканчивается двумя нулями.
    wchar_t ids[64] = {};
    wcscpy_s(ids, kHardwareId);
    const DWORD bytes = static_cast<DWORD>((wcslen(kHardwareId) + 2) * sizeof(wchar_t));
    ok = ok && SetupDiSetDeviceRegistryPropertyW(set, &info, SPDRP_HARDWAREID, reinterpret_cast<BYTE*>(ids), bytes);
    ok = ok && SetupDiCallClassInstaller(DIF_REGISTERDEVICE, set, &info);
    SetupDiDestroyDeviceInfoList(set);
    return ok;
}

int Install(const std::wstring& inf, const std::wstring& cer) {
    if (!AddCertificate(cer)) { fwprintf(stderr, L"certificate: %lu\n", GetLastError()); return 2; }
    if (!FindDevice([](HDEVINFO, SP_DEVINFO_DATA&) {}) && !CreateDevice()) { fwprintf(stderr, L"device: %lu\n", GetLastError()); return 3; }
    BOOL reboot = FALSE;
    if (!UpdateDriverForPlugAndPlayDevicesW(nullptr, kHardwareId, inf.c_str(), INSTALLFLAG_FORCE, &reboot)) {
        fwprintf(stderr, L"driver: %lu\n", GetLastError());
        return 4;
    }
    return 0;
}

int Remove() {
    bool ok = true;
    FindDevice([&](HDEVINFO set, SP_DEVINFO_DATA& info) {
        if (!SetupDiCallClassInstaller(DIF_REMOVE, set, &info)) ok = false;
    });
    return ok ? 0 : 5;
}

}  // namespace

int wmain(int argc, wchar_t** argv) {
    if (argc >= 2 && _wcsicmp(argv[1], L"status") == 0) return FindDevice([](HDEVINFO, SP_DEVINFO_DATA&) {}) ? 0 : 1;
    if (argc >= 4 && _wcsicmp(argv[1], L"install") == 0) return Install(argv[2], argv[3]);
    if (argc >= 2 && _wcsicmp(argv[1], L"remove") == 0) return Remove();
    fwprintf(stderr, L"deixis-display.exe install <inf> <cer> | remove | status\n");
    return 64;
}
