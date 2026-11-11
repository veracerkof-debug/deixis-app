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
//   --log <файл> (в любом месте) — каждый шаг и ошибка Windows с текстом пишутся ещё и в этот файл:
//       помощник идёт с правами администратора в скрытом окне, и Deixis читает причину отсюда, в свой журнал.
//
// Коды выхода install: 0 — монитор подключён; 2 — Windows не доверила драйверу (сертификат не принят и
// установка без него не прошла); 3 — устройство не заведено; 4 — драйвер не встал; 1223 — отказ в окне Windows.
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

FILE* g_log = nullptr;

// Текст ошибки Windows по коду.
std::wstring ErrorText(DWORD code) {
    wchar_t* buf = nullptr;
    DWORD n = FormatMessageW(FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
                             nullptr, code, 0, reinterpret_cast<wchar_t*>(&buf), 0, nullptr);
    std::wstring text = n ? std::wstring(buf, n) : L"";
    if (buf) LocalFree(buf);
    while (!text.empty() && (text.back() == L'\r' || text.back() == L'\n' || text.back() == L' ')) text.pop_back();
    return text;
}

// Шаг и (если есть) ошибка Windows: в stderr и в файл журнала.
void Note(const wchar_t* step, DWORD code = 0) {
    std::wstring line = step;
    if (code) line += L": " + std::to_wstring(code) + L" (0x" + [&] { wchar_t h[16]; swprintf_s(h, L"%08lX", code); return std::wstring(h); }() + L") " + ErrorText(code);
    fwprintf(stderr, L"%s\n", line.c_str());
    if (g_log) { fwprintf(g_log, L"%s\n", line.c_str()); fflush(g_log); }
}

// id устройства; для проверки в CI можно поставить и другой драйвер (четвёртый аргумент install).
const wchar_t* kHardwareId = L"Root\\DeixisDisplay";

// Сертификат драйвера — в «Доверенные корневые» и «Доверенные издатели» компьютера. 0 — принят, иначе код ошибки.
DWORD AddCertificate(const std::wstring& cerPath) {
    HANDLE f = CreateFileW(cerPath.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, 0, nullptr);
    if (f == INVALID_HANDLE_VALUE) { DWORD e = GetLastError(); Note(L"сертификат: файл не открыт", e); return e; }
    DWORD size = GetFileSize(f, nullptr);
    std::vector<BYTE> data(size);
    DWORD read = 0;
    bool ok = ReadFile(f, data.data(), size, &read, nullptr) && read == size;
    DWORD readError = ok ? 0 : GetLastError();
    CloseHandle(f);
    if (!ok) { Note(L"сертификат: файл не прочитан", readError ? readError : ERROR_READ_FAULT); return readError ? readError : ERROR_READ_FAULT; }
    for (const wchar_t* storeName : {L"Root", L"TrustedPublisher"}) {
        HCERTSTORE store = CertOpenStore(CERT_STORE_PROV_SYSTEM_W, 0, 0, CERT_SYSTEM_STORE_LOCAL_MACHINE, storeName);
        if (!store) { DWORD e = GetLastError(); Note((std::wstring(L"сертификат: хранилище ") + storeName + L" не открыто").c_str(), e); return e; }
        BOOL added = CertAddEncodedCertificateToStore(store, X509_ASN_ENCODING, data.data(), size, CERT_STORE_ADD_REPLACE_EXISTING, nullptr);
        DWORD e = added ? 0 : GetLastError();
        CertCloseStore(store, 0);
        if (!added) { Note((std::wstring(L"сертификат: не добавлен в ") + storeName).c_str(), e); return e ? e : ERROR_ACCESS_DENIED; }
    }
    Note(L"сертификат: принят");
    return 0;
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
    wchar_t ids[128] = {};
    wcscpy_s(ids, kHardwareId);
    const DWORD bytes = static_cast<DWORD>((wcslen(kHardwareId) + 2) * sizeof(wchar_t));
    ok = ok && SetupDiSetDeviceRegistryPropertyW(set, &info, SPDRP_HARDWAREID, reinterpret_cast<BYTE*>(ids), bytes);
    ok = ok && SetupDiCallClassInstaller(DIF_REGISTERDEVICE, set, &info);
    SetupDiDestroyDeviceInfoList(set);
    return ok;
}

int Install(const std::wstring& inf, const std::wstring& cer) {
    // Сертификат не приняли (антивирус, политика компьютера) — не конец: Windows сама спросит,
    // ставить ли драйвер этого издателя, и оператор ответит «Установить».
    DWORD certError = AddCertificate(cer);
    if (certError) Note(L"продолжаю без сертификата: Windows спросит, доверять ли издателю Deixis Display");
    if (!FindDevice([](HDEVINFO, SP_DEVINFO_DATA&) {})) {
        if (!CreateDevice()) { Note(L"устройство: не заведено", GetLastError()); return 3; }
        Note(L"устройство: заведено");
    }
    BOOL reboot = FALSE;
    if (!UpdateDriverForPlugAndPlayDevicesW(nullptr, kHardwareId, inf.c_str(), INSTALLFLAG_FORCE, &reboot)) {
        DWORD e = GetLastError();
        Note(L"драйвер: не установлен", e);
        if (e == ERROR_CANCELLED) return 1223;
        return certError ? 2 : 4;
    }
    Note(reboot ? L"драйвер: установлен, Windows просит перезагрузку" : L"драйвер: установлен");
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

int wmain(int rawArgc, wchar_t** rawArgv) {
    // «--log <файл>» — где угодно в строке; остальные аргументы — как раньше.
    std::vector<wchar_t*> args;
    for (int i = 0; i < rawArgc; i++) {
        if (_wcsicmp(rawArgv[i], L"--log") == 0 && i + 1 < rawArgc) { _wfopen_s(&g_log, rawArgv[++i], L"a, ccs=UTF-8"); continue; }
        args.push_back(rawArgv[i]);
    }
    int argc = static_cast<int>(args.size());
    wchar_t** argv = args.data();
    if (argc >= 2 && _wcsicmp(argv[1], L"status") == 0) return FindDevice([](HDEVINFO, SP_DEVINFO_DATA&) {}) ? 0 : 1;
    if (argc >= 5) kHardwareId = argv[4];
    if (argc >= 4 && _wcsicmp(argv[1], L"install") == 0) return Install(argv[2], argv[3]);
    if (argc >= 2 && _wcsicmp(argv[1], L"remove") == 0) return Remove();
    fwprintf(stderr, L"deixis-display.exe install <inf> <cer> | remove | status\n");
    return 64;
}
