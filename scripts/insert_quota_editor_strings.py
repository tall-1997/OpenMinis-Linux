#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Insert the 13 new model_group_detail_* strings into every locale's
strings.xml, right before </resources>. Idempotent: skips keys already present."""
from pathlib import Path

KEYS = [
    "model_group_detail_first_token_timeout",
    "model_group_detail_first_token_timeout_footer",
    "model_group_detail_first_token_timeout_placeholder",
    "model_group_detail_member_weights",
    "model_group_detail_member_weights_footer",
    "model_group_detail_weight_decrease",
    "model_group_detail_weight_increase",
    "model_group_detail_quota_window",
    "model_group_detail_quota_window_footer",
    "model_group_detail_quota_total_placeholder",
    "model_group_detail_quota_period_placeholder",
    "model_group_detail_quota_window_reset_snackbar",
    "model_group_detail_quota_window_remaining",
]

LOCALES = {
    "values": [
        "First-Token Timeout",
        "Seconds to wait for the first token before falling back. Leave empty for the default (45s chat / 90s reasoning).",
        "Default (45s chat / 90s reasoning)",
        "Member Weights",
        "Controls weighted routing \u2014 higher weights are picked more often. 1 is neutral.",
        "Decrease weight",
        "Increase weight",
        "Quota Window",
        "The primary member serves until the window\u2019s sends run out, then the backup takes over until the window resets. Editing either value resets the window now.",
        "Sends per window",
        "Window length (hours)",
        "Quota window reset \u2014 primary member serves again",
        "Remaining this window: %1$d sends",
    ],
    "values-zh": [
        "首 Token 超时",
        "等待首个 Token 的最长秒数，超时则切换备用成员。留空使用默认值（对话 45 秒、推理 90 秒）。",
        "默认（对话 45 秒 / 推理 90 秒）",
        "成员权重",
        "控制加权路由：权重越高，被选中的概率越大；1 为普通权重。",
        "减少权重",
        "增加权重",
        "配额窗口",
        "主成员服务完窗口内的发送次数后，由备用成员接管，直到窗口重置。编辑任一数值会立即重置窗口。",
        "每窗口发送次数",
        "窗口时长（小时）",
        "配额窗口已重置，主成员重新开始服务",
        "本窗口剩余：%1$d 次",
    ],
    "values-zh-rTW": [
        "首 Token 逾時",
        "等待第一個 Token 的最長秒數，逾時則切換備用成員。留空使用預設值（對話 45 秒、推理 90 秒）。",
        "預設（對話 45 秒 / 推理 90 秒）",
        "成員權重",
        "控制加權路由：權重越高，被選中的機率越大；1 為普通權重。",
        "減少權重",
        "增加權重",
        "配額視窗",
        "主要成員服務完視窗內的傳送次數後，由備用成員接管，直到視窗重設。編輯任一數值會立即重設視窗。",
        "每視窗傳送次數",
        "視窗時長（小時）",
        "配額視窗已重設，主要成員重新開始服務",
        "本視窗剩餘：%1$d 次",
    ],
    "values-ja": [
        "最初のトークンまでのタイムアウト",
        "最初のトークンを待つ最大秒数。超過するとバックアップに切り替わります。空欄はデフォルト（チャット45秒・推論90秒）。",
        "デフォルト（チャット45秒 / 推論90秒）",
        "メンバーの重み",
        "重み付きルーティングを制御します。重みが大きいほど選ばれやすく、1が標準です。",
        "重みを減らす",
        "重みを増やす",
        "クォータウィンドウ",
        "プライマリメンバーがウィンドウ内の送信回数を使い切るまで応答し、その後リセットまでバックアップが引き継ぎます。どちらかの値を編集するとウィンドウが即リセットされます。",
        "ウィンドウあたりの送信回数",
        "ウィンドウの長さ（時間）",
        "クォータウィンドウをリセットしました。プライマリメンバーが再び応答します",
        "このウィンドウの残り：%1$d回",
    ],
    "values-ko": [
        "첫 토큰 시간 초과",
        "첫 토큰을 기다리는 최대 시간(초)입니다. 초과 시 백업 멤버로 전환됩니다. 비우면 기본값(채팅 45초, 추론 90초)이 적용됩니다.",
        "기본값(채팅 45초 / 추론 90초)",
        "멤버 가중치",
        "가중치 라우팅을 제어합니다. 가중치가 높을수록 자주 선택되며 1이 기본값입니다.",
        "가중치 줄이기",
        "가중치 늘리기",
        "할당량 창",
        "기본 멤버가 창의 전송 횟수를 모두 사용하면 창이 재설정될 때까지 백업 멤버가 이어받습니다. 값을 편집하면 창이 즉시 재설정됩니다.",
        "창당 전송 횟수",
        "창 길이(시간)",
        "할당량 창이 재설정되어 기본 멤버가 다시 응답합니다",
        "이번 창 남은 횟수: %1$d회",
    ],
    "values-de": [
        "First-Token-Timeout",
        "Maximale Wartezeit auf das erste Token in Sekunden, danach \u00fcbernimmt das Backup. Leer = Standard (Chat 45 s, Reasoning 90 s).",
        "Standard (Chat 45 s / Reasoning 90 s)",
        "Mitgliedergewichte",
        "Steuert gewichtetes Routing \u2013 je h\u00f6her das Gewicht, desto h\u00e4ufiger die Auswahl. 1 ist neutral.",
        "Gewicht verringern",
        "Gewicht erh\u00f6hen",
        "Kontingentfenster",
        "Das prim\u00e4re Mitglied bedient, bis die Sendungen des Fensters aufgebraucht sind, dann \u00fcbernimmt das Backup bis zum Reset. Das Bearbeiten eines Werts setzt das Fenster sofort zur\u00fcck.",
        "Sendungen pro Fenster",
        "Fensterl\u00e4nge (Stunden)",
        "Kontingentfenster zur\u00fcckgesetzt \u2013 prim\u00e4res Mitglied bedient wieder",
        "Verbleibend in diesem Fenster: %1$d Sendungen",
    ],
    "values-es": [
        "Tiempo de espera del primer token",
        "Segundos m\u00e1ximos de espera del primer token antes de cambiar al respaldo. Vac\u00edo = predeterminado (chat 45 s, razonamiento 90 s).",
        "Predeterminado (chat 45 s / razonamiento 90 s)",
        "Pesos de miembros",
        "Controla el enrutamiento ponderado: a mayor peso, m\u00e1s frecuente la selecci\u00f3n. 1 es neutro.",
        "Reducir peso",
        "Aumentar peso",
        "Ventana de cuota",
        "El miembro principal atiende hasta agotar los env\u00edos de la ventana; luego el respaldo toma el control hasta el reinicio. Editar cualquier valor reinicia la ventana.",
        "Env\u00edos por ventana",
        "Duraci\u00f3n de la ventana (horas)",
        "Ventana de cuota reiniciada: el miembro principal vuelve a atender",
        "Restantes en esta ventana: %1$d env\u00edos",
    ],
    "values-fr": [
        "D\u00e9lai du premier jeton",
        "Secondes maximales d\u2019attente du premier jeton avant bascule vers le secours. Vide = d\u00e9faut (chat 45 s, raisonnement 90 s).",
        "D\u00e9faut (chat 45 s / raisonnement 90 s)",
        "Poids des membres",
        "Contr\u00f4le le routage pond\u00e9r\u00e9 : plus le poids est \u00e9lev\u00e9, plus le membre est choisi. 1 est neutre.",
        "R\u00e9duire le poids",
        "Augmenter le poids",
        "Fen\u00eatre de quota",
        "Le membre principal r\u00e9pond jusqu\u2019\u00e0 \u00e9puisement des envois de la fen\u00eatre, puis le secours prend le relais jusqu\u2019\u00e0 la r\u00e9initialisation. Modifier une valeur r\u00e9initialise la fen\u00eatre.",
        "Envois par fen\u00eatre",
        "Dur\u00e9e de la fen\u00eatre (heures)",
        "Fen\u00eatre de quota r\u00e9initialis\u00e9e \u2014 le membre principal r\u00e9pond \u00e0 nouveau",
        "Restants dans cette fen\u00eatre : %1$d envois",
    ],
    "values-pt-rBR": [
        "Tempo limite do primeiro token",
        "Segundos m\u00e1ximos de espera pelo primeiro token antes de alternar para o reserva. Vazio = padr\u00e3o (chat 45 s, racioc\u00ednio 90 s).",
        "Padr\u00e3o (chat 45 s / racioc\u00ednio 90 s)",
        "Pesos dos membros",
        "Controla o roteamento ponderado: quanto maior o peso, mais frequente a escolha. 1 \u00e9 neutro.",
        "Reduzir peso",
        "Aumentar peso",
        "Janela de cota",
        "O membro principal atende at\u00e9 esgotar os envios da janela; depois o reserva assume at\u00e9 a redefini\u00e7\u00e3o. Editar qualquer valor redefine a janela.",
        "Envios por janela",
        "Dura\u00e7\u00e3o da janela (horas)",
        "Janela de cota redefinida \u2014 o membro principal volta a atender",
        "Restantes nesta janela: %1$d envios",
    ],
    "values-ru": [
        "\u0422\u0430\u0439\u043c-\u0430\u0443\u0442 \u043f\u0435\u0440\u0432\u043e\u0433\u043e \u0442\u043e\u043a\u0435\u043d\u0430",
        "\u041c\u0430\u043a\u0441\u0438\u043c\u0430\u043b\u044c\u043d\u043e\u0435 \u0432\u0440\u0435\u043c\u044f \u043e\u0436\u0438\u0434\u0430\u043d\u0438\u044f \u043f\u0435\u0440\u0432\u043e\u0433\u043e \u0442\u043e\u043a\u0435\u043d\u0430 \u0432 \u0441\u0435\u043a\u0443\u043d\u0434\u0430\u0445, \u0437\u0430\u0442\u0435\u043c \u043f\u0435\u0440\u0435\u0445\u043e\u0434 \u043d\u0430 \u0440\u0435\u0437\u0435\u0440\u0432. \u041f\u0443\u0441\u0442\u043e = \u043f\u043e \u0443\u043c\u043e\u043b\u0447\u0430\u043d\u0438\u044e (\u0447\u0430\u0442 45 \u0441, \u0440\u0430\u0441\u0441\u0443\u0436\u0434\u0435\u043d\u0438\u0435 90 \u0441).",
        "\u041f\u043e \u0443\u043c\u043e\u043b\u0447\u0430\u043d\u0438\u044e (\u0447\u0430\u0442 45 \u0441 / \u0440\u0430\u0441\u0441\u0443\u0436\u0434\u0435\u043d\u0438\u0435 90 \u0441)",
        "\u0412\u0435\u0441\u0430 \u0443\u0447\u0430\u0441\u0442\u043d\u0438\u043a\u043e\u0432",
        "\u0423\u043f\u0440\u0430\u0432\u043b\u044f\u0435\u0442 \u0432\u0437\u0432\u0435\u0448\u0435\u043d\u043d\u043e\u0439 \u043c\u0430\u0440\u0448\u0440\u0443\u0442\u0438\u0437\u0430\u0446\u0438\u0435\u0439: \u0447\u0435\u043c \u0432\u044b\u0448\u0435 \u0432\u0435\u0441, \u0442\u0435\u043c \u0447\u0430\u0449\u0435 \u0432\u044b\u0431\u043e\u0440. 1 \u2014 \u043d\u0435\u0439\u0442\u0440\u0430\u043b\u044c\u043d\u043e.",
        "\u0423\u043c\u0435\u043d\u044c\u0448\u0438\u0442\u044c \u0432\u0435\u0441",
        "\u0423\u0432\u0435\u043b\u0438\u0447\u0438\u0442\u044c \u0432\u0435\u0441",
        "\u041e\u043a\u043d\u043e \u043a\u0432\u043e\u0442\u044b",
        "\u041e\u0441\u043d\u043e\u0432\u043d\u043e\u0439 \u0443\u0447\u0430\u0441\u0442\u043d\u0438\u043a \u043e\u0442\u0432\u0435\u0447\u0430\u0435\u0442, \u043f\u043e\u043a\u0430 \u043d\u0435 \u0438\u0441\u0447\u0435\u0440\u043f\u0430\u043d\u044b \u043e\u0442\u043f\u0440\u0430\u0432\u043a\u0438 \u043e\u043a\u043d\u0430, \u0437\u0430\u0442\u0435\u043c \u0440\u0435\u0437\u0435\u0440\u0432 \u0431\u0435\u0440\u0451\u0442 \u0443\u043f\u0440\u0430\u0432\u043b\u0435\u043d\u0438\u0435 \u0434\u043e \u0441\u0431\u0440\u043e\u0441\u0430. \u0418\u0437\u043c\u0435\u043d\u0435\u043d\u0438\u0435 \u043b\u044e\u0431\u043e\u0433\u043e \u0437\u043d\u0430\u0447\u0435\u043d\u0438\u044f \u0441\u0431\u0440\u0430\u0441\u044b\u0432\u0430\u0435\u0442 \u043e\u043a\u043d\u043e.",
        "\u041e\u0442\u043f\u0440\u0430\u0432\u043e\u043a \u0437\u0430 \u043e\u043a\u043d\u043e",
        "\u0414\u043b\u0438\u043d\u0430 \u043e\u043a\u043d\u0430 (\u0447\u0430\u0441\u044b)",
        "\u041e\u043a\u043d\u043e \u043a\u0432\u043e\u0442\u044b \u0441\u0431\u0440\u043e\u0448\u0435\u043d\u043e \u2014 \u043e\u0441\u043d\u043e\u0432\u043d\u043e\u0439 \u0443\u0447\u0430\u0441\u0442\u043d\u0438\u043a \u0441\u043d\u043e\u0432\u0430 \u043e\u0442\u0432\u0435\u0447\u0430\u0435\u0442",
        "\u041e\u0441\u0442\u0430\u043b\u043e\u0441\u044c \u0432 \u044d\u0442\u043e\u043c \u043e\u043a\u043d\u0435: %1$d",
    ],
    "values-tr": [
        "\u0130lk token zaman a\u015f\u0131m\u0131",
        "\u0130lk token i\u00e7in en fazla beklenecek saniye; a\u015f\u0131nca yedek \u00fcyeye ge\u00e7ilir. Bo\u015f = varsay\u0131lan (sohbet 45 sn, ak\u0131l y\u00fcr\u00fctme 90 sn).",
        "Varsay\u0131lan (sohbet 45 sn / ak\u0131l y\u00fcr\u00fctme 90 sn)",
        "\u00dcye a\u011f\u0131rl\u0131klar\u0131",
        "A\u011f\u0131rl\u0131kl\u0131 y\u00f6nlendirmeyi kontrol eder: a\u011f\u0131rl\u0131k ne kadar y\u00fcksekse se\u00e7im o kadar s\u0131k olur. 1 n\u00f6trd\u00fcr.",
        "A\u011f\u0131rl\u0131\u011f\u0131 azalt",
        "A\u011f\u0131rl\u0131\u011f\u0131 art\u0131r",
        "Kota penceresi",
        "Birincil \u00fcye penceredeki g\u00f6nderimler bitene kadar hizmet verir, sonra s\u0131f\u0131rlanana dek yedek devral\u0131r. Bir de\u011feri d\u00fczenlemek pencereyi hemen s\u0131f\u0131rlar.",
        "Pencere ba\u015f\u0131na g\u00f6nderim",
        "Pencere s\u00fcresi (saat)",
        "Kota penceresi s\u0131f\u0131rland\u0131 \u2014 birincil \u00fcye yeniden hizmet veriyor",
        "Bu pencerede kalan: %1$d g\u00f6nderim",
    ],
    "values-th": [
        "\u0e40\u0e27\u0e25\u0e32\u0e23\u0e2d\u0e42\u0e17\u0e40\u0e04\u0e19\u0e41\u0e23\u0e01",
        "\u0e08\u0e33\u0e19\u0e27\u0e19\u0e27\u0e34\u0e19\u0e32\u0e17\u0e35\u0e2a\u0e39\u0e07\u0e2a\u0e38\u0e14\u0e17\u0e35\u0e48\u0e23\u0e2d\u0e42\u0e17\u0e40\u0e04\u0e19\u0e41\u0e23\u0e01\u0e01\u0e48\u0e2d\u0e19\u0e2a\u0e25\u0e31\u0e1a\u0e44\u0e1b\u0e2a\u0e21\u0e32\u0e0a\u0e34\u0e01\u0e2a\u0e33\u0e23\u0e2d\u0e07 \u0e40\u0e27\u0e49\u0e19\u0e27\u0e48\u0e32\u0e07 = \u0e04\u0e48\u0e32\u0e40\u0e23\u0e34\u0e48\u0e21\u0e15\u0e49\u0e19 (\u0e41\u0e0a\u0e17 45 \u0e27\u0e34, \u0e01\u0e32\u0e23\u0e43\u0e0a\u0e49\u0e40\u0e2b\u0e15\u0e38\u0e1c\u0e25 90 \u0e27\u0e34)",
        "\u0e04\u0e48\u0e32\u0e40\u0e23\u0e34\u0e48\u0e21\u0e15\u0e49\u0e19 (\u0e41\u0e0a\u0e17 45 \u0e27\u0e34 / \u0e01\u0e32\u0e23\u0e43\u0e0a\u0e49\u0e40\u0e2b\u0e15\u0e38\u0e1c\u0e25 90 \u0e27\u0e34)",
        "\u0e19\u0e49\u0e33\u0e2b\u0e19\u0e31\u0e01\u0e2a\u0e21\u0e32\u0e0a\u0e34\u0e01",
        "\u0e04\u0e27\u0e1a\u0e04\u0e38\u0e21\u0e01\u0e32\u0e23\u0e08\u0e31\u0e14\u0e40\u0e2a\u0e49\u0e19\u0e17\u0e32\u0e07\u0e41\u0e1a\u0e1a\u0e16\u0e48\u0e27\u0e07\u0e19\u0e49\u0e33\u0e2b\u0e19\u0e31\u0e01: \u0e19\u0e49\u0e33\u0e2b\u0e19\u0e31\u0e01\u0e22\u0e34\u0e48\u0e07\u0e2a\u0e39\u0e07 \u0e22\u0e34\u0e48\u0e07\u0e16\u0e39\u0e01\u0e40\u0e25\u0e37\u0e2d\u0e01\u0e1a\u0e48\u0e2d\u0e22 1 \u0e04\u0e37\u0e2d\u0e04\u0e48\u0e32\u0e1b\u0e01\u0e15\u0e34",
        "\u0e25\u0e14\u0e19\u0e49\u0e33\u0e2b\u0e19\u0e31\u0e01",
        "\u0e40\u0e1e\u0e34\u0e48\u0e21\u0e19\u0e49\u0e33\u0e2b\u0e19\u0e31\u0e01",
        "\u0e2b\u0e19\u0e49\u0e32\u0e15\u0e48\u0e32\u0e07\u0e42\u0e04\u0e27\u0e15\u0e32",
        "\u0e2a\u0e21\u0e32\u0e0a\u0e34\u0e01\u0e2b\u0e25\u0e31\u0e01\u0e43\u0e2b\u0e49\u0e1a\u0e23\u0e34\u0e01\u0e32\u0e23\u0e08\u0e19\u0e01\u0e27\u0e48\u0e32\u0e08\u0e33\u0e19\u0e27\u0e19\u0e01\u0e32\u0e23\u0e2a\u0e48\u0e07\u0e43\u0e19\u0e2b\u0e19\u0e49\u0e32\u0e15\u0e48\u0e32\u0e07\u0e08\u0e30\u0e2b\u0e21\u0e14 \u0e08\u0e32\u0e01\u0e19\u0e31\u0e49\u0e19\u0e2a\u0e21\u0e32\u0e0a\u0e34\u0e01\u0e2a\u0e33\u0e23\u0e2d\u0e07\u0e23\u0e31\u0e1a\u0e0a\u0e48\u0e27\u0e07\u0e15\u0e48\u0e2d\u0e08\u0e19\u0e01\u0e27\u0e48\u0e32\u0e08\u0e30\u0e23\u0e35\u0e40\u0e0b\u0e47\u0e15 \u0e01\u0e32\u0e23\u0e41\u0e01\u0e49\u0e44\u0e02\u0e04\u0e48\u0e32\u0e43\u0e14\u0e04\u0e48\u0e32\u0e2b\u0e19\u0e36\u0e48\u0e07\u0e08\u0e30\u0e23\u0e35\u0e40\u0e0b\u0e47\u0e15\u0e2b\u0e19\u0e49\u0e32\u0e15\u0e48\u0e32\u0e07\u0e17\u0e31\u0e19\u0e17\u0e35",
        "\u0e08\u0e33\u0e19\u0e27\u0e19\u0e2a\u0e48\u0e07\u0e15\u0e48\u0e2d\u0e2b\u0e19\u0e49\u0e32\u0e15\u0e48\u0e32\u0e07",
        "\u0e04\u0e27\u0e32\u0e21\u0e22\u0e32\u0e27\u0e2b\u0e19\u0e49\u0e32\u0e15\u0e48\u0e32\u0e07 (\u0e0a\u0e31\u0e48\u0e27\u0e42\u0e21\u0e07)",
        "\u0e23\u0e35\u0e40\u0e0b\u0e47\u0e15\u0e2b\u0e19\u0e49\u0e32\u0e15\u0e48\u0e32\u0e07\u0e42\u0e04\u0e27\u0e15\u0e32\u0e41\u0e25\u0e49\u0e27 \u2014 \u0e2a\u0e21\u0e32\u0e0a\u0e34\u0e01\u0e2b\u0e25\u0e31\u0e01\u0e01\u0e25\u0e31\u0e1a\u0e21\u0e32\u0e43\u0e2b\u0e49\u0e1a\u0e23\u0e34\u0e01\u0e32\u0e23",
        "\u0e40\u0e2b\u0e25\u0e37\u0e2d\u0e43\u0e19\u0e2b\u0e19\u0e49\u0e32\u0e15\u0e48\u0e32\u0e07\u0e19\u0e35\u0e49: %1$d \u0e04\u0e23\u0e31\u0e49\u0e07",
    ],
    "values-in": [
        "Waktu tunggu token pertama",
        "Detik maksimal menunggu token pertama sebelum beralih ke cadangan. Kosong = bawaan (chat 45 dtk, penalaran 90 dtk).",
        "Bawaan (chat 45 dtk / penalaran 90 dtk)",
        "Bobot anggota",
        "Mengontrol perutean berbobot: semakin tinggi bobot, semakin sering dipilih. 1 netral.",
        "Kurangi bobot",
        "Tambah bobot",
        "Jendela kuota",
        "Anggota utama melayani hingga kiriman jendela habis, lalu cadangan mengambil alih hingga reset. Mengedit nilai apa pun langsung mereset jendela.",
        "Kiriman per jendela",
        "Durasi jendela (jam)",
        "Jendela kuota direset \u2014 anggota utama melayani lagi",
        "Sisa di jendela ini: %1$d kiriman",
    ],
    "values-fil": [
        "Timeout ng unang token",
        "Pinakamataas na segundo ng paghihintay sa unang token bago lumipat sa reserba. Blangko = default (chat 45 s, pangangatwiran 90 s).",
        "Default (chat 45 s / pangangatwiran 90 s)",
        "Timbang ng mga miyembro",
        "Kinokontrol ang weighted na pagruruta: mas mataas ang timbang, mas madalas mapili. Neutral ang 1.",
        "Bawasan ang timbang",
        "Dagdagan ang timbang",
        "Window ng quota",
        "Nagsisilbi ang pangunahing miyembro hanggang maubos ang mga pagpapadala sa window, pagkatapos ay sasalo ang reserba hanggang mag-reset. Ang pag-edit ng anumang halaga ay agad na nagre-reset ng window.",
        "Mga pagpapadala bawat window",
        "Haba ng window (oras)",
        "Na-reset ang window ng quota \u2014 muling nagsisilbi ang pangunahing miyembro",
        "Natitira sa window na ito: %1$d pagpapadala",
    ],
    "values-ms": [
        "Masa tamat token pertama",
        "Saat maksimum menunggu token pertama sebelum beralih ke sandaran. Kosong = lalai (sembang 45 s, penaakulan 90 s).",
        "Lalai (sembang 45 s / penaakulan 90 s)",
        "Pemberat ahli",
        "Mengawal penghalaan berpemberat: semakin tinggi pemberat, semakin kerap dipilih. 1 adalah neutral.",
        "Kurangkan pemberat",
        "Tambah pemberat",
        "Tetingkap kuota",
        "Ahli utama berkhidmat sehingga hantaran tetingkap habis, kemudian sandaran mengambil alih sehingga tetapan semula. Mengedit sebarang nilai menetapkan semula tetingkap serta-merta.",
        "Hantaran setiap tetingkap",
        "Tempoh tetingkap (jam)",
        "Tetingkap kuota ditetapkan semula \u2014 ahli utama berkhidmat semula",
        "Baki dalam tetingkap ini: %1$d hantaran",
    ],
    "values-pl": [
        "Limit czasu pierwszego tokena",
        "Maksymalny czas oczekiwania na pierwszy token w sekundach, po kt\u00f3rym przejmuje zapasowy. Puste = domy\u015blne (czat 45 s, rozumowanie 90 s).",
        "Domy\u015blne (czat 45 s / rozumowanie 90 s)",
        "Wagi cz\u0142onk\u00f3w",
        "Steruje routingiem wa\u017conym: im wy\u017csza waga, tym cz\u0119stszy wyb\u00f3r. 1 jest neutralna.",
        "Zmniejsz wag\u0119",
        "Zwi\u0119ksz wag\u0119",
        "Okno limitu",
        "Cz\u0142onek g\u0142\u00f3wny obs\u0142uguje do wyczerpania wysy\u0142ek w oknie, potem zapasowy przejmuje do resetu. Edycja dowolnej warto\u015bci natychmiast resetuje okno.",
        "Wysy\u0142ki na okno",
        "D\u0142ugo\u015b\u0107 okna (godziny)",
        "Zresetowano okno limitu \u2014 cz\u0142onek g\u0142\u00f3wny znowu obs\u0142uguje",
        "Pozosta\u0142o w tym oknie: %1$d wysy\u0142ek",
    ],
    "values-ro": [
        "Timeout primul token",
        "Secunde maxime de a\u0219teptare a primului token \u00eenainte de trecerea la rezerv\u0103. Gol = implicit (chat 45 s, ra\u021bionament 90 s).",
        "Implicit (chat 45 s / ra\u021bionament 90 s)",
        "Ponderi membri",
        "Controleaz\u0103 rutarea ponderat\u0103: cu c\u00e2t ponderea e mai mare, cu at\u00e2t membrul e ales mai des. 1 este neutru.",
        "Scade ponderea",
        "Cre\u0219te ponderea",
        "Fereastr\u0103 de cot\u0103",
        "Membrul principal deserve\u0219te p\u00e2n\u0103 se epuizeaz\u0103 trimiterile ferestrei, apoi rezerva preia p\u00e2n\u0103 la resetare. Editarea oric\u0103rei valori reseteaz\u0103 imediat fereastra.",
        "Trimiteri pe fereastr\u0103",
        "Durata ferestrei (ore)",
        "Fereastra de cot\u0103 a fost resetat\u0103 \u2014 membrul principal deserve\u0219te din nou",
        "R\u0103mase \u00een aceast\u0103 fereastr\u0103: %1$d trimiteri",
    ],
}

RES_DIR = Path("/var/minis/workspace/OpenMinis-Linux/src/android/app/src/main/res")


def xml_escape(text: str) -> str:
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def main() -> None:
    total_added = 0
    for locale, translations in LOCALES.items():
        assert len(translations) == len(KEYS), f"{locale}: {len(translations)} != {len(KEYS)}"
        path = RES_DIR / locale / "strings.xml"
        if not path.exists():
            print(f"SKIP missing {path}")
            continue
        content = path.read_text(encoding="utf-8")
        added = 0
        for key, text in zip(KEYS, translations):
            if f'name="{key}"' in content:
                continue
            entry = f'    <string name="{key}">{xml_escape(text)}</string>\n'
            marker = "</resources>"
            if marker not in content:
                raise SystemExit(f"{path}: no </resources> marker")
            content = content.replace(marker, entry + marker, 1)
            added += 1
        if added:
            path.write_text(content, encoding="utf-8")
            total_added += added
            print(f"{locale}: +{added}")
        else:
            print(f"{locale}: no-op")
    print(f"TOTAL added: {total_added}")


if __name__ == "__main__":
    main()
