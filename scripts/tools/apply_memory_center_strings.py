#!/usr/bin/env python3
"""One-shot writer for the memory-centre strings (T-memory-center-stats).

Kept in the repo only as a record of what was inserted where; not part of CI.
Run from the repo root:  python scripts/tools/apply_memory_center_strings.py
"""
from __future__ import annotations

import io
import re
from pathlib import Path

RES = Path("src/android/app/src/main/res")

DIARY_FOOTER = {
    "values": "Daily logs in this session's memory folder. The three most recent "
              "are injected in full; older days are listed as an index you can search.",
    "values-zh": "本会话记忆目录下的每日日志：最近三份完整注入，更早的日期以索引列出，"
                 "需要细节时可再用 memory_get 检索。",
}

NEW_KEYS = [
    ("memory_center_overview", {
        "values": "At a glance",
        "values-de": "Auf einen Blick",
        "values-es": "De un vistazo",
        "values-fil": "Sa isang tingin",
        "values-fr": "En un coup d'œil",
        "values-in": "Sekilas",
        "values-ja": "概要",
        "values-ko": "한눈에 보기",
        "values-ms": "Sepintas lalu",
        "values-pl": "W skrócie",
        "values-pt-rBR": "Visão geral",
        "values-ro": "Dintr-o privire",
        "values-ru": "Кратко",
        "values-th": "ภาพรวม",
        "values-tr": "Bir bakışta",
        "values-zh": "概览",
        "values-zh-rTW": "概覽",
    }),
    ("memory_center_stat_memory", {
        "values": "Memory files",
        "values-de": "Speicherdateien",
        "values-es": "Archivos de memoria",
        "values-fil": "Mga file ng memorya",
        "values-fr": "Fichiers de mémoire",
        "values-in": "File memori",
        "values-ja": "メモリファイル",
        "values-ko": "메모리 파일",
        "values-ms": "Fail memori",
        "values-pl": "Pliki pamięci",
        "values-pt-rBR": "Arquivos de memória",
        "values-ro": "Fișiere de memorie",
        "values-ru": "Файлы памяти",
        "values-th": "ไฟล์ความจำ",
        "values-tr": "Bellek dosyaları",
        "values-zh": "记忆文件",
        "values-zh-rTW": "記憶檔案",
    }),
    ("memory_center_stat_notes", {
        "values": "Knowledge notes",
        "values-de": "Wissensnotizen",
        "values-es": "Notas de conocimiento",
        "values-fil": "Mga tala ng kaalaman",
        "values-fr": "Notes de connaissance",
        "values-in": "Catatan pengetahuan",
        "values-ja": "ナレッジノート",
        "values-ko": "지식 노트",
        "values-ms": "Nota pengetahuan",
        "values-pl": "Notatki wiedzy",
        "values-pt-rBR": "Notas de conhecimento",
        "values-ro": "Notițe de cunoștințe",
        "values-ru": "Заметки знаний",
        "values-th": "บันทึกความรู้",
        "values-tr": "Bilgi notları",
        "values-zh": "知识笔记",
        "values-zh-rTW": "知識筆記",
    }),
    ("memory_center_stat_recalls", {
        "values": "Recalled entries",
        "values-de": "Abgerufene Einträge",
        "values-es": "Entradas recuperadas",
        "values-fil": "Mga naalala na entry",
        "values-fr": "Entrées rappelées",
        "values-in": "Entri yang dipanggil",
        "values-ja": "呼び出された項目",
        "values-ko": "호출된 항목",
        "values-ms": "Entri dipanggil",
        "values-pl": "Przywołane wpisy",
        "values-pt-rBR": "Entradas recuperadas",
        "values-ro": "Intrări rechemate",
        "values-ru": "Вызванные записи",
        "values-th": "รายการที่ถูกเรียกคืน",
        "values-tr": "Geri çağrılan kayıtlar",
        "values-zh": "被召回条目",
        "values-zh-rTW": "被召回條目",
    }),
    ("memory_center_stat_failures", {
        "values": "Blocked repeats",
        "values-de": "Blockierte Wiederholungen",
        "values-es": "Repeticiones bloqueadas",
        "values-fil": "Mga naka-block na pag-uulit",
        "values-fr": "Répétitions bloquées",
        "values-in": "Pengulangan yang diblokir",
        "values-ja": "ブロックされた繰り返し",
        "values-ko": "차단된 반복",
        "values-ms": "Ulangan disekat",
        "values-pl": "Zablokowane powtórzenia",
        "values-pt-rBR": "Repetições bloqueadas",
        "values-ro": "Repetări blocate",
        "values-ru": "Заблокированные повторы",
        "values-th": "การทำซ้ำที่ถูกบล็อก",
        "values-tr": "Engellenen tekrarlar",
        "values-zh": "已拦截的重复",
        "values-zh-rTW": "已攔截的重複",
    }),
    ("memory_center_stat_sediments", {
        "values": "Compaction summaries",
        "values-de": "Kompaktierungs-Zusammenfassungen",
        "values-es": "Resúmenes de compactación",
        "values-fil": "Mga buod ng compaction",
        "values-fr": "Résumés de compactage",
        "values-in": "Ringkasan pemadatan",
        "values-ja": "圧縮の要約",
        "values-ko": "압축 요약",
        "values-ms": "Ringkasan pemadatan",
        "values-pl": "Podsumowania kompakcji",
        "values-pt-rBR": "Resumos de compactação",
        "values-ro": "Rezumate de compactare",
        "values-ru": "Сводки сжатия",
        "values-th": "สรุปการบีบอัด",
        "values-tr": "Sıkıştırma özetleri",
        "values-zh": "压缩摘要",
        "values-zh-rTW": "壓縮摘要",
    }),
    ("memory_center_stats_footer", {
        "values": "Counted across this device; reopen this screen to refresh.",
        "values-de": "Geräteweit gezählt; zum Aktualisieren diesen Bildschirm erneut öffnen.",
        "values-es": "Contado en todo el dispositivo; vuelve a abrir esta pantalla para actualizar.",
        "values-fil": "Binilang sa buong device; buksan muli ang screen na ito para i-refresh.",
        "values-fr": "Compté sur tout l'appareil ; rouvrez cet écran pour actualiser.",
        "values-in": "Dihitung di seluruh perangkat; buka ulang layar ini untuk menyegarkan.",
        "values-ja": "端末全体の集計です。再表示すると更新されます。",
        "values-ko": "이 기기 전체 기준입니다. 이 화면을 다시 열면 갱신됩니다.",
        "values-ms": "Dikira pada keseluruhan peranti; buka semula skrin ini untuk menyegarkan.",
        "values-pl": "Liczone dla całego urządzenia; otwórz ponownie ten ekran, aby odświeżyć.",
        "values-pt-rBR": "Contado no dispositivo inteiro; reabra esta tela para atualizar.",
        "values-ro": "Numărate pe întregul dispozitiv; redeschide acest ecran pentru actualizare.",
        "values-ru": "Подсчитано по всему устройству; откройте экран заново для обновления.",
        "values-th": "นับทั้งอุปกรณ์ เปิดหน้านี้อีกครั้งเพื่อรีเฟรช",
        "values-tr": "Tüm cihaz genelinde sayılır; yenilemek için bu ekranı yeniden açın.",
        "values-zh": "统计范围为本设备；重新打开此页即可刷新。",
        "values-zh-rTW": "統計範圍為本裝置；重新開啟此頁即可重新整理。",
    }),
]


def escape(value: str) -> str:
    return (value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("'", "\\'"))


def read(path: Path) -> str:
    return io.open(path, encoding="utf-8").read()


def write(path: Path, text: str) -> None:
    io.open(path, "w", encoding="utf-8", newline="\n").write(text)


def main() -> None:
    touched = 0
    for locale_dir in sorted(RES.iterdir()):
        if not locale_dir.is_dir() or not locale_dir.name.startswith("values"):
            continue
        xml = locale_dir / "strings.xml"
        if not xml.is_file():
            continue
        name = locale_dir.name
        text = read(xml)

        # 1) refresh the diary footer where it exists
        if name in DIARY_FOOTER:
            pattern = re.compile(
                r'<string name="memory_section_diary_footer">.*?</string>')
            replacement = ('<string name="memory_section_diary_footer">'
                           + escape(DIARY_FOOTER[name]) + '</string>')
            text, count = pattern.subn(replacement, text)
            if count != 1:
                raise SystemExit(f"{name}: diary footer occurrences = {count}")

        # 2) append any missing keys, skipping ones already present
        missing = []
        for key, by_locale in NEW_KEYS:
            if f'name="{key}"' in text:
                continue
            missing.append(
                f'    <string name="{key}">{escape(by_locale[name])}</string>')
        if missing:
            text = text.replace(
                "</resources>",
                "\n".join(missing) + "\n</resources>",
            )
        write(xml, text)
        touched += 1
        print(f"{name}: diary-footer={'yes' if name in DIARY_FOOTER else 'skip'} "
              f"new-keys={len(missing)}")
    print(f"updated {touched} files")


if __name__ == "__main__":
    main()
