#!/usr/bin/env python3
"""Inject git_panel_* translations into every locale strings.xml.

The CI gate scripts/ci/check_new_untranslated.py rejects new keys that are
missing from any values-*/strings.xml. Keys must be present in ALL shipped
locales, not just well-translated ones.
"""
import os
import re
import sys

RES = "src/android/app/src/main/res"

KEYS = [
    "git_panel_staged",
    "git_panel_changes_section",
    "git_panel_stats_files",
    "git_panel_untracked_badge",
    "git_panel_search_history",
    "git_panel_search_no_match",
    "git_panel_no_identity",
    "git_panel_commit_files",
    "git_panel_copy_hash",
    "git_panel_author_label",
    "git_panel_committer",
    "git_panel_committed_at",
    "git_panel_hash_label",
]

T = {
    "de": ["Bereitgestellt", "Änderungen", "%1$d Dateien geändert", "neu",
           "Commits durchsuchen", "Keine passenden Commits",
           "Git-Identität nicht konfiguriert; Commits fallen auf Systemstandards zurück",
           "Geänderte Dateien (%1$d)", "Hash kopieren", "Autor", "Committer",
           "Committet", "Hash"],
    "es": ["En staging", "Cambios", "%1$d archivos modificados", "nuevo",
           "Buscar commits", "No hay commits coincidentes",
           "Identidad de Git no configurada; los commits usarán los valores predeterminados del sistema",
           "Archivos modificados (%1$d)", "Copiar hash", "Autor", "Committer",
           "Confirmado", "Hash"],
    "fil": ["Naka-stage", "Mga pagbabago", "%1$d na file ang nabago", "bago",
            "Maghanap ng mga commit", "Walang tumutugmang commit",
            "Hindi naka-configure ang Git identity; gagamitin ng mga commit ang default ng system",
            "Mga nabagong file (%1$d)", "Kopyahin ang hash", "May-akda",
            "Committer", "Nakapag-commit", "Hash"],
    "fr": ["Indexé", "Modifications", "%1$d fichiers modifiés", "nouveau",
           "Rechercher des commits", "Aucun commit correspondant",
           "Identité Git non configurée ; les commits utiliseront les valeurs par défaut du système",
           "Fichiers modifiés (%1$d)", "Copier le hash", "Auteur", "Committer",
           "Commité", "Hash"],
    "in": ["Ter-staging", "Perubahan", "%1$d file diubah", "baru",
           "Cari commit", "Tidak ada commit yang cocok",
           "Identitas Git belum dikonfigurasi; commit akan memakai bawaan sistem",
           "File diubah (%1$d)", "Salin hash", "Penulis", "Committer",
           "Dikomit", "Hash"],
    "ja": ["ステージ済み", "変更", "%1$d 件のファイルを変更", "新規",
           "コミットを検索", "一致するコミットがありません",
           "Git のユーザー情報が未設定です。コミットはシステム既定値を使用します",
           "変更されたファイル (%1$d)", "ハッシュをコピー", "作成者",
           "コミッター", "コミット日時", "ハッシュ"],
    "ko": ["스테이징됨", "변경 사항", "파일 %1$d개 변경됨", "새 파일",
           "커밋 검색", "일치하는 커밋 없음",
           "Git 사용자 정보가 설정되지 않았습니다. 커밋은 시스템 기본값을 사용합니다",
           "변경된 파일 (%1$d)", "해시 복사", "작성자", "커미터",
           "커밋됨", "해시"],
    "ms": ["Dipentas", "Perubahan", "%1$d fail diubah", "baharu",
           "Cari commit", "Tiada commit sepadan",
           "Identiti Git tidak dikonfigurasi; commit akan menggunakan tetapan lalai sistem",
           "Fail diubah (%1$d)", "Salin hash", "Pengarang", "Committer",
           "Dikomit", "Hash"],
    "pl": ["Zaindeksowane", "Zmiany", "Zmieniono plików: %1$d", "nowy",
           "Szukaj commitów", "Brak pasujących commitów",
           "Tożsamość Git nie jest skonfigurowana; commity użyją domyślnych ustawień systemowych",
           "Zmienione pliki (%1$d)", "Kopiuj hash", "Autor", "Committer",
           "Zatwierdzono", "Hash"],
    "pt-rBR": ["Em staging", "Alterações", "%1$d arquivos alterados", "novo",
               "Pesquisar commits", "Nenhum commit correspondente",
               "Identidade Git não configurada; commits usarão os padrões do sistema",
               "Arquivos alterados (%1$d)", "Copiar hash", "Autor", "Committer",
               "Comitado", "Hash"],
    "ro": ["În staging", "Modificări", "%1$d fișiere modificate", "nou",
           "Caută commituri", "Niciun commit corespunzător",
           "Identitatea Git nu este configurată; commiturile vor folosi valorile implicite de sistem",
           "Fișiere modificate (%1$d)", "Copiază hash", "Autor", "Committer",
           "Comitat", "Hash"],
    "ru": ["Индексировано", "Изменения", "Изменено файлов: %1$d", "новый",
           "Поиск коммитов", "Нет подходящих коммитов",
           "Идентификация Git не настроена; коммиты будут использовать системные значения по умолчанию",
           "Изменённые файлы (%1$d)", "Скопировать хеш", "Автор",
           "Коммиттер", "Закоммичено", "Хеш"],
    "th": ["สเตจแล้ว", "การเปลี่ยนแปลง", "ไฟล์ที่เปลี่ยนแปลง %1$d รายการ", "ใหม่",
           "ค้นหาคอมมิต", "ไม่มีคอมมิตที่ตรงกัน",
           "ยังไม่ได้ตั้งค่าข้อมูลประจำตัว Git คอมมิตจะใช้ค่าเริ่มต้นของระบบ",
           "ไฟล์ที่เปลี่ยนแปลง (%1$d)", "คัดลอกแฮช", "ผู้เขียน",
           "ผู้คอมมิต", "คอมมิตแล้ว", "แฮช"],
    "tr": ["Hazırlandı", "Değişiklikler", "%1$d dosya değiştirildi", "yeni",
           "Commit'lerde ara", "Eşleşen commit yok",
           "Git kimliği yapılandırılmadı; commit'ler sistem varsayılanlarını kullanacak",
           "Değiştirilen dosyalar (%1$d)", "Hash'i kopyala", "Yazar",
           "Committer", "Commit'lendi", "Hash"],
    "zh": ["已暂存", "更改", "已更改 %1$d 个文件", "新增", "搜索提交",
           "没有匹配的提交", "未配置 Git 身份信息；提交将使用系统默认值",
           "已更改文件 (%1$d)", "复制哈希", "作者", "提交者", "提交时间",
           "哈希"],
    "zh-rTW": ["已暫存", "變更", "已變更 %1$d 個檔案", "新增", "搜尋提交",
               "沒有相符的提交", "未設定 Git 身分資訊；提交將使用系統預設值",
               "已變更檔案 (%1$d)", "複製雜湊", "作者", "提交者", "提交時間",
               "雜湊"],
}


def xml_escape(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def main() -> int:
    changed = 0
    for locale, values in T.items():
        path = os.path.join(RES, f"values-{locale}", "strings.xml")
        with open(path, encoding="utf-8") as f:
            content = f.read()
        lines = []
        for key, val in zip(KEYS, values):
            if re.search(rf'<string name="{re.escape(key)}"', content):
                continue
            lines.append(f'    <string name="{key}">{xml_escape(val)}</string>')
        if not lines:
            continue
        block = "\n".join(lines) + "\n"
        idx = content.rfind("</resources>")
        content = content[:idx] + block + content[idx:]
        with open(path, "w", encoding="utf-8") as f:
            f.write(content)
        changed += 1
        print(f"{path}: +{len(lines)} keys")
    if changed == 0:
        print("no changes needed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
